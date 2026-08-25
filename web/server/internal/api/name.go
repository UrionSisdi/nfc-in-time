package api

import (
	"errors"
	"strings"
	"unicode"
)

// maxNameRunes keeps the public table readable. The landing page gives a handle
// one narrow column, and nothing enforces a width on the client side. Telegram
// hands over a first and last name, so the limit has to fit one.
const maxNameRunes = 32

var errBadName = errors.New("name must be 1 to 32 visible characters")

// cleanName normalises a player-chosen handle. Control and format characters go
// out entirely: a bidi override or a zero-width joiner in a nickname reorders
// the whole row it is rendered in, and the board is public.
func cleanName(raw string) (string, error) {
	var b strings.Builder
	space := false

	for _, r := range strings.TrimSpace(raw) {
		switch {
		case unicode.IsControl(r), unicode.Is(unicode.Cf, r), r == unicode.ReplacementChar:
			return "", errBadName
		case unicode.IsSpace(r):
			space = true
		default:
			if space && b.Len() > 0 {
				b.WriteRune(' ')
			}
			space = false
			b.WriteRune(r)
		}
	}

	name := b.String()
	if name == "" || len([]rune(name)) > maxNameRunes {
		return "", errBadName
	}
	return name, nil
}
