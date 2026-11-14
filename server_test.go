package main

import (
	"net/http"
	"net/http/httptest"
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
