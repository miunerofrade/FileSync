// FileSync —— 轻量级、中心化、手动触发的跨平台文件同步工具。
// 第一阶段：HTTP 文件服务端 + CLI（serve/ls/put/get），仅依赖 Go 标准库。
package main

import (
	"fmt"
	"os"
)

const usage = `FileSync —— 轻量级、中心化、手动触发的文件同步工具

快速上手（两台设备 + 一台服务器）:
  服务器:  filesync serve --addr :8443 --data-dir /var/lib/filesync --token <令牌>
  设备 A:  filesync init --server https://IP:8443 --token <令牌> --subdir notes D:/notes
           filesync sync
  设备 B:  filesync init --server https://IP:8443 --token <令牌> --subdir notes ~/notes
           filesync sync
  （两台设备的 notes 目录即互相同步；不同 --subdir 分组互不可见）

命令:

  filesync serve [--addr 127.0.0.1:8080] [--data-dir data] [--token 令牌]
                 [--tls-cert 证书 --tls-key 私钥] [--client-config 路径]
      启动文件服务端。数据目录缺省 ./data，不存在时自动创建。
      --token                  启用 Bearer 认证；缺省读环境变量 FILESYNC_TOKEN。公网部署必开。
      --tls-cert --tls-key     同时提供时以 HTTPS 监听（推荐，可签发 IP 证书）。
      --public-url URL         对外可达地址（仅配合 --client-config）。
      --client-config 路径     启动时写出客户端配置 JSON，供 App 导入 / CLI --config 导入
                               （需同时提供 --public-url 与 --token）。

  filesync init [--server URL] [--token 令牌] [--subdir 分组] [--config 文件] [--force] [目录]
      初始化同步目录（缺省当前目录），写入 .filesync.json 保存服务器/令牌/分组/基线。
      --subdir   该目录绑定到云端某个子目录（同步组），不同分组互不可见。
      --config   导入 JSON 配置（server/token/subdir，App"导出配置"或服务端生成）：
                 字段有则覆盖对应 -- 参数，没有的保留参数值。
      --force    已初始化的目录重新初始化：覆盖服务器/令牌/分组，基线清空，本地文件不动。

  filesync sync [--keep local|remote] [--token 令牌] [相对路径]
      双向同步整个目录；给出相对路径时只同步该文件或子目录（如 sync docs）。
      自动检测两端变化，仅传输有改动的文件；两端分别修改同一文件时逐个询问保留
      哪个版本，--keep local / --keep remote 可自动决策，stdin 关闭（脚本）时冲突跳过。
      上传携带 if-match 条件覆盖，并发修改被检测到时按冲突跳过，不覆盖服务器上更新的内容。
      删除不传播：一端删除的文件在另一端保留并提示；两端都删则自动清理记录。
      令牌缺省用 init 时保存的值，--token 可覆盖。

  filesync ls [--server URL] [--token 令牌] [--subdir 分组] [--prefix 子目录]
      列出云端文件（大小 / 修改时间 / 路径）；--prefix 仅列出某个远端子目录。

  filesync put [--server URL] [--token 令牌] [--subdir 分组] [--overwrite] 本地路径 [远端路径]
      手工上传文件或整个目录（独立于同步状态，不走基线比较）。
      远端已有同名文件时默认拒绝（409），--overwrite 覆盖。

  filesync get [--server URL] [--token 令牌] [--subdir 分组] [--overwrite] 远端路径 [本地路径]
      手工下载单个文件，缺省写到当前目录的同名文件；--overwrite 覆盖本地已有文件。

  filesync rm [-r] [--server URL] [--token 令牌] [--subdir 分组] 远端路径...
      删除云端文件（可一次给多个路径；-r 递归删除整个远端子树）。只删云端，
      触碰本地文件；下次 sync 时本地会提示"服务器已不存在，本地保留"。

多目录与多分组:
  · 每个同步目录独立：各自 init，各自的 --subdir 分组、基线与配置，互不影响。
  · 同一台服务器的不同 --subdir 即不同同步组；同名分组的多台设备互相同步。
  · 修改分组：filesync init --force --subdir 新分组 目录（基线清空，本地文件不动）。

配置文件互通（App / CLI / 服务端）:
  · App 设置页可"导出配置"：JSON（server/token/subdir），不含本机目录。
  · CLI 导入：filesync init --config filesync-client.json [目录]，字段缺省保留 -- 参数。
  · 服务端生成：filesync serve --client-config 路径 --public-url URL --token 令牌。

说明:
  · 远端路径以 '/' 分隔；绝对路径、'..'、反斜杠等一律拒绝，文件被限制在数据目录内。
  · 所有传输先写临时文件并做 SHA-256 双向校验，成功后原子改名；中断不会损坏已有文件。
  · ls/put/get/rm 的 --token 可省略：用环境变量 FILESYNC_TOKEN。
  · 部署（Ubuntu + systemd + TLS + IP 证书）与 Android App 用法见仓库 README.md。
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
