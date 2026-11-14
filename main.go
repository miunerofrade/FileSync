// FileSync —— 轻量级、中心化、手动触发的跨平台文件同步工具。
// 第一阶段：HTTP 文件服务端 + CLI（serve/ls/put/get），仅依赖 Go 标准库。
package main

import (
	"fmt"
	"os"
)

const usage = `FileSync —— 轻量级、中心化、手动触发的文件同步工具

用法:
  filesync serve [--addr 127.0.0.1:8080] [--data-dir data]
      启动文件服务端。数据目录缺省为运行目录下的 ./data，不存在时自动创建。

  filesync init [--server http://127.0.0.1:8080] [--subdir 分组] [--config 配置文件] [目录]
      初始化同步目录（缺省当前目录），写入 .filesync.json 保存服务端地址与同步基线。
      已初始化的目录加 --force 可修改分组/服务器（基线清空，本地文件不动）。
      --config 读取 JSON 配置（server/token/subdir，App 可导出）：字段有则覆盖、无则保留对应 -- 参数。

  filesync sync [--keep local|remote] [相对路径]
      双向同步整个目录；给定相对路径（相对同步根目录）时只同步该文件或子目录。
      自动检测两端变化，仅传输有改动的文件；两端分别修改同一文件时询问保留
      哪个版本，--keep local / --keep remote 可自动决策，stdin 关闭时冲突跳过。

  filesync ls [--server URL] [--prefix docs]
      列出服务端文件；--prefix 仅列出某个远端子目录。

  filesync put [--server URL] [--overwrite] 本地路径 [远端路径]
      手工上传文件或整个目录（独立于同步状态，不走基线比较）。

  filesync get [--server URL] [--overwrite] 远端路径 [本地路径]
      手工下载单个文件。

  filesync rm [-r] [--server URL] [--subdir 子目录] 远端路径...
      删除云端文件（-r 递归删除整个远端子目录）。只删云端，绝不触碰本地文件；
      下次 sync 时本地文件会提示"服务器已不存在，本地保留"。

说明:
  · 远端路径以 '/' 分隔；绝对路径、'..'、反斜杠等一律拒绝，文件被限制在数据目录内。
  · 所有传输先写临时文件并做 SHA-256 校验，成功后原子改名；中断不会损坏已有文件。
  · 同步不传播删除：一端删除的文件只在另一端保留并提示，不做自动删除。
`

func main() {
	if len(os.Args) < 2 {
		badUsage()
	}
	switch os.Args[1] {
	case "serve":
		cmdServe(os.Args[2:])
	case "ls":
		cmdLs(os.Args[2:])
	case "put":
		cmdPut(os.Args[2:])
	case "get":
		cmdGet(os.Args[2:])
	case "rm":
		cmdRm(os.Args[2:])
	case "init":
		cmdInit(os.Args[2:])
	case "sync":
		cmdSync(os.Args[2:])
	case "help", "-h", "--help":
		fmt.Print(usage)
	default:
		fmt.Fprintf(os.Stderr, "未知命令 %q\n\n", os.Args[1])
		badUsage()
	}
}

func badUsage() {
	fmt.Fprint(os.Stderr, usage)
	os.Exit(2)
}

func fatal(format string, a ...any) {
	fmt.Fprintf(os.Stderr, "错误: "+format+"\n", a...)
	os.Exit(1)
}
