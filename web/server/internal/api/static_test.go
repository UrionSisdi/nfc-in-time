package api

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/urionsisdi/nfc-in-time/web/server/internal/config"
)

// publicDir is the landing itself, not a fixture: the point of the test is that
// the page shipped in the repository renders in both languages.
const publicDir = "../../../public"

func newStatic(t *testing.T) http.Handler {
	t.Helper()
	l, err := loadLanding(publicDir)
	if err != nil {
		t.Fatalf("load landing: %v", err)
	}
	s := &Server{cfg: config.Config{StaticDir: publicDir}, landing: l}
	return s.static()
}

func TestStaticRoutes(t *testing.T) {
	h := newStatic(t)

	cases := []struct {
		path string
		want int
	}{
		{"/", http.StatusOK},
		{"/ru/", http.StatusOK},
		{"/ru", http.StatusMovedPermanently},
		{"/index.html", http.StatusMovedPermanently}, // never the raw template
		{"/robots.txt", http.StatusOK},
		{"/sitemap.xml", http.StatusOK},
		{"/llms.txt", http.StatusOK},
		{"/de/", http.StatusNotFound},
		{"/nothing-here", http.StatusNotFound},
	}
	for _, c := range cases {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, c.path, nil))
		if w.Code != c.want {
			t.Errorf("GET %s = %d, want %d", c.path, w.Code, c.want)
		}
	}
}

// TestLandingIsTranslated is the whole reason the page is rendered on the
// server: a crawler that runs no scripts has to find the Russian text, the
// Russian <html lang> and the Russian canonical in the bytes it is served.
func TestLandingIsTranslated(t *testing.T) {
	h := newStatic(t)

	cases := []struct {
		path           string
		want, unwanted []string
	}{
		{
			path: "/",
			want: []string{
				`lang="en"`,
				`<link rel="canonical" id="canonical" href="https://in-time-nfc.ru/">`,
				"Time in the world",
				"The hand on top takes",
			},
			unwanted: []string{"Времени в мире"},
		},
		{
			path: "/ru/",
			want: []string{
				`lang="ru"`,
				`<link rel="canonical" id="canonical" href="https://in-time-nfc.ru/ru/">`,
				"Времени в мире",
				"Рука сверху забирает",
				`content="ru_RU"`,
			},
			unwanted: []string{"Time in the world"},
		},
	}

	for _, c := range cases {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, c.path, nil))
		body := w.Body.String()
		if !strings.Contains(body, "window.NFCIT_I18N") {
			t.Fatalf("GET %s ships no translation table for the runtime", c.path)
		}
		// The table travels with the page and holds both languages; what has
		// to be in one language is the markup around it.
		markup, _, _ := strings.Cut(body, "window.NFCIT_I18N")

		for _, want := range c.want {
			if !strings.Contains(markup, want) {
				t.Errorf("GET %s does not carry %q", c.path, want)
			}
		}
		for _, unwanted := range c.unwanted {
			if strings.Contains(markup, unwanted) {
				t.Errorf("GET %s still carries %q", c.path, unwanted)
			}
		}
	}
}
