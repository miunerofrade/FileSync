package main

import (
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// stateFileName 保存同步配置与基线，位于每个同步目录的根下（不参与同步）。
const stateFileName = ".filesync.json"

const stateTmpPrefix = stateFileName + ".tmp-"

// StateEntry 记录某文件上次同步成功时两端共同内容的基线。
// 三方比较以 Sha256 为准；Size/SyncedAt 仅用于展示。
type StateEntry struct {
	Sha256   string `json:"sha256"`
	Size     int64  `json:"size"`
	SyncedAt string `json:"synced_at,omitempty"` // RFC3339
}

// SyncState 是客户端本地同步状态。
type SyncState struct {
	Version int                    `json:"version"`
	Server  string                 `json:"server"`
	Token   string                 `json:"token,omitempty"`
	Files   map[string]*StateEntry `json:"files"`
}

func loadState(dir string) (*SyncState, error) {
	st := &SyncState{Version: 1, Files: map[string]*StateEntry{}}
	data, err := os.ReadFile(filepath.Join(dir, stateFileName))
	if err != nil {
		if os.IsNotExist(err) {
			return st, nil
		}
		return nil, err
	}
	if err := json.Unmarshal(data, st); err != nil {
		return nil, fmt.Errorf("状态文件损坏: %v（删除 %s 后重新 init 可重置）", err, stateFileName)
	}
	if st.Files == nil {
		st.Files = map[string]*StateEntry{}
	}
	return st, nil
}

// saveState 原子写入状态文件（临时文件 + fsync + 改名）。
func saveState(dir string, st *SyncState) error {
	data, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	tmp, err := os.CreateTemp(dir, stateTmpPrefix+"*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	done := false
	defer func() {
		if !done {
			tmp.Close()
			os.Remove(name)
		}
	}()
	if _, err := tmp.Write(data); err != nil {
		return err
	}
	if err := tmp.Sync(); err != nil {
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	_ = os.Chmod(name, 0o600)
	if err := os.Rename(name, filepath.Join(dir, stateFileName)); err != nil {
		return err
	}
	done = true
	return nil
}

// findSyncRoot 从 start 逐级向上查找包含 .filesync.json 的同步根目录。
func findSyncRoot(start string) (string, error) {
	abs, err := filepath.Abs(start)
	if err != nil {
		return "", err
	}
	for {
		if fi, err := os.Stat(filepath.Join(abs, stateFileName)); err == nil && !fi.IsDir() {
			return abs, nil
		}
		parent := filepath.Dir(abs)
		if parent == abs {
			return "", fmt.Errorf("未找到 %s（请先在同步目录运行 filesync init）", stateFileName)
		}
		abs = parent
	}
}

// cleanupSyncTemps 清理进程被强杀时遗留的临时文件（状态临时文件与下载临时文件）。
func cleanupSyncTemps(root string) {
	filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		n := d.Name()
		if strings.HasPrefix(n, stateTmpPrefix) || strings.HasPrefix(n, ".fsync-get-") {
			os.Remove(p)
		}
		return nil
	})
}
