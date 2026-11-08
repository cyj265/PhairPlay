# PhairPlay

**把任何一台 Android 电视变成一块「谁都能投」的屏幕。**

开源、无广告、不联网的电视投屏接收端，适用于 **Android TV / Google TV / Fire TV
以及各类 Android 电视盒子、投影仪**。一个 App 同时接下 AirPlay、DLNA、Google Cast
和 Miracast。

<p align="left">
  <img alt="license" src="https://img.shields.io/badge/license-Apache--2.0-blue">
  <img alt="platform" src="https://img.shields.io/badge/platform-Android%207.1%2B-3ddc84">
  <img alt="kotlin" src="https://img.shields.io/badge/Kotlin-1.9-7f52ff">
  <img alt="release" src="https://img.shields.io/github/v/release/cyj265/PhairPlay?display_name=tag">
</p>

```
 macOS / iPhone          Windows / Android            电视盒子
 ┌────────────┐          ┌────────────┐          ┌──────────────────┐
 │  AirPlay   │ ───────► │  Miracast  │ ───────► │                  │
 │  DLNA App  │ ───────► │  Cast V2   │ ───────► │    PhairPlay     │
 └────────────┘          └────────────┘          └──────────────────┘
        同一条局域网，不经过云端，不注册账号，不需要 Apple TV / Chromecast
```

---

## 支持矩阵

| 协议 | 发送端 | 状态 | 说明 |
|------|--------|------|------|
| **AirPlay 2** | macOS 12+、iOS / iPadOS 16+ | ✅ 可用 | 镜像 + 音频 + 照片 + 视频 URL，HomeKit 配对、FairPlay 解密、AAC-ELD/ALAC、NTP 音画同步、DACP 反向遥控 |
| **DLNA / UPnP** | Android 手机上的 B 站、腾讯视频、抖音等 | ✅ 可用 | 自写 SSDP + SOAP，HLS / FLV / MPEG-TS，HEVC 硬解；按 Back 暂停回首页，投屏会话不中断 |
| **Google Cast V2** | VLC 等走 LAN Cast V2 的发送端 | ✅ 可用 | TLS `8009` + mDNS `_googlecast._tcp`，内置 Default Media Receiver (`CC1AD845`)，ExoPlayer 直接播放 |
| **Miracast (WFD)** | Windows「连接」、Android「无线投屏」 | ✅ 可用 | Wi-Fi Direct 组网、WFD RTSP 握手、MPEG-TS / H.264 / AAC 硬解渲染；Android 手机镜像 30fps 稳定，Windows 桌面源在高码率动态场景下仍有卡顿（定位中） |

> **Cast 的真实边界**：本项目使用自签名证书，Chrome 投屏菜单、YouTube、Google Home
> 会校验 Google Cast CA，因此不会接受本接收端；能连上的是只要求 Cast V2 over TLS 的
> 局域网发送端（VLC 是参考客户端）。Google TV flavor 改走官方 Cast Connect SDK，
> 不受这条限制。详见 [docs/guides/CAST_APP_ID.md](docs/guides/CAST_APP_ID.md)。
>
> **Miracast 的前提**：盒子必须支持 Wi-Fi Direct。系统已开启无线显示
> （`wifi_display_on=1`）时由框架直接广播；否则 App 需要 root 才能通过
> `libwfdctl.so` 注入 WFD IE。没有 root 且系统未开启时，协议卡片会显示「出错」，
> 其余三种协议不受影响。

---

## 为什么用它

- **四个协议，一个 App** —— 家里有 Mac、iPhone、Windows 和安卓手机，不用为每种设备准备一台不同的接收器。
- **遥控器优先的界面** —— 首页、设置、播放控制全部为 D-pad 设计，字号按客厅观看距离放大，不使用手机风格的控件条。
- **本地直连** —— 没有账号、没有云中转、没有统计 SDK；断网也能投。
- **诚实的失败提示** —— 源站拒绝、证书不匹配、编码不支持都会在屏幕上说清楚，而不是留一块黑屏。
- **现场可诊断** —— `8099` 端口实时暴露运行日志，盒子出问题时不用插 USB。

---

## 安装

### 直接下载 Release

到 [Releases](https://github.com/cyj265/PhairPlay/releases) 下载对应 APK。两个包功能相同，
区别只在 **Cast 的实现方式** 和最低系统版本：

| APK | 适用设备 | Cast 能力 |
|-----|----------|-----------|
| `PhairPlay-vX.Y.Z-androidtv.apk` | **通用电视版**：Fire TV、Android TV 7.1+、Amlogic / 晶晨等 Android 盒子、投影仪 | 自签名 Cast V2（VLC 等局域网发送端） |
| `PhairPlay-vX.Y.Z-googletv.apk` | Google TV / Android TV 10+ | 官方 Cast Connect（需 Google Play 服务与 Cast App ID） |

> Gradle 里通用电视版的 flavor 名仍是 `firetv`（历史命名，minSdk 25），**它不是
> Fire TV 专属**——只要系统是 Android 7.1 及以上、带 Leanback 启动器，普通 Android TV
> 和第三方盒子都装这个包。

**32 位 / 64 位**：上面两个 APK 都是 universal 包，内置 `armeabi-v7a`（32 位 ARM）、
`arm64-v8a`（64 位 ARM）以及 `x86` / `x86_64`，所以只支持 32 位的老板子和 64 位新设备都
装同一个包；系统会按设备 ABI 自动取对应的 `.so`。

```bash
adb connect <盒子IP>
adb install -r PhairPlay-vX.Y.Z-androidtv.apk
```

电视端开启 ADB 的方法见 [docs/guides/INSTALLATION.md](docs/guides/INSTALLATION.md)。

### 从源码编译

需要 JDK 17、Android SDK（`compileSdk` 见 `app/build.gradle.kts`）。根目录 `local.properties` 写：

```properties
sdk.dir=C:/Android
```

```bash
git clone https://github.com/cyj265/PhairPlay.git
cd PhairPlay

# Fire TV / 普通盒子
./gradlew assembleFiretvDebug

# Google TV（替换成已注册的 Cast App ID）
./gradlew assembleGoogletvDebug -Pphairplay.castAppId=<APP_ID>
```

产物在 `app/build/outputs/apk/<flavor>/debug/`。跑单测与本地门禁：

```bash
./gradlew :app:testFiretvDebugUnitTest :app:assembleFiretvDebug
```

---

## 怎么用

| 场景 | 操作 |
|------|------|
| **Mac 镜像** | 菜单栏「控制中心 → 屏幕镜像」，选中电视名字 |
| **iPhone / iPad** | 控制中心「屏幕镜像」，选中电视名字 |
| **DLNA 投视频** | 视频 App 里点投屏按钮，选中电视；播放中按 **Back** 暂停回首页，点「继续播放」从原位置接上 |
| **VLC → Cast** | VLC 里「渲染器 → 扫描」，选中电视，再播放任意本地文件或网络流 |
| **Windows 镜像** | `Win + K` →「投放」→ 选中电视 |
| **Android 镜像** | 下拉快捷开关「无线投屏 / 多屏互动」，选中电视 |

显示名称在 **设置 → 设备名称** 里改；留空时使用系统设备名。

---

## 工作原理

每种协议都是独立接收链路，由前台服务统一拉起、统一上报状态：

```
                        ┌───────────────────────────────┐
   AirPlay  ─────────►  │                               │
   DLNA     ─────────►  │        PhairPlayService       │──► 首页状态 / 通知
   Cast V2  ─────────►  │  （生命周期、网络变化、唤醒）  │──► StreamingScreen
   Miracast ─────────►  │                               │
                        └───────────────────────────────┘
```

| 端口 | 协议 | 用途 |
|------|------|------|
| `7000/tcp` | AirPlay | RTSP 控制 |
| `6002/udp` | AirPlay | NTP timing / 音频重传 |
| `1900/udp` | DLNA | SSDP 发现 |
| `8899/tcp` | DLNA | 设备描述 + SOAP + 媒体 |
| `5353/udp` | mDNS | `_airplay._tcp`、`_raop._tcp`、`_googlecast._tcp` |
| `8009/tcp` | Cast V2 | TLS 控制通道 |
| `7236/tcp` | Miracast | WFD RTSP（Sink 主动拨号到 Source） |
| `19000/udp` | Miracast | MPEG-TS over RTP |
| `8099/tcp` | 诊断 | 实时日志（只读） |

更多设计取舍见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，测试方法见
[docs/TESTING.md](docs/TESTING.md)。

---

## 已知限制

- **受 DRM 保护的内容**（Netflix、Disney+、Apple TV+、部分 IPTV）无法投屏，这不是本项目的限制，而
 是 DRM 本身的设计。
- **Cast 的通用性受证书限制**：自签名证书过不了 Google Cast CA 校验，Chrome / YouTube / Google Home
  不会把它当成接收器；需要这些发送端时请使用 Google TV flavor 的 Cast Connect。
- **Miracast 依赖 Wi-Fi Direct**：发送端与盒子必须支持 P2P。部分 Windows 网卡驱动会报告
  「不支持无线显示」，此时 `Win + K` 列表里不会出现设备，需要换一台发送端。
- **AirPlay 受保护音频**：Apple Music 的应用内音频在所有 AirPlay 路径上都被 FairPlay 保护，
  可改用系统音频输出；AirPlay 2 的缓冲音频（type 103）已接受但尚未播放。
- **竖屏视频不会自动放大**：竖屏源按原始比例加黑边显示，可在播放菜单里手动切换画面模式。
- 路由器开启 **AP 隔离** 或 **组播过滤** 时，mDNS 发现会失效；关闭这两个选项即可。

---

## 开发

```bash
./gradlew :app:compileFiretvDebugKotlin      # 快速类型检查
./gradlew :app:testFiretvDebugUnitTest       # 单元测试
./gradlew :app:lintFiretvDebug               # lint
```

代码规范、提交要求见 [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)；CI 会跑构建、单测和 lint。

目录速览：

```
app/src/main/kotlin/com/phairplay/
├── airplay/     AirPlay 2：RTSP、配对、FairPlay、镜像、音频同步
├── cast/        Cast V2：wire 编解码、设备认证、mDNS、Default Media Receiver
├── miracast/    WFD：Wi-Fi Direct、RTSP、TS 解复用、硬解渲染
├── dlna/        DLNA：SSDP、SOAP、AVTransport
├── service/     PhairPlayService：生命周期与统一状态
└── ui/          遥控器优先的首页 / 设置 / 播放界面
```

---

## 许可与致谢

本项目以 **Apache License 2.0** 发布，见 [LICENSE](LICENSE)。

部分协议的接收端参考并移植自以下 MIT 项目，代码中保留了来源注释，
完整许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)：

- [weekdayjast/MiracastReceiver](https://github.com/weekdayjast/MiracastReceiver) — Cast V2 接收端与 Miracast WFD Sink 的移植基线
- [CastHub](https://github.com/CastHub) / [openchromecast](https://github.com/yukarinoki/openchromecast) / [vibecast](https://github.com/emilsvennesson/vibecast) — Cast V2 行为参考
- [openairplay/airplay-spec](https://github.com/openairplay/airplay-spec) — AirPlay 协议文档
- [UxPlay](https://github.com/FDH2/UxPlay) / [RPiPlay](https://github.com/FD-/RPiPlay) — AirPlay 镜像参考实现

感谢所有把协议细节写进文档和开源代码的人。
