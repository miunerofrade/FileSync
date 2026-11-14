package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const defaultServer = "http://127.0.0.1:8080"

type Client struct {
	base  string
	token string
	sub   string // 云端子目录前缀（subdir 同步组，空 = 服务器根）
	hc    *http.Client
}

func NewClient(server, token, sub string) (*Client, error) {
	server = strings.TrimSuffix(server, "/")
	if !strings.Contains(server, "://") {
		server = "https://" + server
	}
	u, err := url.Parse(server)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, fmt.Errorf("无效的 --server 地址 %q（示例: https://124.222.6.120:8443）", server)
	}
	if sub != "" {
		s, err := SanitizeRelPath(strings.TrimSuffix(strings.TrimSpace(sub), "/"))
		if err != nil {
			return nil, fmt.Errorf("subdir 不合法: %v", err)
		}
		sub = s
	}
	return &Client{base: server, token: strings.TrimSpace(token), sub: sub, hc: &http.Client{}}, nil
}

// joinSub 把本地视角的相对路径映射为云端路径（subdir 前缀）。
func (c *Client) joinSub(rel string) string {
	if c.sub == "" || rel == "" {
		return rel
	}
	return c.sub + "/" + rel
}

// newRequest 统一附加认证头。
func (c *Client) newRequest(method, url string, body io.Reader) (*http.Request, error) {
	req, err := http.NewRequest(method, url, body)
	if err != nil {
		return nil, err
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	return req, nil
}

type apiError struct {
	status int
	msg    string
}

func (e *apiError) Error() string {
	return fmt.Sprintf("服务端 %d: %s", e.status, e.msg)
}

func serverError(resp *http.Response) error {
	msg := resp.Status
	var body struct {
		Error string `json:"error"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 8<<10)).Decode(&body); err == nil && body.Error != "" {
		msg = body.Error
	}
	return &apiError{status: resp.StatusCode, msg: msg}
}

// Ls 获取服务端文件列表（prefix 为空表示全部）。
func (c *Client) Ls(prefix string) ([]FileEntry, error) {
	full := c.sub
	if prefix != "" {
		full = c.joinSub(prefix)
	}
	v := url.Values{}
	if full != "" {
		v.Set("prefix", full)
	}
	req, err := c.newRequest(http.MethodGet, c.base+"/api/ls?"+v.Encode(), nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.hc.Do(req)
	if err != nil {
		return nil, fmt.Errorf("连接服务端失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, serverError(resp)
	}
	var entries []FileEntry
	if err := json.NewDecoder(resp.Body).Decode(&entries); err != nil {
		return nil, fmt.Errorf("解析文件列表失败: %w", err)
	}
	if full != "" {
		p := full + "/"
		for i := range entries {
			entries[i].Path = strings.TrimPrefix(entries[i].Path, p)
		}
	}
	return entries, nil
}

// Put 上传文件内容。返回服务端状态码；错误仅表示请求层面的失败。
// ifMatch 非空时启用条件覆盖：仅当服务器当前内容哈希与之一致才接受。
func (c *Client) Put(wire string, size int64, sum string, body io.Reader, overwrite bool, ifMatch string) (int, error) {
	wire = c.joinSub(wire)
	v := url.Values{}
	v.Set("path", wire)
	if overwrite {
		v.Set("overwrite", "1")
	}
	if ifMatch != "" {
		v.Set("if-match", ifMatch)
	}
	req, err := c.newRequest(http.MethodPut, c.base+"/api/put?"+v.Encode(), body)
	if err != nil {
		return 0, err
	}
	req.ContentLength = size
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("X-Checksum-SHA256", sum)
	// 服务端拒绝（如远端已存在）时不读取请求体；
	// Expect: 100-continue 让客户端先等确认再发送文件内容。
	req.Header.Set("Expect", "100-continue")

	resp, err := c.hc.Do(req)
	if err != nil {
		return 0, fmt.Errorf("上传请求失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		return resp.StatusCode, serverError(resp)
	}
	_, _ = io.Copy(io.Discard, resp.Body)
	return http.StatusCreated, nil
}

// Get 下载远端文件到 localPath：临时文件 + SHA-256 校验 + 原子改名，
// 任何失败都不会改动已存在的本地文件。返回写入字节数与实际内容的 SHA-256。
func (c *Client) Get(wire, localPath string, overwrite bool) (int64, string, error) {
	wire = c.joinSub(wire)
	if info, err := os.Stat(localPath); err == nil && info.IsDir() {
		return 0, "", fmt.Errorf("本地目标 %q 是目录", localPath)
	}
	if _, err := os.Lstat(localPath); err == nil && !overwrite {
		return 0, "", fmt.Errorf("本地文件已存在: %s（--overwrite 可覆盖）", localPath)
	}

	req, err := c.newRequest(http.MethodGet, c.base+"/api/get?"+url.Values{"path": {wire}}.Encode(), nil)
	if err != nil {
		return 0, "", err
	}
	resp, err := c.hc.Do(req)
	if err != nil {
		return 0, "", fmt.Errorf("下载请求失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotFound {
		return 0, "", fmt.Errorf("远端不存在: %s", wire)
	}
	if resp.StatusCode != http.StatusOK {
		return 0, "", serverError(resp)
	}
	want := strings.ToLower(resp.Header.Get("X-Checksum-SHA256"))
	if len(want) != 64 {
		return 0, "", fmt.Errorf("服务端未提供校验和，拒绝写入")
	}

	dir := filepath.Dir(localPath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return 0, "", err
	}
	tmp, err := os.CreateTemp(dir, ".fsync-get-*")
	if err != nil {
		return 0, "", err
	}
	tmpName := tmp.Name()
	done := false
	defer func() {
		if !done {
			tmp.Close()
			os.Remove(tmpName)
		}
	}()
	_ = tmp.Chmod(0o644)

	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(tmp, h), resp.Body)
	if err != nil {
		return 0, "", fmt.Errorf("下载中断（本地文件未改动）: %w", err)
	}
	if resp.ContentLength >= 0 && n != resp.ContentLength {
		return 0, "", fmt.Errorf("下载不完整（%d/%d 字节），本地文件未改动", n, resp.ContentLength)
	}
	got := hex.EncodeToString(h.Sum(nil))
	if got != want {
		return 0, "", fmt.Errorf("SHA-256 校验失败（got %s want %s），本地文件未改动", got, want)
	}
	if err := tmp.Sync(); err != nil {
		return 0, "", err
	}
	if err := tmp.Close(); err != nil {
		return 0, "", err
	}
	// 再确认一次“不覆盖”，尽量收窄并发窗口
	if _, err := os.Lstat(localPath); err == nil && !overwrite {
		return 0, "", fmt.Errorf("本地文件已存在: %s", localPath)
	}
	if err := os.Rename(tmpName, localPath); err != nil {
		return 0, "", err
	}
	done = true
	return n, got, nil
}

// errRemoteExists 标记“远端已存在且未覆盖”的冲突，目录上传据此跳过。
var errRemoteExists = errors.New("远端已存在")

// putFile 上传单个本地文件。remoteArg 为空时取本地文件名。
func putFile(c *Client, localPath, remoteArg string, overwrite bool) error {
	wire := remoteArg
	if wire == "" {
		wire = filepath.Base(localPath)
	}
	wire = filepath.ToSlash(filepath.Clean(wire))
	clean, err := SanitizeRelPath(wire)
	if err != nil {
		return fmt.Errorf("远端路径 %q 不合法: %w", remoteArg, err)
	}
	sum, err := fileSHA256(localPath)
	if err != nil {
		return err
	}
	f, err := os.Open(localPath)
	if err != nil {
		return err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}
	status, err := c.Put(clean, info.Size(), sum, f, overwrite, "")
	if err != nil {
		if status == http.StatusConflict {
			return fmt.Errorf("%w: %w", errRemoteExists, err)
		}
		return err
	}
	fmt.Printf("PUT %s (%s)\n", clean, formatBytes(info.Size()))
	return nil
}

func cmdLs(args []string) {
	fsSet := flag.NewFlagSet("ls", flag.ExitOnError)
	server := fsSet.String("server", defaultServer, "服务端地址")
	token := fsSet.String("token", os.Getenv("FILESYNC_TOKEN"), "访问令牌")
	subdir := fsSet.String("subdir", "", "云端子目录（同步组前缀）")
	prefix := fsSet.String("prefix", "", "仅列出该远端子目录")
	fsSet.Parse(args)
	if fsSet.NArg() != 0 {
		badUsage()
	}
	p := ""
	if *prefix != "" {
		p = strings.TrimSuffix(filepath.ToSlash(filepath.Clean(*prefix)), "/")
		if p == "." || p == "/" {
			p = ""
		} else {
			var err error
			if p, err = SanitizeRelPath(p); err != nil {
				fatal("prefix 不合法: %v", err)
			}
		}
	}
	c, err := NewClient(*server, *token, *subdir)
	if err != nil {
		fatal("%v", err)
	}
	entries, err := c.Ls(p)
	if err != nil {
		fatal("%v", err)
	}
	if len(entries) == 0 {
		fmt.Println("(空)")
		return
	}
	var total int64
	for _, e := range entries {
		total += e.Size
		fmt.Printf("%10s  %s  %s\n", formatBytes(e.Size), formatServerTime(e.ModTime), e.Path)
	}
	fmt.Printf("共 %d 个文件, %s\n", len(entries), formatBytes(total))
}

func cmdPut(args []string) {
	fsSet := flag.NewFlagSet("put", flag.ExitOnError)
	server := fsSet.String("server", defaultServer, "服务端地址")
	token := fsSet.String("token", os.Getenv("FILESYNC_TOKEN"), "访问令牌")
	subdir := fsSet.String("subdir", "", "云端子目录（同步组前缀）")
	overwrite := fsSet.Bool("overwrite", false, "覆盖远端同名文件")
	fsSet.Parse(args)
	if fsSet.NArg() < 1 || fsSet.NArg() > 2 {
		badUsage()
	}
	local := fsSet.Arg(0)
	remote := ""
	if fsSet.NArg() == 2 {
		remote = fsSet.Arg(1)
	}
	info, err := os.Stat(local)
	if err != nil {
		fatal("%v", err)
	}
	c, err := NewClient(*server, *token, *subdir)
	if err != nil {
		fatal("%v", err)
	}

	if !info.IsDir() {
		if err := putFile(c, local, remote, *overwrite); err != nil {
			fatal("%v", err)
		}
		return
	}

	// 目录上传：远端前缀缺省取本地目录名
	var base string
	if remote == "" {
		base = filepath.ToSlash(filepath.Clean(filepath.Base(local)))
	} else {
		base = strings.TrimSuffix(filepath.ToSlash(filepath.Clean(remote)), "/")
	}
	if base == "." || base == "/" {
		base = ""
	}
	if base != "" {
		if _, err := SanitizeRelPath(base); err != nil {
			fatal("远端目录 %q 不合法: %v", remote, err)
		}
	}

	var uploaded, skipped, failed int
	walkErr := filepath.WalkDir(local, func(p string, d os.DirEntry, err error) error {
		if err != nil {
			failed++
			fmt.Fprintf(os.Stderr, "警告: 无法读取 %s: %v\n", p, err)
			return nil
		}
		if d.IsDir() {
			return nil
		}
		if !d.Type().IsRegular() {
			fmt.Fprintf(os.Stderr, "警告: 跳过非常规文件 %s\n", p)
			return nil
		}
		rel, err := filepath.Rel(local, p)
		if err != nil {
			failed++
			return nil
		}
		wire := filepath.ToSlash(rel)
		if base != "" {
			wire = base + "/" + wire
		}
		switch err := putFile(c, p, wire, *overwrite); {
		case err == nil:
			uploaded++
		case errors.Is(err, errRemoteExists):
			skipped++
			fmt.Printf("跳过（远端已存在）: %s\n", wire)
		default:
			failed++
			fmt.Fprintf(os.Stderr, "失败: %s: %v\n", wire, err)
		}
		return nil
	})
	if walkErr != nil {
		fatal("%v", walkErr)
	}
	fmt.Printf("目录上传完成: 新增 %d, 跳过 %d, 失败 %d\n", uploaded, skipped, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func cmdGet(args []string) {
	fsSet := flag.NewFlagSet("get", flag.ExitOnError)
	server := fsSet.String("server", defaultServer, "服务端地址")
	token := fsSet.String("token", os.Getenv("FILESYNC_TOKEN"), "访问令牌")
	subdir := fsSet.String("subdir", "", "云端子目录（同步组前缀）")
	overwrite := fsSet.Bool("overwrite", false, "覆盖本地同名文件")
	fsSet.Parse(args)
	if fsSet.NArg() < 1 || fsSet.NArg() > 2 {
		badUsage()
	}
	wire, err := SanitizeRelPath(filepath.ToSlash(filepath.Clean(fsSet.Arg(0))))
	if err != nil {
		fatal("远端路径不合法: %v", err)
	}
	local := fsSet.Arg(1)
	if local == "" {
		local = filepath.Base(filepath.FromSlash(wire))
	}
	c, err := NewClient(*server, *token, *subdir)
	if err != nil {
		fatal("%v", err)
	}
	n, _, err := c.Get(wire, local, *overwrite)
	if err != nil {
		fatal("%v", err)
	}
	fmt.Printf("GET %s -> %s (%s)\n", wire, local, formatBytes(n))
}

// formatServerTime 把 RFC3339 时间转为本地时区的可读形式。
func formatServerTime(ts string) string {
	t, err := time.Parse(time.RFC3339, ts)
	if err != nil {
		return ts
	}
	return t.Local().Format("2006-01-02 15:04:05")
}

func formatBytes(n int64) string {
	if n < 1024 {
		return fmt.Sprintf("%d B", n)
	}
	const units = "KMGTPE"
	div, exp := int64(1024), 0
	for x := n / 1024; x >= 1024 && exp < len(units)-1; x /= 1024 {
		div *= 1024
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), units[exp])
}
