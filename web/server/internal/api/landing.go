package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"html/template"
	"os"
	"path/filepath"
	"time"
)

// site is the address the page is canonical at. It is baked in rather than
// configured: it appears in the sitemap and the structured data as well, and a
// canonical link that follows the Host header is a canonical link an attacker
// can rewrite.
const site = "https://in-time-nfc.ru/"

// languages are the two the landing is written in, in the order the page names
// them. The first is the one the root serves.
var languages = []struct{ code, path, locale string }{
	{"en", "/", "en_US"},
	{"ru", "/ru/", "ru_RU"},
}

// landing holds the page rendered once per language. A crawler that does not
// run scripts still has to read the Russian text, so the translation is applied
// to the markup here rather than in the browser; the whole table travels with
// the page anyway, because the runtime needs the other language for the switch
// and the plural forms for every number it prints.
type landing struct {
	pages   map[string][]byte
	modTime time.Time
}

func loadLanding(dir string) (*landing, error) {
	raw, err := os.ReadFile(filepath.Join(dir, "i18n.json"))
	if err != nil {
		return nil, fmt.Errorf("read translations: %w", err)
	}
	var dict map[string]map[string]any
	if err := json.Unmarshal(raw, &dict); err != nil {
		return nil, fmt.Errorf("parse translations: %w", err)
	}

	// Re-encoded rather than passed through, so a stray byte in the file
	// cannot end up inside a <script> tag unescaped.
	table, err := json.Marshal(dict)
	if err != nil {
		return nil, fmt.Errorf("encode translations: %w", err)
	}

	index := filepath.Join(dir, "index.html")
	tmpl, err := template.ParseFiles(index)
	if err != nil {
		return nil, fmt.Errorf("parse landing template: %w", err)
	}
	info, err := os.Stat(index)
	if err != nil {
		return nil, fmt.Errorf("stat landing template: %w", err)
	}

	l := &landing{pages: make(map[string][]byte, len(languages)), modTime: info.ModTime()}
	for i, lang := range languages {
		words, ok := dict[lang.code]
		if !ok {
			return nil, fmt.Errorf("translations have no %q", lang.code)
		}
		text := make(map[string]string, len(words))
		for k, v := range words {
			if s, ok := v.(string); ok {
				text[k] = s
			}
		}

		var buf bytes.Buffer
		err := tmpl.Execute(&buf, struct {
			Lang, Locale, AltLocale, URL string
			T                            map[string]string
			Dict                         template.JS
		}{
			Lang:      lang.code,
			Locale:    lang.locale,
			AltLocale: languages[(i+1)%len(languages)].locale,
			URL:       site + lang.path[1:],
			T:         text,
			Dict:      template.JS(table),
		})
		if err != nil {
			return nil, fmt.Errorf("render landing in %s: %w", lang.code, err)
		}
		l.pages[lang.path] = buf.Bytes()
	}
	return l, nil
}

func (l *landing) page(path string) ([]byte, bool) {
	p, ok := l.pages[path]
	return p, ok
}
