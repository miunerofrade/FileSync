package main

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestSanitizeRelPath(t *testing.T) {
	valid := []string{
		"a.md",
		"docs/a.md",
		"a/b/c/deep/file.bin",
		"中文/文件 PDF.pdf",
		".gitignore",
		"a..b",
		"123",
		"archive.tar.gz",
		"conx.txt",
		"notes final.md",
	}
	for _, p := range valid {
		got, err := SanitizeRelPath(p)
		if err != nil {
			t.Errorf("SanitizeRelPath(%q) 意外报错: %v", p, err)
			continue
		}
		if got != p {
			t.Errorf("SanitizeRelPath(%q) = %q, 期望保持不变", p, got)
		}
	}

	invalid := []string{
		"",
		"/abs.txt",
		"..",
		".",
		"...",
		"../x",
		"a/../b",
		"a/./b",
		"a/",
		"a//b",
		"C:/x",
		"C:\\x",
		"a\\b",
		"x:y",
		"CON",
		"con.txt",
		"NUL",
		"Aux.csv",
		"COM1",
		"lpt9.log",
		"a.",
		"a ",
		"a/b.",
		"a\x01b",
		"x\x7f",
		strings.Repeat("a", 256),
	}
	for _, p := range invalid {
		if got, err := SanitizeRelPath(p); err == nil {
			t.Errorf("SanitizeRelPath(%q) = %q, 期望报错", p, got)
		}
	}
}

func TestWithinRoot(t *testing.T) {
	root := filepath.Join("srv", "data")
	cases := []struct {
		p    string
		want bool
	}{
		{filepath.Join(root, "a.txt"), true},
		{filepath.Join(root, "d", "b.txt"), true},
		{root, true},
		{filepath.Join("srv", "other"), false},
		{filepath.Join("srv", "data2"), false},
		{"other", false},
	}
	for _, c := range cases {
		if got := withinRoot(root, c.p); got != c.want {
			t.Errorf("withinRoot(%q, %q) = %v, 期望 %v", root, c.p, got, c.want)
		}
	}
}
