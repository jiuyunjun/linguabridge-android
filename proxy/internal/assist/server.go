package assist

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type Handler struct {
	Engine     Engine
	TokenHash  [32]byte
	Timeout    time.Duration
	mu         sync.Mutex
	credits    float64
	last       time.Time
	concurrent chan struct{}
}

func NewHandler(engine Engine, token string) *Handler {
	return &Handler{Engine: engine, TokenHash: sha256.Sum256([]byte(token)), Timeout: 60 * time.Second, credits: 10, last: time.Now(), concurrent: make(chan struct{}, 2)}
}

func writeError(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	if status == 429 {
		w.Header().Set("Retry-After", "6")
	}
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]string{"code": code, "message": "Request could not be completed. Review input or retry later."}})
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.URL.Path != "/v1/assist" {
		writeError(w, 404, "not_found")
		return
	}
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeError(w, 405, "method_not_allowed")
		return
	}
	auth := r.Header.Get("Authorization")
	if len(auth) > 512 || !strings.HasPrefix(auth, "Bearer ") {
		writeError(w, 401, "unauthorized")
		return
	}
	hash := sha256.Sum256([]byte(strings.TrimPrefix(auth, "Bearer ")))
	if subtle.ConstantTimeCompare(hash[:], h.TokenHash[:]) != 1 {
		writeError(w, 401, "unauthorized")
		return
	}
	if !strings.EqualFold(strings.TrimSpace(strings.Split(r.Header.Get("Content-Type"), ";")[0]), "application/json") {
		writeError(w, 415, "content_type")
		return
	}
	h.mu.Lock()
	now := time.Now()
	h.credits = min(10, h.credits+now.Sub(h.last).Seconds()/6)
	h.last = now
	allowed := h.credits >= 1
	if allowed {
		h.credits--
	}
	h.mu.Unlock()
	if !allowed {
		writeError(w, 429, "rate_limited")
		return
	}
	select {
	case h.concurrent <- struct{}{}:
		defer func() { <-h.concurrent }()
	default:
		writeError(w, 429, "busy")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, MaxBodyBytes)
	raw, readErr := io.ReadAll(r.Body)
	if readErr != nil || !utf8.Valid(raw) {
		writeError(w, 400, "invalid_request")
		return
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	var input Request
	if decoder.Decode(&input) != nil || decoder.Decode(new(any)) != io.EOF || input.Validate() != nil {
		writeError(w, 400, "invalid_request")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), h.Timeout)
	defer cancel()
	result, err := h.Engine.Generate(ctx, input)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(ctx.Err(), context.DeadlineExceeded) {
			writeError(w, 504, "timeout")
			return
		}
		if errors.Is(err, context.Canceled) || errors.Is(ctx.Err(), context.Canceled) {
			writeError(w, 408, "cancelled")
			return
		}
		var failure *Failure
		if errors.As(err, &failure) {
			writeError(w, failure.Status, failure.Code)
		} else {
			writeError(w, 502, "upstream_unavailable")
		}
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(result)
}
