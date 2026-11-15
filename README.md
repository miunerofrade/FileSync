# FileSync

轻量级文件同步工具：一个 Go 二进制（HTTP 服务端 + CLI）+ 一个 Android App。
中心化存储、手动触发，数据即文件，无数据库、无后台进程。

## 功能

- **双向同步**：三方比较（本地 / 云端 / 基线）检测变化，仅传输有改动的文件
- **冲突处理**：两端分别修改时由用户选择保留版本，`--keep` 可自动决策
- **多目录 / 多分组**：目录绑定云端子目录（同步组），CLI 与 App 均可管理多组
- **删除不传播**：一端删除另一端保留并提示；删除云端用 `rm`（CLI）或云端列表（App）
- **公网可用**：Bearer Token 认证 + TLS（支持无域名的 IP 证书）
- **自动更新**（App）：检查 GitHub Release、下载并调起系统安装
- **完整性**：SHA-256 双向校验、临时文件 + 原子改名、路径白名单校验、`if-match` 条件覆盖

## 构建

### Go（服务端 + CLI）

```
go build -o filesync.exe .
# Linux:
GOOS=linux GOARCH=amd64 go build -o filesync .
```

### Android App

前提：JDK 17+、Android SDK（可用 Android Studio 自带的 JBR 与 SDK）。

```
cd android
gradlew.bat :app:assembleDebug     # 产物 app/build/outputs/apk/debug/app-debug.apk
gradlew.bat :app:assembleRelease   # R8 压缩版
```

- `local.properties` 写入本机 SDK 路径（如 `sdk.dir=D\:/android-studio-sdk`）
- 单测：`gradlew.bat :app:testDebugUnitTest`；设置 `FILESYNC_TEST_SERVER` 后
  额外启用对接真实服务端的集成探针
- 已启用 Gradle build cache / configuration cache（见 `gradle.properties`）

## 快速开始

终端 1（服务端）：

```
./filesync serve --addr 127.0.0.1:8080 --data-dir data
```

终端 2/3（CLI 客户端）：

```
./filesync init --server http://127.0.0.1:8080 D:/notes
cd /d/notes
./filesync sync                 # 双向同步整个目录
./filesync sync docs            # 只同步子目录
./filesync sync --keep local    # 冲突自动保留本地版本
./filesync rm docs/a.md         # 删除云端文件（本地不受影响）；-r 递归删远端子目录
```

Android App：

1. 设置页填写服务器地址与令牌，"测试连接"确认
2. 选择同步目录（SAF，权限持久化）；可添加多个同步方案（目录 + 分组）
3. 主屏"全量同步"；文件可勾选后同步选中项；云端视图可批量删除云端文件
4. Markdown 文件预览/编辑/保存；PDF 内置阅读

## 同步原理（CLI 与 App 一致）

每个客户端维护基线：记录每个文件"上次同步成功时两端共同内容"的 SHA-256。
每次 sync 对每个文件做三方比较，**决策入口先判断两端内容是否一致**：

| 本地 | 服务器 | 结论 |
|------|--------|------|
| 内容一致（无论基线） | | 记录基线，跳过（自愈过期/损坏的基线） |
| 已变 | 未变 | 上传（携带 `if-match=基线` 条件覆盖） |
| 未变 | 已变 | 下载 |
| 首次遇到两端不同 | | 冲突：由用户选择保留哪个版本 |
| 两端都变且不同 | | 冲突：同上 |
| 一端删除 | 保留 | 不传播删除，仅提示（两端都删则清理记录） |

- 上传携带 `if-match`：服务器内容与基线不符时拒绝（HTTP 412），按冲突跳过，
  避免并发修改被静默覆盖
- 冲突交互：CLI 终端逐个询问 `[L]本地/[R]远端/[S]跳过`；`--keep` 自动决策；
  App 弹对话框；stdin 关闭（脚本）时冲突跳过并报告，退出码 1
- 状态分批原子落盘（CLI: JSON 临时文件+改名；App: SharedPreferences commit），
  中断后重新 sync 自动收敛

## HTTP API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/ping` | 健康检查 |
| GET  | `/api/ls?prefix=` | 文件列表（path / size / sha256 / mod_time） |
| GET  | `/api/get?path=` | 下载文件，响应头 `X-Checksum-SHA256` |
| PUT  | `/api/put?path=&overwrite=&if-match=` | 上传；新文件 201，已存在 409，条件覆盖不符 412 |
| DELETE | `/api/delete?path=&recursive=` | 删除云端文件/目录；404 幂等；空路径拒绝 |
| GET  | `/api/update-check` | 转发 GitHub 最新 Release（服务端缓存 15 分钟，`refresh=1` 强制刷新） |

## 可靠性与安全设计

1. **路径校验**：拒绝绝对路径、`..`、`.`、反斜杠、盘符/冒号（NTFS ADS）、控制字符、
   Windows 保留名、`.`/空格结尾段；服务端二次确认不越出根目录
2. **完整性**：传输强制 SHA-256 双向校验，长度不符或校验失败则丢弃、不写入
3. **原子写入**：CLI 临时文件+fsync+改名；App 经 SAF 写临时文件后先删旧目标再改名，
   不触发 provider 去重改名
4. **默认不覆盖**：put 已存在时 409；`if-match` 条件覆盖避免并发修改丢失
5. **App 网络**：全部在 IO 线程执行；`Expect: 100-continue` 使被拒上传不发送文件体

## 已知限制

- 服务端为单令牌模型：所有客户端共享同一 Token，无细粒度权限
- 变化检测基于内容哈希：每次 sync 读取两端全部文件（个人文档场景可接受）
- 不上传空目录；不保留/同步文件修改时间
- 删除不传播：两端分歧需手动处理（`rm` / App 云端删除只作用于云端）
- 不做分块/断点续传；并发同步同一服务器未加锁
- App 的 Markdown 预览基于 Markwon（唯一第三方依赖）；PDF 用内置 PdfRenderer

## 公网部署（Linux VPS + HTTPS + Token）

服务端以独立系统用户运行、监听高位端口（8443）、Bearer Token 认证、
Let's Encrypt IP 证书（shortlived 6 天，acme.sh 自动续签并重启服务）。

### 服务端部署（Ubuntu 24.04 示例）

```
# 1. 构建并上传
GOOS=linux GOARCH=amd64 go build -o filesync-linux .
scp filesync-linux user@VPS:/tmp/filesync

# 2. 安装（VPS 上）
sudo useradd -r -s /usr/sbin/nologin filesync
sudo mkdir -p /opt/filesync/certs /var/lib/filesync
sudo mv /tmp/filesync /opt/filesync/filesync && sudo chmod 755 /opt/filesync/filesync
echo "FILESYNC_TOKEN=$(openssl rand -hex 32)" | sudo tee /opt/filesync/filesync.env
sudo chmod 600 /opt/filesync/filesync.env && sudo chown -R filesync:filesync /var/lib/filesync /opt/filesync
```

systemd 单元见 `deploy/filesync.service`，核心字段：

```
[Service]
User=filesync
EnvironmentFile=/opt/filesync/filesync.env
ExecStart=/opt/filesync/filesync serve --addr :8443 --data-dir /var/lib/filesync \
  --tls-cert /opt/filesync/certs/fullchain.pem --tls-key /opt/filesync/certs/privkey.pem
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/lib/filesync
```

### IP 证书（Let's Encrypt shortlived，无需域名）

LE 对 IP 签发 6 天短期证书，acme.sh 自动续签（剩余 <2/3 生命周期时续签），
`--reloadcmd` 在续签后自动重启服务加载新证书。验证方式按环境选择：

- 80 空闲 → `--standalone`（HTTP-01）
- 80 被 nginx 占用 → `--webroot /var/www/html`（只放验证文件，不动 nginx 配置）
- 443 空闲 → `--standalone --alpn`（TLS-ALPN-01；注意 tailscaled 等占用 tailscale0:443 时不可用）

```
curl -s https://get.acme.sh | sh -s -- --server letsencrypt   # 国内机器可本地克隆后安装
~/.acme.sh/acme.sh --register-account --server letsencrypt
~/.acme.sh/acme.sh --issue -d <VPS公网IP> --webroot /var/www/html \
  --server letsencrypt --certificate-profile shortlived --days 3
~/.acme.sh/acme.sh --install-cert -d <VPS公网IP> --ecc \
  --fullchain-file /opt/filesync/certs/fullchain.pem \
  --key-file /opt/filesync/certs/privkey.pem \
  --reloadcmd "sudo systemctl restart filesync"
```

### 客户端配置

CLI（Token 也可用环境变量 FILESYNC_TOKEN）：

```
./filesync init --server https://<VPS IP>:8443 --token <令牌> --subdir notes D:/notes
./filesync sync
```

**多目录 / 多分组**：不同 `--subdir` 即不同同步组，同名分组的多台设备互相同步。
修改分组：`filesync init --force --subdir 新分组 目录`（覆盖配置并重置基线，本地文件不动）。

**配置文件互通**：App 设置页可"导出配置"（JSON：`server`/`token`/`subdir`，不含本机
同步目录），服务端也可用 `serve --client-config` 生成。CLI 用 `init --config 文件`
导入，字段级合并——配置文件里有的覆盖对应 `--` 参数，没有的保留参数值：

```
./filesync init --config filesync-client.json --subdir pc D:/pc
```

**App 侧**：设置页填服务器地址与令牌；同步方案（目录 + 分组）在主屏左上角切换器管理；
基线按"目录 + 分组"独立存储，切换方案不丢失。App 与 CLI 均使用系统证书库标准验证 TLS。

### App 更新

App 启动时（24 小时节流）与手动"检查更新"均会经服务端代理查询 GitHub 最新 Release，
发现新版弹窗下载并调起系统安装。首次更新需在系统设置中授权"安装未知应用"（一次性）。

### 防扫描说明

高位端口只降低被随机扫描的概率，不是安全边界；真正的防线是：强制 Bearer Token
（常量时间比较）、标准 TLS 验证、系统用户 + systemd 权限加固（NoNewPrivileges /
ProtectSystem）、独立数据目录。VPS 上已启用 fail2ban 的机器可进一步保护 SSH 层。
