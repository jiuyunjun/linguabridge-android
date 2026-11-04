package assist

import (
	"errors"
	"regexp"
	"strings"
	"unicode/utf8"
)

const MaxBodyBytes = 48 * 1024

type Block struct {
	ID     string `json:"id"`
	Path   string `json:"path"`
	Bounds []int  `json:"bounds"`
	Text   string `json:"text"`
}

type Request struct {
	RequestID      string  `json:"requestId"`
	Operation      string  `json:"operation"`
	TargetLanguage string  `json:"targetLanguage"`
	PageType       string  `json:"pageType"`
	SourcePackage  string  `json:"sourcePackage"`
	TargetBlockID  string  `json:"targetBlockId"`
	UserIntent     string  `json:"userIntent"`
	Tone           string  `json:"tone,omitempty"`
	Blocks         []Block `json:"blocks"`
}

// Translation is one translated page block for operation translate_page.
type Translation struct {
	ID   string `json:"id"`
	Text string `json:"text"`
}

type Result struct {
	RequestID          string        `json:"requestId"`
	Text               string        `json:"text"`
	Notes              string        `json:"notes"`
	NeedsClarification bool          `json:"needsClarification"`
	Questions          []string      `json:"questions"`
	Translations       []Translation `json:"translations"`
	Model              string        `json:"model"`
	Demo               bool          `json:"demo"`
}

var idPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,80}$`)
var pathPattern = regexp.MustCompile(`^[0-9]+(/[0-9]+)*$`)
var packagePattern = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`)

// A deliberately conservative estimate: at most one token per UTF-8 input byte.
// IDs, paths and numeric geometry have their own bounds and are not page text.
func (r Request) Validate() error {
	invalid := errors.New("invalid request")
	if !idPattern.MatchString(r.RequestID) || !packagePattern.MatchString(r.SourcePackage) || len(r.SourcePackage) > 200 {
		return invalid
	}
	switch r.TargetLanguage {
	case "zh", "ja", "en":
	case "auto": // Reply in the language of the page conversation.
		if r.Operation != "reply" {
			return invalid
		}
	default:
		return invalid
	}
	switch r.Tone {
	case "", "formal", "casual", "snarky", "rude": // same ids as the LinguaBridge extension
	default:
		return invalid
	}
	if r.Tone != "" && r.Operation != "reply" {
		return invalid
	}
	switch r.PageType {
	case "auto", "chat", "email", "article", "other":
	default:
		return invalid
	}
	if r.Operation != "translate" && r.Operation != "translate_page" && r.Operation != "reply" {
		return invalid
	}
	if len(r.Blocks) < 1 || len(r.Blocks) > 60 || utf8.RuneCountInString(r.UserIntent) > 2000 {
		return invalid
	}
	if r.Operation == "reply" && strings.TrimSpace(r.UserIntent) == "" {
		return invalid
	}
	if r.Operation != "reply" && r.UserIntent != "" {
		return invalid
	}
	if r.Operation == "translate_page" && r.TargetBlockID != "" {
		return invalid
	}
	ids := map[string]bool{}
	totalBytes, totalChars := len(r.UserIntent), utf8.RuneCountInString(r.UserIntent)
	for _, b := range r.Blocks {
		if !idPattern.MatchString(b.ID) || ids[b.ID] || len(b.Path) > 160 || !pathPattern.MatchString(b.Path) {
			return invalid
		}
		ids[b.ID] = true
		if strings.TrimSpace(b.Text) == "" || !utf8.ValidString(b.Text) || utf8.RuneCountInString(b.Text) > 1500 {
			return invalid
		}
		if len(b.Bounds) != 4 || b.Bounds[2] < b.Bounds[0] || b.Bounds[3] < b.Bounds[1] {
			return invalid
		}
		for _, v := range b.Bounds {
			if v < -10000 || v > 100000 {
				return invalid
			}
		}
		totalBytes += len(b.Text)
		totalChars += utf8.RuneCountInString(b.Text)
	}
	if totalBytes > 6000 || totalChars > 8000 || !utf8.ValidString(r.UserIntent) {
		return invalid
	}
	if (r.Operation == "translate" && r.TargetBlockID == "") || (r.TargetBlockID != "" && !ids[r.TargetBlockID]) {
		return invalid
	}
	return nil
}
