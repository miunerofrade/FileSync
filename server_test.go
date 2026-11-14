package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
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

func TestDeleteEndpoint(t *testing.T) {
	srv, err := NewServer(t.TempDir(), "secret123")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(srv.root, "a.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	_ = os.MkdirAll(filepath.Join(srv.root, "d/sub"), 0o755)
	_ = os.WriteFile(filepath.Join(srv.root, "d/sub/b.txt"), []byte("x"), 0o644)

	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	del := func(path string, recursive bool) int {
		q := url.Values{"path": {path}}
		if recursive {
			q.Set("recursive", "1")
		}
		req, _ := http.NewRequest(http.MethodDelete, ts.URL+"/api/delete?"+q.Encode(), nil)
		req.Header.Set("Authorization", "Bearer secret123")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		return resp.StatusCode
	}

	if got := del("a.txt", false); got != 200 {
		t.Errorf("删除文件期望 200, 实际 %d", got)
	}
	if _, err := os.Stat(filepath.Join(srv.root, "a.txt")); !os.IsNotExist(err) {
		t.Errorf("文件应已被删除")
	}
	// 幂等：再删一次返回 404，客户端据此视为已删除
	if got := del("a.txt", false); got != 404 {
		t.Errorf("重复删除期望 404, 实际 %d", got)
	}
	// 删除文件后应清理变空的父目录（不留目录骨架）
	_ = os.MkdirAll(filepath.Join(srv.root, "e/f"), 0o755)
	_ = os.WriteFile(filepath.Join(srv.root, "e/f/c.txt"), []byte("y"), 0o644)
	if got := del("e/f/c.txt", false); got != 200 {
		t.Errorf("删除嵌套文件期望 200, 实际 %d", got)
	}
	for _, p := range []string{"e/f", "e"} {
		if _, err := os.Stat(filepath.Join(srv.root, filepath.FromSlash(p))); !os.IsNotExist(err) {
			t.Errorf("空父目录 %s 应被清理", p)
		}
	}
	// 防误删根目录
	if got := del("", false); got != 400 {
		t.Errorf("空路径期望 400, 实际 %d", got)
	}
	if got := del("", true); got != 400 {
		t.Errorf("空路径递归期望 400, 实际 %d", got)
	}
	// 非空目录需 recursive=1
	if got := del("d", false); got != 400 {
		t.Errorf("非空目录不递归期望 400, 实际 %d", got)
	}
	if got := del("d", true); got != 200 {
		t.Errorf("递归删除目录期望 200, 实际 %d", got)
	}
	if _, err := os.Stat(filepath.Join(srv.root, "d")); !os.IsNotExist(err) {
		t.Errorf("目录子树应已被删除")
	}
}

func TestClientDeleteMapping(t *testing.T) {
	var gotURI, gotMethod string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotURI, gotMethod = r.URL.RequestURI(), r.Method
		w.Write([]byte(`{}`))
	}))
	defer ts.Close()

	c, err := NewClient(ts.URL, "", "photos")
	if err != nil {
		t.Fatal(err)
	}
	if err := c.Delete("a/b.jpg", true); err != nil {
		t.Fatalf("delete 失败: %v", err)
	}
	if gotMethod != http.MethodDelete {
		t.Errorf("方法应为 DELETE, 实际 %s", gotMethod)
	}
	if !strings.Contains(gotURI, "path=photos%2Fa%2Fb.jpg") || !strings.Contains(gotURI, "recursive=1") {
		t.Errorf("未正确映射 subdir/recursive: %s", gotURI)
	}
	// 云端根目录拒绝（subdir 为空时）
	c2, _ := NewClient(ts.URL, "", "")
	if err := c2.Delete("", false); err == nil {
		t.Errorf("空路径应被拒绝")
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
