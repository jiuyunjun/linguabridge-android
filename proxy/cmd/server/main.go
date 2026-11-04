package main

import (
	"context"
	"crypto/tls"
	"io"
	"linguabridge/proxy/internal/assist"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"regexp"
	"strconv"
	"strings"
	"time"
)

func main() {
	logger := log.New(os.Stderr, "", log.LstdFlags)
	token := os.Getenv("DEVICE_TOKEN")
	if !regexp.MustCompile(`^[A-Za-z0-9_-]{32,256}$`).MatchString(token) || strings.HasPrefix(token, "sk-") {
		logger.Fatal("DEVICE_TOKEN must be a separate strong device token (32-256 URL-safe characters)")
	}
	address := os.Getenv("LISTEN_ADDR")
	if address == "" {
		address = "127.0.0.1:8787"
	}
	host, _, err := net.SplitHostPort(address)
	ip := net.ParseIP(host)
	if err != nil || ip == nil || !ip.IsLoopback() {
		logger.Fatal("LISTEN_ADDR must be a loopback IP; expose through an HTTPS reverse proxy only")
	}
	var engine assist.Engine
	timeoutSeconds := 60
	if configured := os.Getenv("REQUEST_TIMEOUT_SECONDS"); configured != "" {
		parsed, parseErr := strconv.Atoi(configured)
		if parseErr != nil || parsed < 1 || parsed > 120 {
			logger.Fatal("REQUEST_TIMEOUT_SECONDS must be between 1 and 120")
		}
		timeoutSeconds = parsed
	}
	timeout := time.Duration(timeoutSeconds) * time.Second
	if os.Getenv("DEMO_MODE") == "1" {
		engine = assist.Demo{}
		logger.Print("Fixed DEMO mode: no OpenAI calls")
	} else {
		key, model := os.Getenv("OPENAI_API_KEY"), os.Getenv("OPENAI_MODEL")
		if key == "" || model == "" {
			logger.Fatal("OPENAI_API_KEY and OPENAI_MODEL are required outside DEMO_MODE")
		}
		client := &http.Client{Timeout: timeout + 2*time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }, Transport: &http.Transport{
			Proxy: http.ProxyFromEnvironment, ForceAttemptHTTP2: true, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12}, ResponseHeaderTimeout: timeout, MaxResponseHeaderBytes: 16384,
		}}
		engine = &assist.OpenAI{HTTP: client, APIKey: key, Model: model}
	}
	handler := assist.NewHandler(engine, token)
	handler.Timeout = timeout
	server := &http.Server{Addr: address, Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second, WriteTimeout: timeout + 10*time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 8192, ErrorLog: log.New(io.Discard, "", 0)}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	logger.Print("Assistant proxy listening on loopback; HTTPS required for deployment")
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		logger.Fatal("Server stopped unexpectedly")
	}
}
