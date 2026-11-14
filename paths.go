package main

import (
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"unicode/utf8"
)

var errBadPath = errors.New("非法路径")

// windowsReservedNames 是 Windows 保留设备名；比较时取第一个 '.' 之前的部分
// 并忽略大小写（如 CON.txt、com9.log 同样被拒绝）。
var windowsReservedNames = map[string]bool{
	"con": true, "prn": true, "aux": true, "nul": true,
	"com1": true, "com2": true, "com3": true, "com4": true, "com5": true,
	"com6": true, "com7": true, "com8": true, "com9": true,
	"lpt1": true, "lpt2": true, "lpt3": true, "lpt4": true, "lpt5": true,
	"lpt6": true, "lpt7": true, "lpt8": true, "lpt9": true,
}

// SanitizeRelPath 校验并规范化客户端提供的相对路径，返回以 '/' 分隔的规范形式。
// 规则：
//   - 禁止绝对路径（前导 '/'、盘符）、反斜杠、冒号、控制字符、非法 UTF-8；
//   - 禁止 '.'、'..' 与空段，因此结果不可能越出数据根目录；
//   - 禁止 Windows 保留名、以 '.' 或空格结尾的段、超过 255 字节的段。
func SanitizeRelPath(p string) (string, error) {
	if p == "" {
		return "", fmt.Errorf("%w: 路径为空", errBadPath)
	}
	if strings.Contains(p, "\\") {
		return "", fmt.Errorf("%w: 不允许反斜杠（请使用 '/'）", errBadPath)
	}
	if strings.HasPrefix(p, "/") {
		return "", fmt.Errorf("%w: 不允许绝对路径", errBadPath)
	}
	if strings.Contains(p, ":") {
		return "", fmt.Errorf("%w: 不允许冒号", errBadPath)
	}
	if !utf8.ValidString(p) {
		return "", fmt.Errorf("%w: 编码非法", errBadPath)
	}
	for _, r := range p {
		if r < 0x20 || r == 0x7f {
			return "", fmt.Errorf("%w: 含控制字符", errBadPath)
		}
	}
	segs := strings.Split(p, "/")
	for _, seg := range segs {
		switch seg {
		case "", ".", "..":
			return "", fmt.Errorf("%w: 非法段 %q", errBadPath, seg)
		}
		if len(seg) > 255 {
			return "", fmt.Errorf("%w: 段超过 255 字节", errBadPath)
		}
		if strings.HasSuffix(seg, ".") || strings.HasSuffix(seg, " ") {
			return "", fmt.Errorf("%w: 段以 '.' 或空格结尾 %q", errBadPath, seg)
		}
		name := seg
		if i := strings.IndexByte(seg, '.'); i >= 0 {
			name = seg[:i]
		}
		if windowsReservedNames[strings.ToLower(name)] {
			return "", fmt.Errorf("%w: Windows 保留名 %q", errBadPath, seg)
		}
	}
	return strings.Join(segs, "/"), nil
}

// withinRoot 判断绝对路径 p 是否位于 root 之内（服务端的防御性二次检查）。
func withinRoot(root, p string) bool {
	rel, err := filepath.Rel(root, p)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}
