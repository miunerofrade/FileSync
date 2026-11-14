package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestTokenAuth(t *testing.T) {
	srv, err := NewServer(t.TempDir(), "secret123")
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	try := func(token string) int {
		req, _ := http.NewRequest("GET", ts.URL+"/api/ping", nil)
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		return resp.StatusCode
	}

	if got := try(""); got != 401 {
		t.Errorf("无令牌期望 401, 实际 %d", got)
	}
	if got := try("wrong"); got != 401 {
		t.Errorf("错误令牌期望 401, 实际 %d", got)
	}
	if got := try("secret123"); got != 200 {
		t.Errorf("正确令牌期望 200, 实际 %d", got)
	}
}

func TestNoTokenServerAllowsAll(t *testing.T) {
	srv, err := NewServer(t.TempDir(), "")
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()
	resp, err := http.Get(ts.URL + "/api/ping")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Errorf("未启用认证时期望 200, 实际 %d", resp.StatusCode)
	}
}

func TestClientSubdirMapping(t *testing.T) {
	var uris []string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		uris = append(uris, r.URL.RequestURI())
		switch {
		case r.Method == http.MethodPut:
			w.WriteHeader(http.StatusCreated)
		case r.URL.Path == "/api/ls":
			// 模拟服务端 prefix 过滤：只返回该子树内的文件
			if strings.Contains(r.URL.RawQuery, "prefix=photos") {
				w.Write([]byte(`[{"path":"photos/a.jpg","size":3,"sha256":"aa"}]`))
			} else {
				w.Write([]byte(`[{"path":"photos/a.jpg","size":3,"sha256":"aa"},{"path":"other/x.txt","size":1,"sha256":"bb"}]`))
			}
		default:
			w.Write([]byte(`{"error":"unknown"}`))
		}
	}))
	defer ts.Close()

	c, err := NewClient(ts.URL, "", "photos")
	if err != nil {
		t.Fatal(err)
	}

	// Put：wire 应映射为 photos/a.jpg
	if _, err := c.Put("a.jpg", 3, "aa", io.LimitReader(nil, 0), false, ""); err != nil && len(uris) == 0 {
		t.Fatalf("put 失败: %v", err)
	}
	_ = io.Discard
	if len(uris) == 0 || !strings.Contains(uris[0], "path=photos%2Fa.jpg") {
		t.Errorf("Put 未加 subdir 前缀: %v", uris)
	}

	// Ls：请求 prefix=photos，返回条目剥前缀（other/x.txt 不应出现在结果）
	uris = nil
	entries, err := c.Ls("")
	if err != nil {
		t.Fatal(err)
	}
	if len(uris) == 0 || !strings.Contains(uris[0], "prefix=photos") {
		t.Errorf("Ls 未请求 subdir 前缀: %v", uris)
	}
	if len(entries) != 1 || entries[0].Path != "a.jpg" {
		t.Errorf("Ls 应剥前缀且只含本组文件: %+v", entries)
	}
}
