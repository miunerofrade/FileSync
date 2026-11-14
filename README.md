# FileSync

轻量级、中心化、手动触发的跨平台文件同步工具。仅依赖 Go 标准库 + Android 原生 API，
单一 Go 二进制 + 单一 APK，无数据库、无自动同步、无鉴权、无版本管理。

- 第一阶段：HTTP 文件服务端 + CLI（serve / put / get / ls）
- 第二阶段：手动双向同步（init / sync），三方比较变化检测与冲突处理
- 第三阶段：Android 原生 App（Kotlin + Jetpack Compose）

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
# 首次构建（gradle wrapper 会自动下载 Gradle 9.2.0 与依赖）
gradlew.bat :app:assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

- `local.properties` 写入本机 SDK 路径（如 `sdk.dir=D\:/android-studio-sdk`）
- 无 Gradle 时可用任意 Gradle 9.x 执行 `gradle wrapper` 生成 wrapper，或直接用
  `gradle.bat :app:assembleDebug`
- 测试：`gradlew.bat :app:testDebugUnitTest`
  - 含同步逻辑单测（16 项，内存假实现）与真实服务端集成探针（设置
    `FILESYNC_TEST_SERVER=http://127.0.0.1:8080` 后自动启用）
- 安装到设备：`adb install -r app/build/outputs/apk/debug/app-debug.apk`

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
./filesync rm docs/a.md         # 只删云端文件（本地不受影响）；-r 递归删远端子目录
```

Android App：

1. 设置页填写服务器 IP / 端口，"测试连接"确认
2. 选择同步目录（SAF，权限持久化）
3. 主屏点"同步"一键双向同步；文件列表可勾选多选同步；
   云端视图（Chip 切换）中可勾选单个/全选后批量删除云端文件（只删云端，本地保留）
4. 点击 Markdown 文件 → 预览/编辑/保存；PDF 文件 → 内置阅读

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

- 上传携带 `if-match`：服务器内容与基线不符时拒绝（HTTP 412），按冲突跳过，绝不静默覆盖
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
| DELETE | `/api/delete?path=&recursive=` | 删除云端文件/目录；404 幂等（视为已删除）；空路径拒绝（防误删根目录） |

## 可靠性与安全设计

1. **路径校验**：拒绝绝对路径、`..`、`.`、反斜杠、盘符/冒号（NTFS ADS）、控制字符、
   Windows 保留名、`.`/空格结尾段；服务端二次确认不越出根目录
2. **完整性**：所有传输强制 SHA-256 双向校验，长度不符或校验失败一律丢弃
3. **原子写入**：CLI 临时文件+fsync+改名；App 经 SAF 写临时文件（时间戳命名）后
   先删旧目标再改名（改名目标不存在，必然精确生效，不触发 provider 去重改名）
4. **默认不覆盖**：put 409；`if-match` 条件覆盖防并发修改被静默覆盖
5. **App 网络全部在 IO 线程**；`Expect: 100-continue` 使被拒上传不发送文件体

## 已知限制（后续阶段计划）

- 无鉴权，仅适用于本机或可信局域网
- 变化检测基于内容哈希：每次 sync 需读取两端全部文件（个人文档场景可接受）
- 不上传空目录；不保留/同步文件修改时间
- 删除不传播：一端删除只在另一端保留并提示；需要删云端时用 `filesync rm`
  （或 App 云端列表的删除按钮），只删云端不动本地；两端分歧需手动处理
- 不做分块/断点续传；并发同步同一服务器未加锁
- App 的 Markdown 预览基于 Markwon（唯一第三方依赖）；PDF 用内置 PdfRenderer

## 第四阶段：公网部署（Linux VPS + HTTPS + Token）

服务端以独立系统用户运行、监听高位端口（8443）、强制 Bearer Token 认证、
使用 Let's Encrypt 签发的 IP 证书（shortlived 6 天，acme.sh 自动续签并重启服务）。

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

systemd 单元（/etc/systemd/system/filesync.service，见 deploy/）核心字段：

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

LE 对 IP 签发 6 天短期证书，acme.sh 每天自动续签（剩余 <2/3 生命周期时续签），
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
./filesync init --server https://124.222.6.120:8443 --token <令牌> --subdir photos D:/photos
./filesync sync
# 多文件夹隔离：同一配置 + 不同 --subdir（云端按子目录分文件夹存放，互不混淆）
./filesync init --server https://124.222.6.120:8443 --token <令牌> --subdir notes D:/notes
./filesync sync
# 以后修改分组（换同步组）：
#   CLI: filesync init --force --subdir notes2 D:/notes（覆盖配置并清空基线，本地文件不动）
#   App: 设置页直接改"云端子目录"（基线按分组独立保存，改完直接同步即可）
```

Android App 设置页：服务器地址填 `https://124.222.6.120:8443`，访问令牌填部署时生成的值。
App 与 CLI 均使用系统证书库标准验证 TLS（不提供忽略证书错误的选项）。

### 防扫描说明

高位端口只降低被随机扫描的概率，不是安全边界；真正的防线是：强制 Bearer Token
（常量时间比较）、标准 TLS 验证、系统用户 + systemd 权限加固（NoNewPrivileges /
ProtectSystem）、独立数据目录。VPS 上已启用 fail2ban 的机器可进一步保护 SSH 层。
