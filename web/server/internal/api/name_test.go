package api

import "testing"

func TestCleanName(t *testing.T) {
	ok := []struct{ in, want string }{
		{"MERIDIAN", "MERIDIAN"},
		{"  spaced  out  ", "spaced out"},
		{"Ева", "Ева"},
		{"кто-то_ещё 42", "кто-то_ещё 42"},
	}
	for _, c := range ok {
		got, err := cleanName(c.in)
		if err != nil || got != c.want {
			t.Errorf("cleanName(%q) = %q, %v; want %q", c.in, got, err, c.want)
		}
	}

	bad := []string{
		"",
		"   ",
		"‮gnol oot",                            // bidi override reorders the whole table row
		"zero​width",                           // invisible joiner, two handles render alike
		"line\nbreak",                          // control character
		"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", // 36 runes
	}
	for _, in := range bad {
		if got, err := cleanName(in); err == nil {
			t.Errorf("cleanName(%q) = %q, want an error", in, got)
		}
	}
}
