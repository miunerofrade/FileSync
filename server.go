package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// tempPrefix 是服务端未完成上传的临时文件前缀；列表会跳过它们。
const tempPrefix = ".fsync-"

// FileEntry 是文件列表中的一条记录，路径始终以 '/' 分隔。
type FileEntry struct {
	Path    string `json:"path"`
	Size    int64  `json:"size"`
	Sha256  string `json:"sha256"`
	ModTime string `json:"mod_time"` // RFC3339 (UTC)
}

// Server 把所有文件访问限制在数据根目录内。
type Server struct {
	root  string // 绝对路径
	token string // 非空时要求 Bearer 认证
}

func NewServer(dataDir, token string) (*Server, error) {
	root, err := filepath.Abs(dataDir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(root, 0o755); err != nil {
		return nil, err
	}
	return &Server{root: root, token: strings.TrimSpace(token)}, nil
}

// fullPath 校验相对路径并转换为根目录内的绝对路径。
// 返回 wire（规范化后的 '/' 分隔路径）与 full（磁盘绝对路径）。
func (s *Server) fullPath(rel string) (wire, full string, err error) {
	wire, err = SanitizeRelPath(rel)
	if err != nil {
		return "", "", err
	}
	full = filepath.Join(s.root, filepath.FromSlash(wire))
	if !withinRoot(s.root, full) {
		return "", "", fmt.Errorf("%w: 越出数据目录", errBadPath)
	}
	return wire, full, nil
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/api/ping" && r.Method == http.MethodGet:
			s.handlePing(w, r)
		case r.URL.Path == "/api/ls" && r.Method == http.MethodGet:
			s.handleLs(w, r)
		case r.URL.Path == "/api/get" && r.Method == http.MethodGet:
			s.handleGet(w, r)
		case r.URL.Path == "/api/put" && r.Method == http.MethodPut:
			s.handlePut(w, r)
		case r.URL.Path == "/api/delete" && r.Method == http.MethodDelete:
			s.handleDelete(w, r)
		case r.URL.Path == "/api/update-check" && r.Method == http.MethodGet:
			s.handleUpdateCheck(w, r)
		default:
			writeError(w, http.StatusNotFound, "未知接口")
		}
	})
	// Token 认证：设置后所有 /api/* 请求必须携带 Authorization: Bearer <token>
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.token != "" {
			got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if subtle.ConstantTimeCompare([]byte(got), []byte(s.token)) != 1 {
				writeError(w, http.StatusUnauthorized, "认证失败")
				return
			}
		}
		mux.ServeHTTP(w, r)
	})
}

func (s *Server) handlePing(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// handleLs 递归列出数据目录下的全部普通文件，可选按 prefix 过滤。
func (s *Server) handleLs(w http.ResponseWriter, r *http.Request) {
	prefix := ""
	if raw := r.URL.Query().Get("prefix"); raw != "" {
		p, err := SanitizeRelPath(raw)
		if err != nil {
			writeError(w, http.StatusBadRequest, "prefix 不合法: "+err.Error())
			return
		}
		prefix = p
	}
	entries := []FileEntry{}
	err := filepath.WalkDir(s.root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err // 根目录不可读等严重错误
		}
		if d.IsDir() {
			return nil
		}
		if strings.HasPrefix(d.Name(), tempPrefix) {
			return nil // 跳过未完成上传的临时文件
		}
		rel, err := filepath.Rel(s.root, p)
		if err != nil {
			return nil
		}
		wire := filepath.ToSlash(rel)
		if prefix != "" && wire != prefix && !strings.HasPrefix(wire, prefix+"/") {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		sum, err := fileSHA256(p)
		if err != nil {
			return nil // 文件在遍历过程中消失，跳过
		}
		entries = append(entries, FileEntry{
			Path:    wire,
			Size:    info.Size(),
			Sha256:  sum,
			ModTime: info.ModTime().UTC().Format(time.RFC3339),
		})
		return nil
	})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "遍历数据目录失败: "+err.Error())
		return
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Path < entries[j].Path })
	writeJSON(w, http.StatusOK, entries)
}

// handleGet 下载文件：先整体计算 SHA-256，再携带校验头发送。
func (s *Server) handleGet(w http.ResponseWriter, r *http.Request) {
	wire, full, err := s.fullPath(r.URL.Query().Get("path"))
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	f, err := os.Open(full)
	if err != nil {
		if os.IsNotExist(err) {
			writeError(w, http.StatusNotFound, "文件不存在")
		} else {
			writeError(w, http.StatusInternalServerError, err.Error())
		}
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if info.IsDir() {
		writeError(w, http.StatusBadRequest, "目标是目录")
		return
	}
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		writeError(w, http.StatusInternalServerError, "计算校验和失败: "+err.Error())
		return
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(info.Size(), 10))
	w.Header().Set("X-Checksum-SHA256", hex.EncodeToString(h.Sum(nil)))
	w.WriteHeader(http.StatusOK)
	n, err := io.Copy(w, f)
	if err != nil {
		log.Printf("GET %s: 传输中断（已发送 %d 字节）: %v", wire, n, err)
		return
	}
	log.Printf("GET %s (%d bytes) -> %s", wire, n, r.RemoteAddr)
}

// handlePut 上传文件：写临时文件 → fsync → 校验 SHA-256 → 原子改名。
// 任何一步失败都只会留下待清理的临时文件，已有文件不受影响。
func (s *Server) handlePut(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	wire, full, err := s.fullPath(q.Get("path"))
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	overwrite := q.Get("overwrite") == "1" || q.Get("overwrite") == "true"
	// if-match：条件覆盖（CAS）。仅当服务器当前内容与给定 SHA-256 一致时才允许覆盖，
	// 同步流程用它防止并发修改被静默覆盖。
	ifMatch := strings.ToLower(q.Get("if-match"))
	if ifMatch != "" && (len(ifMatch) != 64 || !isHex(ifMatch)) {
		writeError(w, http.StatusBadRequest, "非法的 if-match 参数")
		return
	}
	wantSum := strings.ToLower(r.Header.Get("X-Checksum-SHA256"))
	if len(wantSum) != 64 || !isHex(wantSum) {
		writeError(w, http.StatusBadRequest, "缺少或非法的 X-Checksum-SHA256 请求头")
		return
	}

	if info, err := os.Lstat(full); err == nil {
		if info.IsDir() {
			writeError(w, http.StatusBadRequest, "目标路径已是目录")
			return
		}
		if !overwrite {
			writeError(w, http.StatusConflict, "文件已存在（可加 overwrite=1 覆盖）")
			return
		}
		if ifMatch != "" {
			cur, herr := fileSHA256(full)
			if herr != nil {
				writeError(w, http.StatusInternalServerError, herr.Error())
				return
			}
			if cur != ifMatch {
				writeError(w, http.StatusPreconditionFailed, "服务器内容已变化（if-match 不匹配），拒绝覆盖")
				return
			}
		}
	} else if !os.IsNotExist(err) {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	} else if ifMatch != "" {
		writeError(w, http.StatusPreconditionFailed, "服务器文件已不存在（if-match 不匹配）")
		return
	}

	dir := filepath.Dir(full)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		writeError(w, http.StatusInternalServerError, "创建目录失败: "+err.Error())
		return
	}
	tmp, err := os.CreateTemp(dir, tempPrefix+"*")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "创建临时文件失败: "+err.Error())
		return
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

	size, err := io.Copy(tmp, r.Body)
	if err != nil {
		// 客户端中断：丢弃临时文件，已有文件不受影响
		writeError(w, http.StatusBadRequest, "接收数据中断: "+err.Error())
		return
	}
	if err := tmp.Sync(); err != nil {
		writeError(w, http.StatusInternalServerError, "刷盘失败: "+err.Error())
		return
	}
	if err := tmp.Close(); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	gotSum, err := fileSHA256(tmpName)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if gotSum != wantSum {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("SHA-256 不匹配（got %s want %s）", gotSum, wantSum))
		return
	}

	// 写完后再确认一次“不覆盖”，尽量收窄并发窗口
	if !overwrite {
		if _, err := os.Lstat(full); err == nil {
			writeError(w, http.StatusConflict, "文件已存在（可加 overwrite=1 覆盖）")
			return
		} else if !os.IsNotExist(err) {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
	}
	// 带条件覆盖时，写入期间服务器文件可能又被并发修改，改名前做最终检查
	if ifMatch != "" {
		cur, herr := fileSHA256(full)
		if herr != nil {
			if os.IsNotExist(herr) {
				writeError(w, http.StatusPreconditionFailed, "服务器文件已消失（if-match 不匹配）")
			} else {
				writeError(w, http.StatusInternalServerError, herr.Error())
			}
			return
		}
		if cur != ifMatch {
			writeError(w, http.StatusPreconditionFailed, "服务器内容已变化（if-match 不匹配），拒绝覆盖")
			return
		}
	}
	if err := os.Rename(tmpName, full); err != nil {
		if os.IsExist(err) {
			writeError(w, http.StatusConflict, "文件已存在")
		} else {
			writeError(w, http.StatusInternalServerError, "改名失败: "+err.Error())
		}
		return
	}
	done = true
	log.Printf("PUT %s (%d bytes) <- %s", wire, size, r.RemoteAddr)
	writeJSON(w, http.StatusCreated, map[string]any{
		"path":   wire,
		"size":   size,
		"sha256": gotSum,
	})
}

// handleDelete 删除云端文件（仅影响服务端数据目录，不涉及任何客户端本地文件）。
// recursive=1 时删除整个远端子树；空路径一律拒绝（防误删数据根目录）。
func (s *Server) handleDelete(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	wire, full, err := s.fullPath(q.Get("path"))
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if wire == "" {
		writeError(w, http.StatusBadRequest, "拒绝删除数据根目录")
		return
	}
	info, err := os.Lstat(full)
	if err != nil {
		if os.IsNotExist(err) {
			writeError(w, http.StatusNotFound, "文件不存在")
		} else {
			writeError(w, http.StatusInternalServerError, err.Error())
		}
		return
	}
	if info.IsDir() && q.Get("recursive") != "1" {
		writeError(w, http.StatusBadRequest, "目标是目录（recursive=1 可删除整个子树）")
		return
	}
	if info.IsDir() {
		err = os.RemoveAll(full)
	} else {
		err = os.Remove(full)
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "删除失败: "+err.Error())
		return
	}
	// 清理因删除而变空的父目录（full 在根内，向上必先经过根，到根即停）：
	// 上传时 MkdirAll 建出的目录骨架不应在文件删光后残留
	for dir := filepath.Dir(full); dir != s.root; dir = filepath.Dir(dir) {
		if err := os.Remove(dir); err != nil {
			break // 非空或不可删即停
		}
	}
	log.Printf("DELETE %s <- %s", wire, r.RemoteAddr)
	writeJSON(w, http.StatusOK, map[string]any{"path": wire, "deleted": true})
}

// updateCache 缓存 GitHub 最新 Release（15 分钟）：手机网络的共享出口 IP 极易触发
// GitHub 未认证限流（403），由服务器独立 IP 代拉并转发给客户端。
var updateCache = struct {
	sync.Mutex
	at   time.Time
	body []byte
}{}

// handleUpdateCheck 转发 GitHub 最新 Release 信息（带缓存）。
// refresh=1 强制绕过缓存拉取上游：App 手动"检查更新"时携带，避免发布后缓存窗口内
// 客户端拿到旧 tag 而误报"已是最新"。
func (s *Server) handleUpdateCheck(w http.ResponseWriter, r *http.Request) {
	force := r.URL.Query().Get("refresh") == "1"
	updateCache.Lock()
	defer updateCache.Unlock()
	if force || updateCache.body == nil || time.Since(updateCache.at) > 15*time.Minute {
		req, err := http.NewRequest(http.MethodGet,
			"https://api.github.com/repos/miunerofrade/FileSync/releases/latest", nil)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		req.Header.Set("Accept", "application/vnd.github+json")
		req.Header.Set("User-Agent", "FileSync-Server")
		client := &http.Client{Timeout: 15 * time.Second}
		resp, err := client.Do(req)
		if err != nil {
			writeError(w, http.StatusBadGateway, "上游请求失败: "+err.Error())
			return
		}
		defer resp.Body.Close()
		body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		if err != nil {
			writeError(w, http.StatusBadGateway, "读取上游失败: "+err.Error())
			return
		}
		if resp.StatusCode != http.StatusOK {
			writeError(w, http.StatusBadGateway, "上游状态 "+resp.Status)
			return
		}
		updateCache.body = body
		updateCache.at = time.Now()
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(updateCache.body)
}

func writeError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func isHex(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		switch {
		case c >= '0' && c <= '9', c >= 'a' && c <= 'f':
		default:
			return false
		}
	}
	return true
}

func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func cmdServe(args []string) {
	fsSet := flag.NewFlagSet("serve", flag.ExitOnError)
	addr := fsSet.String("addr", "127.0.0.1:8080", "监听地址，如 127.0.0.1:8080 / :8443")
	dataDir := fsSet.String("data-dir", "data", "文件存储根目录（缺省为运行目录下的 ./data）")
	token := fsSet.String("token", os.Getenv("FILESYNC_TOKEN"), "访问令牌（非空时启用认证；缺省读 FILESYNC_TOKEN）")
	certFile := fsSet.String("tls-cert", "", "TLS 证书路径（PEM，与 --tls-key 同时提供时启用 HTTPS）")
	keyFile := fsSet.String("tls-key", "", "TLS 私钥路径")
	publicURL := fsSet.String("public-url", "", "对外可达地址（用于生成客户端配置文件）")
	clientConfig := fsSet.String("client-config", "", "启动时写出客户端配置文件路径（需 --public-url 与 --token）")
	fsSet.Parse(args)
	if fsSet.NArg() != 0 {
		badUsage()
	}
	if (*certFile == "") != (*keyFile == "") {
		fatal("--tls-cert 与 --tls-key 必须同时提供")
	}

	srv, err := NewServer(*dataDir, *token)
	if err != nil {
		fatal("初始化数据目录失败: %v", err)
	}
	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		fatal("监听 %s 失败: %v", *addr, err)
	}
	scheme := "http"
	if *certFile != "" {
		scheme = "https"
	}
	if *clientConfig != "" {
		if *publicURL == "" || *token == "" {
			fatal("--client-config 需要 --public-url 与 --token 同时提供")
		}
		if err := os.WriteFile(*clientConfig,
			[]byte(fmt.Sprintf("{\"server\": \"%s\", \"token\": \"%s\"}\n", strings.TrimSuffix(*publicURL, "/"), strings.TrimSpace(*token))),
			0o600,
		); err != nil {
			fatal("写出客户端配置文件失败: %v", err)
		}
		fmt.Printf("客户端配置文件已生成: %s\n", *clientConfig)
	}
	fmt.Printf("FileSync 服务端\n  监听:     %s (%s)\n  数据目录: %s\n  认证:     %s\n  (Ctrl+C 退出)\n",
		ln.Addr(), scheme, srv.root, map[bool]string{true: "已启用", false: "未启用"}[*token != ""])

	httpSrv := &http.Server{
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	go func() {
		<-ctx.Done()
		fmt.Println("\n正在退出…")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = httpSrv.Shutdown(shutdownCtx)
	}()

	serveErr := error(nil)
	if *certFile != "" {
		serveErr = httpSrv.ServeTLS(ln, *certFile, *keyFile)
	} else {
		serveErr = httpSrv.Serve(ln)
	}
	if serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
		fatal("服务端错误: %v", serveErr)
	}
}
