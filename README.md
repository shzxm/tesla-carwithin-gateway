# Tesla CarWithin Gateway (Root Android)

> 把任意一台已 Root 的旧安卓手机变成 **特斯拉 CarWithin 零延迟本地 HTTPS/WebSocket 网关**：无需购买 OpenWrt 硬件、无需折腾 Termux / Caddy、不走一字节公网视频流量，同时与主 iPhone 上的 **Surge / Loon** 完美共存。

---

## 核心特性

1. **Cloudflare API 域名读取与自定义子域名**
   - 填入 Cloudflare API Token 后，一键拉取账号下所有托管的根域名，输入前缀（如 `car`）自动生成自定义域名（如 `car.example.com`）。
2. **内置 Cloudflare DDNS（一键写入 A 记录）**
   - 自动将自定义域名的 A 记录指向指定非内网保留 IP（默认 `100.64.0.1`，关闭小黄云 `proxied=false`），绕过特斯拉浏览器对 `192.168.x.x` / `10.x.x.x` / `172.16-31.x.x` 的屏蔽。
   - 支持手动一键同步，也支持每次启动网关时自动校验更新。
3. **全自动申请 & 续期正规 HTTPS 证书（Let's Encrypt）**
   - 内置 Go ACME v2 客户端，通过 Cloudflare DNS-01 自动申请正规绿锁证书（无需 80 端口，也不需要公网服务器），本地持久化缓存，剩余不足 30 天自动续期；同时支持手动导入 `fullchain.pem` / `privkey.pem`。
4. **内网目标 iPhone 地址管理（防 IP 变动）**
   - 内置 **热点在线设备扫描**：通过 `ip neigh` + `9888` 端口探测一键找出主 iPhone。
   - 支持 **绑定主 iPhone MAC 地址（推荐）**：即便 iPhone 每次重连热点分配的内网 IP 发生变化，后台每 4 秒自动追踪最新 IP 并热更新反代上游。
   - 也支持 **手动指定内网 IP** 或 **全自动扫描 9888 端口**。
5. **零延迟本地反代 + Root iptables 透明劫持**
   - 启动时自动下发 `iptables` `PREROUTING` + `INPUT` 规则，将车机发往 `100.64.0.1:8443` 的流量直接劫持至本机反代核心；停止时自动清理规则。
   - Go 零缓冲（`FlushInterval: -1`）HTTP + WebSocket 反向代理，支持特斯拉车机 WebCodecs 硬件低延迟解码，**视频流 100% 走车内 5 GHz Wi-Fi 局域网，0 公网视频流量**。
6. **App 内置向导式流程与新手教程**
   - 界面严格按照 **第 1 步 → 第 2 步 → 第 3 步 → 第 4 步** 实际操作顺序排布，内置「使用教程」弹窗及 Surge/Loon 放行规则一键复制。

---

## 网络拓扑

```text
[ 主 iPhone ] (CarWithin :9888 + Surge/Loon 放行热点网段)
      │ 5 GHz Wi-Fi 局域网               │ 蓝牙 (负责车内声音输出)
      ▼                                 ▼
[ Root 安卓机 ] (开 5 GHz 热点 + 本 App) ◄── 5 GHz Wi-Fi ──► [ 特斯拉车机浏览器 ]
  ├─ Cloudflare DDNS: <你的域名> -> 100.64.0.1                打开 https://<你的域名>:8443
  ├─ Root iptables: 100.64.0.1:8443 -> 本机 :8443             (正规 Let's Encrypt 绿锁)
  ├─ Go HTTPS/WS 反代: :8443 -> 主 iPhone 内网 IP:9888
  └─ 自动追踪主 iPhone 最新内网 IP（按 MAC / 9888 端口）
```

---

## 手把手使用教程（按顺序操作）

### 准备工作
- 一台已 Root（Magisk / KernelSU / APatch）的安卓手机，建议插一张有流量的卡并开启 **5 GHz 个人热点**（特斯拉连有外网的 Wi-Fi 最稳定不易断开）。
- 从本仓库 **Releases** 页面下载最新 APK 并安装到 Root 安卓机。
- 一个托管在 Cloudflare 的域名，并创建 API Token：
  1. 登录 Cloudflare Dashboard → 右上角头像 → **My Profile** → **API Tokens** → **Create Token**。
  2. 选择 **Edit zone DNS** 模板（需包含 `Zone:Zone:Read` 和 `Zone:DNS:Edit` 权限）。
  3. 生成并复制 Token。

---

### 第 1 步：填写 Cloudflare API Token、自定义域名并同步 DDNS
1. 打开 App，在 **第 1 步** 粘贴刚才创建的 **Cloudflare API Token**。
2. 点击 **「读取我的 Cloudflare 域名并选择」**，在弹出的列表里点选你的根域名，输入前缀（如 `car`），自动填入完整自定义域名（如 `car.example.com`）。
3. 确认 **DDNS 指向 / 劫持 IP** 为 `100.64.0.1`（或自定义其他非内网 IP），**HTTPS 监听端口** 为 `8443`。
4. 点击 **「一键同步 Cloudflare DDNS（写入 A 记录）」**，看到绿色「已同步」提示即可。

---

### 第 2 步：一键自动化申请正规 HTTPS 证书
1. 保持默认的 **Cloudflare 自动申请** 模式（可按需填写 ACME 通知邮箱）。
2. 点击 **「一键申请 / 检测 Let's Encrypt 证书」**，等待约 20 秒完成 DNS-01 验证。
3. 看到绿色 **「证书状态：已就绪」** 即可（证书保存在本地，后续无外网也能正常提供 HTTPS 服务，到期前自动续期）。

> 如需使用自有证书，可切换为「手动导入 PEM」，分别选择 `fullchain.pem` 与 `privkey.pem`。

---

### 第 3 步：设置主 iPhone 内网目标地址（防 IP 变动）
1. 在 Root 安卓上开启 **5 GHz 个人热点**。
2. 主 iPhone 连上该热点，并打开 **CarWithin**（默认端口 `9888`）。
3. 回到 App **第 3 步**，点击 **「扫描当前热点在线设备」**：
   - 在列表中找到带有 **`★ 已发现 CarWithin (9888)`** 标记的主 iPhone。
   - 点击它并选择 **「绑定 MAC（推荐，iPhone IP 变了自动追踪）」**。
   - 如果希望临时固定某个内网 IP，也可以选择填入固定 IP。

#### 主 iPhone 使用 Surge / Loon 的必做配置
为防止 CarWithin 的 `9888` 端口被代理软件 TUN 接管，将安卓热点网段（通常为 `192.168.43.0/24`）加入放行：

**Surge (`[General]`):**
```ini
skip-proxy = 127.0.0.1, 192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, localhost, *.local
tun-excluded-routes = 192.168.43.0/24, 192.168.0.0/16
```

**Loon (`[General]`):**
```ini
skip-proxy = 127.0.0.1, 192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, localhost, *.local
bypass-tun = 192.168.43.0/24, 192.168.0.0/16
```
> 在 App 右上角 **「使用教程」** 弹窗中也可一键复制上述规则。

---

### 第 4 步：启动网关并在特斯拉打开
1. 点击 **「启动本地网关」**（首次会弹出 Root 授权提示，请点击允许）。
2. 状态栏变为绿色运行中，确认「当前主 iPhone 上游」已显示为 `http://192.168.x.x:9888`。
3. 特斯拉车机连上安卓的 5 GHz 热点，打开车机浏览器访问复制好的地址：
   ```text
   https://<你的自定义域名>:8443
   ```
4. 主 iPhone 蓝牙连接特斯拉负责音频输出，即可享受正规绿锁、低延迟、零公网视频流量的无线 CarPlay！

---

## 本地构建与开发

本项目采用 **Go 高性能反代核心 (`core/`) + Kotlin Android 外壳 (`android/`)** 架构：

```bash
# 1. 运行 Go 核心单元测试（代理、WebSocket 透传、Cloudflare DDNS、TLS）
cd core && go test -v ./...

# 2. 交叉编译 Android arm64-v8a / armeabi-v7a 核心二进制
cd .. && ./scripts/build-core.sh

# 3. 编译 Android Release APK（需 JDK 17 + Android SDK）
cd android && gradle assembleRelease
```

---

## 免责声明与开源协议

本项目基于 [MIT License](LICENSE) 开源，仅用于个人局域网网络工具学习与合法合规的本地连接用途。行车时请注意道路安全，遵守当地交通法规。
