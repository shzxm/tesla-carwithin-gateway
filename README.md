# Tesla CarWithin Gateway (Root Android)

> 把任意一台已 Root 的备用安卓手机，变成**特斯拉 CarWithin 本地零延迟网关**：自动配置 `iptables` 绕过特斯拉内网 IP 黑名单、内置正规 Let's Encrypt HTTPS 绿锁证书（支持 Cloudflare DNS-01 自动申请/续期与手动导入）、零缓冲 WebSocket 反向代理，并自动发现热点下主 iPhone 的 CarWithin 端口。
>
> **0 公网视频流量 · 100% 车内 5 GHz 局域网直连 · 主 iPhone 的 CarWithin 与 Surge / Loon 完美共存。**

---

## 为什么需要这个项目？

在特斯拉车机浏览器中使用主 iPhone 的 CarWithin 投屏时，通常面临三个系统级硬限制：

1. **特斯拉浏览器拦截常见私网 IPv4**：访问 `192.168.x.x`、`172.16-31.x.x`、`10.x.x.x` 会被车机直接阻断。
2. **WebCodecs 硬件低延迟解码要求正规 HTTPS 绿锁**：自签名证书会被拦截或降级。
3. **主 iPhone 无法同时优雅地自建网关与运行 Surge / Loon**：未越狱 iOS 个人热点流量绕过代理核心，且车机 Wi-Fi 无法配置代理。

**本项目的解法**：利用一台已 Root 的安卓手机开 5 GHz 热点作为网关，一步到位替代 OpenWrt 便携路由器 + Termux + Caddy：

```text
┌───────────────────────────┐            5 GHz 热点局域网            ┌─────────────────────────────┐
│        特斯拉车机          │  ──────────────────────────────────►  │     已 Root 的备用安卓机     │
│  浏览器打开:               │   DNS 解析 car.<你的域名>              │  1. 开启 5 GHz 个人热点       │
│  car.<你的域名>:8443       │   -> 100.64.0.1 (CGNAT, 车机放行)      │  2. iptables PREROUTING 劫持  │
└───────────────────────────┘                                        │     100.64.0.1:8443 -> :8443  │
              ▲                                                      │  3. 内置 Go HTTPS/WS 反代核心 │
              │ 蓝牙音频直连                                          │     (Let's Encrypt 绿锁)      │
              │                                                      └──────────────┬──────────────┘
┌─────────────┴─────────────┐                                                       │ 局域网零延迟反代
│        主 iPhone          │  ◄────────────────────────────────────────────────────┘ (自动发现 9888)
│  CarWithin (:9888)        │
│  + Surge / Loon 照常运行  │
└───────────────────────────┘
```

---

## 核心特性

- **一键 Root `iptables` 劫持与自动清理**：启动服务时幂等添加 `PREROUTING REDIRECT`（默认劫持 `100.64.0.1:8443` 到本机 `:8443`）与 `INPUT ACCEPT` 规则，停止服务时自动清理，不留脏规则。
- **内置高性能 Go 反向代理核心 (`core/`)**：编译为 `arm64-v8a` / `armeabi-v7a` 原生二进制打包在 APK 内，支持 `FlushInterval: -1` 零缓冲转发 HTTP 与 WebSocket 流量，无须安装 Termux 或外部 Caddy。
- **正规 HTTPS 绿锁证书（两种模式）**：
  - **Cloudflare DNS-01 自动申请与续期**：填入 Cloudflare API Token（仅需 `Zone:DNS:Edit` 权限），自动向 Let's Encrypt 申请证书并在到期前 30 天内自动续期。
  - **手动导入证书**：支持通过系统文件选择器直接导入已有的 `fullchain.pem` 与 `privkey.pem`。
- **主 iPhone IP 自动发现与热更新**：通过 Root `ip -4 neigh show` 实时扫描热点内网邻居，自动探测 `9888` 端口（支持可选 MAC 地址锁定或手动固定 IP），iPhone 重连换 IP 也能秒级自动切换，无须重启网关。
- **前台服务 + WakeLock 保活**：熄屏放扶手箱也能稳定运行，状态栏实时显示当前上游 IP 与车机访问地址。

---

## 快速上手

### 第一步：配置公网 DNS（一次性）

在你的域名解析商（推荐 Cloudflare）添加一条 `A` 记录：

| 类型 | 名称 | 内容 | 代理状态 |
| :--- | :--- | :--- | :--- |
| `A` | `car`（即 `car.<你的域名>`） | `100.64.0.1` | 仅 DNS（关闭小云朵 / 灰云） |

> 说明：`100.64.0.0/10` 是 RFC 6598 保留的 CGNAT 地址段，不在特斯拉浏览器的 RFC 1918 私网黑名单内，车机可直接放行并由安卓机的 `iptables` 在本地无缝劫持，不会产生任何公网视频流量。

### 第二步：准备 HTTPS 证书（二选一）

- **方式 A（推荐，全自动）**：在 Cloudflare 控制台创建一个仅含 `Zone -> DNS -> Edit` 权限的 API Token，填进 App 即可自动申请 Let's Encrypt 证书。
- **方式 B（手动导入）**：使用任意域名商在电脑/VPS 上通过 `acme.sh` 或 `certbot` 申请好证书，把 `fullchain.pem` 和 `privkey.pem` 拷贝到安卓手机，在 App 中切换“手动导入证书”选择导入。

### 第三步：配置主 iPhone 的 Surge / Loon（防止内网被代理拦截）

将安卓热点网段（通常为 `192.168.43.0/24` 或 `192.168.0.0/16`）加入排除规则：

- **Surge (`[General]`)**：
  ```ini
  skip-proxy = 127.0.0.1, 192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, localhost, *.local
  tun-excluded-routes = 192.168.0.0/16
  ```
- **Loon (`[General]`)**：
  ```ini
  skip-proxy = 127.0.0.1, 192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12, localhost, *.local
  bypass-tun = 192.168.0.0/16
  ```

### 第四步：安装并启动 CarWithin Gateway

1. 从本仓库的 **[Releases](../../releases)**（或 Actions 构建产物）下载 `CarWithinGateway-universal.apk` 并安装到已 Root（Magisk / KernelSU / APatch）的安卓手机。
2. 开启安卓手机的 **5 GHz 个人热点**（建议插副卡保持外网，特斯拉连有外网的 Wi-Fi 最稳定）。
3. 打开 **CarWithin Gateway** App：
   - 填写你的域名（例如 `car.<你的域名>`）；
   - 保持劫持 IP 为 `100.64.0.1`，监听端口 `8443`；
   - 填入 Cloudflare API Token（或导入手动证书）；
   - 点击 **启动网关** 并授予 Root 权限。
4. 主 iPhone 与特斯拉车机均连上该 5 GHz 热点，主 iPhone 打开 CarWithin（蓝牙连车机出声音）。
5. 特斯拉车机浏览器打开 `https://` + `car.<你的域名>:8443`，即可享受正规绿锁 + 本地 5 GHz 零延迟投屏！

---

## 工程结构

```text
.
├── core/                         # Go 高性能 HTTPS + WebSocket 反代与 ACME 核心
│   ├── main.go                   # 启动入口、TLS 配置与本地控制 API
│   ├── proxy.go                  # 零缓冲 HTTP/WebSocket 反代与热更新引擎
│   ├── cert.go                   # 证书加载、热重载与自动续期循环
│   ├── acme_cloudflare.go        # Let's Encrypt ACME v2 Cloudflare DNS-01 实现
│   └── proxy_test.go             # HTTP / WebSocket / 证书单元测试
├── android/                      # Kotlin Android 外壳应用
│   └── app/src/main/java/com/shzxm/carwithingateway/
│       ├── MainActivity.kt       # 配置界面、证书导入与实时日志
│       ├── GatewayService.kt     # 前台保活服务、核心进程托管与上游热更新
│       ├── RootIptables.kt       # Root iptables 规则幂等应用与清理
│       ├── UpstreamDiscovery.kt  # ip neigh + 9888 端口自动发现
│       ├── GatewayConfig.kt      # 配置持久化与 JSON 生成
│       └── GatewayLog.kt         # 内存日志与状态流
├── scripts/
│   └── build-core.sh             # Go 核心 Android arm64/armv7 交叉编译脚本
└── .github/workflows/
    └── build-release.yml         # GitHub Actions 自动测试、编译与 Release 发布
```

---

## 本地自行编译

前置依赖：Go 1.23+、JDK 17、Android SDK（API 34）。

```bash
# 1. 运行 Go 核心单元测试
cd core && go test -v ./... && cd ..

# 2. 交叉编译 Go 核心到 android/app/src/main/jniLibs/
./scripts/build-core.sh

# 3. 编译 Android APK
cd android && gradle assembleRelease
```

---

## 常见问题排查

1. **首次启动 Cloudflare DNS-01 申请证书较慢？**
   - 首次申请会在 Cloudflare 添加 `_acme-challenge` TXT 记录并等待约 15 秒 DNS 传播，成功后证书保存在 App 私有目录，后续启动秒开，到期前自动续期。
2. **车机打开提示“CarWithin 本地网关已就绪 / 未发现主 iPhone”？**
   - 说明 `iptables` 劫持和 HTTPS 绿锁都已成功！请检查主 iPhone 是否已连上安卓热点、CarWithin 是否在前台启动、以及 Surge / Loon 是否已排除 `192.168.0.0/16`。
3. **部分安卓 ROM 热点会隔离客户端（AP 隔离）？**
   - 绝大多数原生/类原生/小米/一加热点默认不开启 AP 隔离；若发现安卓机自身能通主 iPhone 但车机或反代异常，可在日志中查看 `[discovery]` 输出确认能否探测到 `9888` 端口。

---

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。
