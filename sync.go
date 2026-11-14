package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// ---------- filesync init ----------

// readClientConfig 读取客户端配置文件（服务端 --client-config 生成）。
func readClientConfig(path string) (server, token string, err error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", "", err
	}
	var cfg struct {
		Server string `json:"server"`
		Token  string `json:"token"`
	}
	if err := json.Unmarshal(data, &cfg); err != nil {
		return "", "", fmt.Errorf("JSON 解析失败: %v", err)
	}
	if cfg.Server == "" {
		return "", "", errors.New("缺少 server 字段")
	}
	return strings.TrimSpace(cfg.Server), strings.TrimSpace(cfg.Token), nil
}

func cmdInit(args []string) {
	fsSet := flag.NewFlagSet("init", flag.ExitOnError)
	server := fsSet.String("server", defaultServer, "服务端地址")
	token := fsSet.String("token", os.Getenv("FILESYNC_TOKEN"), "访问令牌")
	configFile := fsSet.String("config", "", "从配置文件导入（JSON：server/token，覆盖 --server/--token）")
	fsSet.Parse(args)
	if fsSet.NArg() > 1 {
		badUsage()
	}
	dir := "."
	if fsSet.NArg() == 1 {
		dir = fsSet.Arg(0)
	}
	if *configFile != "" {
		s, t, err := readClientConfig(*configFile)
		if err != nil {
			fatal("读取配置文件失败: %v", err)
		}
		*server = s
		*token = t
	}
	absDir, err := filepath.Abs(dir)
	if err != nil {
		fatal("%v", err)
	}
	if err := os.MkdirAll(absDir, 0o755); err != nil {
		fatal("创建目录失败: %v", err)
	}
	if _, err := os.Stat(filepath.Join(absDir, stateFileName)); err == nil {
		fatal("目录已初始化: %s（删除 %s 可重新初始化）", absDir, stateFileName)
	}
	c, err := NewClient(*server, *token)
	if err != nil {
		fatal("%v", err)
	}
	if _, err := c.Ls(""); err != nil {
		fatal("服务端不可用: %v", err)
	}
	st := &SyncState{Version: 1, Server: c.base, Token: strings.TrimSpace(*token), Files: map[string]*StateEntry{}}
	if err := saveState(absDir, st); err != nil {
		fatal("保存状态失败: %v", err)
	}
	fmt.Printf("已初始化\n  同步目录: %s\n  服务端:   %s\n运行 filesync sync 开始首次同步。\n", absDir, c.base)
}

// ---------- filesync sync ----------

type syncCounters struct {
	uploaded, downloaded, unchanged, resolved, conflictSkipped, warnings, cleaned, failed int
}

// concurrentChangeError 表示上传时服务器内容已与预期不符（并发修改）。
type concurrentChangeError struct{ status int }

func (e *concurrentChangeError) Error() string {
	return fmt.Sprintf("服务器内容已并发变化（HTTP %d）", e.status)
}

type localFile struct {
	abs  string
	size int64
	hash string // 惰性计算
}

type syncer struct {
	root        string
	st          *SyncState
	c           *Client
	local       map[string]*localFile
	server      map[string]FileEntry
	keep        string
	in          *bufio.Scanner
	stdinClosed bool
	ops         int // 距上次状态落盘的状态变更数
	n           syncCounters
}

func cmdSync(args []string) {
	fsSet := flag.NewFlagSet("sync", flag.ExitOnError)
	keep := fsSet.String("keep", "", "冲突时自动保留的版本: local / remote")
	token := fsSet.String("token", os.Getenv("FILESYNC_TOKEN"), "访问令牌（缺省用 init 时保存的）")
	fsSet.Parse(args)
	if fsSet.NArg() > 1 {
		badUsage()
	}
	switch *keep {
	case "", "local", "remote":
	default:
		fatal("--keep 仅支持 local 或 remote")
	}

	root, err := findSyncRoot(".")
	if err != nil {
		fatal("%v", err)
	}
	st, err := loadState(root)
	if err != nil {
		fatal("%v", err)
	}
	tk := strings.TrimSpace(*token)
	if tk == "" {
		tk = st.Token
	}
	c, err := NewClient(st.Server, tk)
	if err != nil {
		fatal("%v", err)
	}
	cleanupSyncTemps(root)

	filter := ""
	if fsSet.NArg() == 1 {
		filter, err = SanitizeRelPath(filepath.ToSlash(filepath.Clean(fsSet.Arg(0))))
		if err != nil {
			fatal("路径不合法: %v", err)
		}
	}

	localFiles, err := walkLocal(root)
	if err != nil {
		fatal("%v", err)
	}
	entries, err := c.Ls("")
	if err != nil {
		fatal("获取服务端列表失败: %v", err)
	}
	serverFiles := map[string]FileEntry{}
	for _, e := range entries {
		serverFiles[e.Path] = e
	}

	// 待决策路径 = 本地 ∪ 服务器 ∪ 基线记录
	union := map[string]bool{}
	for p := range localFiles {
		union[p] = true
	}
	for p := range serverFiles {
		union[p] = true
	}
	for p := range st.Files {
		union[p] = true
	}
	paths := make([]string, 0, len(union))
	for p := range union {
		if filter == "" || p == filter || strings.HasPrefix(p, filter+"/") {
			paths = append(paths, p)
		}
	}
	sort.Strings(paths)

	fmt.Printf("同步目录: %s\n服务端:   %s\n", root, st.Server)
	if filter != "" {
		fmt.Printf("范围:     %s\n", filter)
	}
	if len(paths) == 0 {
		if filter != "" {
			fmt.Printf("没有匹配的文件: %s\n", filter)
		}
		return
	}

	s := &syncer{
		root:   root,
		st:     st,
		c:      c,
		local:  localFiles,
		server: serverFiles,
		keep:   *keep,
		in:     bufio.NewScanner(os.Stdin),
	}
	for _, rel := range paths {
		if err := s.syncOne(rel); err != nil {
			fmt.Fprintf(os.Stderr, "失败: %s: %v\n", rel, err)
			s.n.failed++
		}
	}
	if err := s.flush(); err != nil {
		fatal("保存同步状态失败: %v", err)
	}
	n := s.n
	fmt.Printf("同步完成: 上传 %d, 下载 %d, 无变化 %d, 冲突解决 %d, 冲突跳过 %d, 需注意 %d, 清理 %d\n",
		n.uploaded, n.downloaded, n.unchanged, n.resolved, n.conflictSkipped, n.warnings, n.cleaned)
	if n.conflictSkipped > 0 || n.failed > 0 {
		fmt.Fprintln(os.Stderr, "存在未解决项，处理后可重新运行 filesync sync。")
		os.Exit(1)
	}
}

// syncOne 对单个路径做三方比较（本地 / 服务器 / 基线）并执行相应动作。
func (s *syncer) syncOne(rel string) error {
	if rel == stateFileName {
		return nil // 状态文件只属于本客户端，绝不同步
	}
	lf := s.local[rel]
	se, hasServer := s.server[rel]
	base, hasBase := s.st.Files[rel]

	switch {
	case lf != nil && hasServer:
		lHash, err := s.hashLocal(lf)
		if err != nil {
			return err
		}
		if !hasBase {
			// 首次遇到：两端一致则直接采纳；否则按冲突处理
			if lHash == se.Sha256 {
				s.record(rel, lHash, lf.size)
				s.n.unchanged++
				fmt.Printf("已一致 %s (%s)\n", rel, formatBytes(lf.size))
				return nil
			}
			return s.resolveConflict(rel, lf, lHash, &se, nil)
		}
		localChanged := lHash != base.Sha256
		serverChanged := se.Sha256 != base.Sha256
		switch {
		case !localChanged && !serverChanged:
			s.n.unchanged++
			return nil
		case localChanged && !serverChanged:
			return s.autoUpload(rel, lf, lHash, base.Sha256)
		case serverChanged && !localChanged:
			return s.autoDownload(rel, &se)
		default: // 两端都变
			if lHash == se.Sha256 {
				s.record(rel, lHash, lf.size)
				s.n.unchanged++
				fmt.Printf("已一致 %s (%s)\n", rel, formatBytes(lf.size))
				return nil
			}
			return s.resolveConflict(rel, lf, lHash, &se, base)
		}

	case lf != nil: // 服务器没有该文件
		if !hasBase {
			lHash, err := s.hashLocal(lf)
			if err != nil {
				return err
			}
			return s.autoUpload(rel, lf, lHash, "")
		}
		fmt.Printf("注意: 服务器已不存在，本地保留（不传播删除）: %s\n", rel)
		s.n.warnings++
		return nil

	case hasServer: // 本地没有该文件
		if !hasBase {
			return s.autoDownload(rel, &se)
		}
		fmt.Printf("注意: 本地已删除，服务器保留（不传播删除）: %s\n", rel)
		s.n.warnings++
		return nil

	default: // 两端都没有：仅剩基线记录，清理之
		if hasBase {
			delete(s.st.Files, rel)
			s.ops++
			if s.ops%32 == 0 {
				s.flushQuiet()
			}
			s.n.cleaned++
			fmt.Printf("清理记录 %s（两端均已不存在）\n", rel)
		}
		return nil
	}
}

func (s *syncer) hashLocal(lf *localFile) (string, error) {
	if lf.hash == "" {
		h, err := fileSHA256(lf.abs)
		if err != nil {
			return "", err
		}
		lf.hash = h
	}
	return lf.hash, nil
}

// record 更新基线并按批落盘，保证中断后最多重复检测、绝不损坏数据。
func (s *syncer) record(rel, sha string, size int64) {
	s.st.Files[rel] = &StateEntry{
		Sha256:   sha,
		Size:     size,
		SyncedAt: time.Now().UTC().Format(time.RFC3339),
	}
	s.ops++
	if s.ops%32 == 0 {
		s.flushQuiet()
	}
}

func (s *syncer) flushQuiet() {
	if err := s.flush(); err != nil {
		fmt.Fprintf(os.Stderr, "警告: 保存同步状态失败: %v\n", err)
	}
}

func (s *syncer) flush() error {
	if s.ops == 0 {
		return nil
	}
	if err := saveState(s.root, s.st); err != nil {
		return err
	}
	s.ops = 0
	return nil
}

// upload 上传本地文件。overwrite+ifMatch 组合实现条件覆盖（CAS）。
func (s *syncer) upload(rel string, lf *localFile, overwrite bool, ifMatch string) error {
	sum, err := s.hashLocal(lf)
	if err != nil {
		return err
	}
	f, err := os.Open(lf.abs)
	if err != nil {
		return err
	}
	defer f.Close()
	status, err := s.c.Put(rel, lf.size, sum, f, overwrite, ifMatch)
	if err != nil {
		if status == http.StatusConflict || status == http.StatusPreconditionFailed {
			return &concurrentChangeError{status: status}
		}
		return err
	}
	return nil
}

// autoUpload 自动上传：正常覆盖走 if-match=基线；若服务器内容在两次同步之间
// 被其他端修改（409/412），视为新冲突，本轮跳过并报告，绝不静默覆盖。
func (s *syncer) autoUpload(rel string, lf *localFile, lHash string, ifMatch string) error {
	err := s.upload(rel, lf, ifMatch != "", ifMatch)
	if err != nil {
		var cc *concurrentChangeError
		if errors.As(err, &cc) {
			fmt.Fprintf(os.Stderr, "冲突(上传时服务器内容已变化，本轮跳过): %s\n", rel)
			s.n.conflictSkipped++
			return nil
		}
		return err
	}
	s.record(rel, lHash, lf.size)
	s.n.uploaded++
	fmt.Printf("上传 %s (%s)\n", rel, formatBytes(lf.size))
	return nil
}

func (s *syncer) autoDownload(rel string, se *FileEntry) error {
	if _, err := s.downloadTo(rel, se); err != nil {
		return err
	}
	s.n.downloaded++
	fmt.Printf("下载 %s (%s)\n", rel, formatBytes(se.Size))
	return nil
}

// downloadTo 下载并覆盖本地文件（临时文件 + 校验 + 原子改名），
// 基线记录实际下载内容的哈希。
func (s *syncer) downloadTo(rel string, se *FileEntry) (string, error) {
	localPath := filepath.Join(s.root, filepath.FromSlash(rel))
	_, sum, err := s.c.Get(rel, localPath, true)
	if err != nil {
		return "", err
	}
	s.record(rel, sum, se.Size)
	return sum, nil
}

// resolveConflict 处理冲突：--keep 指定时自动决策，否则交互询问，
// stdin 关闭（脚本/管道）时跳过并报告。
func (s *syncer) resolveConflict(rel string, lf *localFile, lHash string, se *FileEntry, base *StateEntry) error {
	_ = s.flush() // 询问前先把已有进度落盘
	choice := s.keep
	if choice == "" {
		var err error
		choice, err = s.promptConflict(rel, lf, lHash, se)
		if err != nil {
			return err
		}
	}
	switch choice {
	case "local":
		// 用户明确选择本地版本：无条件覆盖远端（不携带 if-match）
		if err := s.upload(rel, lf, true, ""); err != nil {
			return err
		}
		s.record(rel, lHash, lf.size)
		s.n.resolved++
		fmt.Printf("冲突解决(保留本地) %s\n", rel)
	case "remote":
		if _, err := s.downloadTo(rel, se); err != nil {
			return err
		}
		s.n.resolved++
		fmt.Printf("冲突解决(保留远端) %s\n", rel)
	default:
		fmt.Fprintf(os.Stderr, "冲突未解决(已跳过): %s\n", rel)
		s.n.conflictSkipped++
	}
	return nil
}

func (s *syncer) promptConflict(rel string, lf *localFile, lHash string, se *FileEntry) (string, error) {
	for {
		if s.stdinClosed {
			return "skip", nil
		}
		fmt.Printf("冲突: %s\n", rel)
		fmt.Printf("  本地: %s, 修改于 %s, sha256 %s\n", formatBytes(lf.size), localModTime(lf.abs), shortHash(lHash))
		fmt.Printf("  远端: %s, 修改于 %s, sha256 %s\n", formatBytes(se.Size), formatServerTime(se.ModTime), shortHash(se.Sha256))
		fmt.Print("保留哪个版本? [L]本地 / [R]远端 / [S]跳过: ")
		if !s.in.Scan() {
			s.stdinClosed = true
			fmt.Println()
			return "skip", nil
		}
		switch strings.ToLower(strings.TrimSpace(s.in.Text())) {
		case "l", "local", "本地":
			return "local", nil
		case "r", "remote", "远端":
			return "remote", nil
		case "s", "skip", "跳过", "":
			return "skip", nil
		default:
			fmt.Println("  无效输入，请输入 L、R 或 S")
		}
	}
}

// walkLocal 枚举本地同步目录下的普通文件（不含哈希，按需惰性计算），
// 跳过状态文件及其临时文件、下载临时文件。
func walkLocal(root string) (map[string]*localFile, error) {
	files := map[string]*localFile{}
	err := filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		if !d.Type().IsRegular() {
			return nil
		}
		name := d.Name()
		if strings.HasPrefix(name, stateTmpPrefix) || strings.HasPrefix(name, ".fsync-get-") {
			return nil
		}
		rel, err := filepath.Rel(root, p)
		if err != nil {
			return nil
		}
		wire := filepath.ToSlash(rel)
		if wire == stateFileName {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		files[wire] = &localFile{abs: p, size: info.Size()}
		return nil
	})
	return files, err
}

func localModTime(path string) string {
	if fi, err := os.Stat(path); err == nil {
		return fi.ModTime().Local().Format("2006-01-02 15:04:05")
	}
	return "?"
}

func shortHash(h string) string {
	if len(h) > 12 {
		return h[:12]
	}
	return h
}
