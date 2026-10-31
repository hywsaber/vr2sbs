<p align="center">
  <img src="assets/banner.png" alt="vr2sbs" width="100%">
</p>

<p align="center">
  <a href="README.md">English</a> · <strong>简体中文</strong>
</p>

## vr2sbs v0.2.2 版本更新说明

本版本新增 SMB 局域网自动发现，像 Windows 11 的“网络”一样打开共享：扫描设备、点击设备、输入账户，即可直接浏览该设备的共享目录，无需再输入 Share name。原有 SMB 播放、投影和追踪功能保持不变。

### 新增功能

#### Scan SMB：自动扫描局域网服务器

- 主界面 “Open SMB share” 下方新增 “Scan SMB” 按钮。
- 进入后自动扫描当前 Wi-Fi/以太网中的 SMB 服务器，边发现边显示；点击 **Rescan** 可重新扫描。
- 通过限定并发的 TCP 445 端口探测（450ms 超时）发现服务器。常规 /24 网段扫描全部 254 个地址；更大网段仅扫描本地 /24；/23 及更小网段按实际范围扫描，单次最多 1024 个目标。
- 使用 NetBIOS 节点状态查询解析 `MEDIA-PC` 这类友好名称，无法解析时显示 IP 地址。
- 仅使用已有的网络权限，不需要新增运行时权限。

#### 免输入共享名的设备级登录

- 点击扫描到的设备后，弹出与 “Open SMB share” 相同的凭据窗口：用户名、域（可选）、密码，以及 **Remember this account securely**。
- 登录成功后直接进入该设备的共享列表，无需填写 Share name。
- 与 Windows“网络”一致，设备根目录自动隐藏 `C$`、`ADMIN$`、`IPC$` 等管理共享。
- 已记住的设备以紫色控件显示在扫描页顶部，点击即可重新连接；长按可删除该设备的全部保存凭据。
- 旧版本保存的“主机 + Share”账户会自动归并为每台设备一个条目，可直接在扫描页复用，无需重新登录。

### 改进与修复

- 手动 “Open SMB share” 表单中的 **Share name** 现在可以留空，此时会列出服务器上的全部共享。
- 扫描中的结果数量与完成文案正确区分单复数。
- 新增单元测试：网段扫描目标选择、NetBIOS 查询/响应编码与解析、已记住设备的归并逻辑。

### 安装与兼容性

- versionName `0.2.2`，versionCode `2`，minSdk 33，targetSdk 35。
- 适配 XREAL One / XREAL One Pro。
- 可直接覆盖升级 v0.2.1（签名一致），已保存的 SMB 配置会保留。
- 可与原版 vr2xr-neo 共存安装。

### 已知问题

- 部分片源在眼镜端观感上右半画面分辨率偏低的问题仍在定位中，本版本未改动相关显示/外接输出路径。

---

## vr2sbs v0.2.1 版本更新说明

本版本基于 [NeoNatural/vr2xr-neo](https://github.com/NeoNatural/vr2xr-neo)，重点提升 SBS 输出设备的兼容性、调整默认观看视角，并支持按需关闭 IMU 头部追踪。上游已有的 SMB2/SMB3 网络播放、VR180 鱼眼投影、触控视角操作等功能保持不变，不再重复列出。

### 新增功能

#### SBS 输出格式可选（Half-SBS / Full-SBS）

- Projection Settings 新增 “SBS Output” 控件。
- **Half-SBS**：输出 1920×1080（每眼 960×1080），适配只支持 1080p 的 USB/转接链路。
- **Full-SBS**：输出 3840×1080（每眼 1920×1080）。
- 播放中切换后自动重建眼镜输出，无需退出播放；选择会自动保存。

#### Half-SBS 强制 1080p 渲染

即使眼镜上报的是 3840×1080 显示模式，Half-SBS 也按 1920×1080 生成真正的半宽 SBS 帧，避免被当作 Full-SBS 输出。

#### 外接显示模式自动匹配

根据所选输出格式选择同分辨率的显示模式，并优先保持当前刷新率，减少切换格式时的画面跳动。

#### 可关闭 IMU 追踪

- 校准页新增 “Disable IMU Tracking”：不需要头部追踪或无法完成校准时，可跳过校准直接进入播放。
- 开关状态跨页面保存，启动器、SMB 浏览与播放器统一生效。
- 播放器的眼镜设置中可随时重新开启：会进入校准流程，完成后自动回到原片源继续播放。
- 关闭 IMU 后，“回中”改为重置触控视角偏移。

#### 独立安装身份

- 应用名称改为 **vr2sbs**，图标保持不变。
- applicationId 改为 `com.vr2sbs`，可与原版 vr2xr-neo 同时安装，互不覆盖。

### 改进与修复

- 修复 Projection Settings 中 SBS 选项选中后文字与背景同色、看不清的问题。
- Half-SBS、Full-SBS 与 “Reset default FOV (58°)” 的字号统一为与其他投影选项一致。
- “Half Equirectangular (VR180)” 更名为 “VR180 to SBS”，与新增的 SBS 输出选项对应。
- View FOV 每次播放的默认值由 95° 调整为 58°；播放期间仍支持 25°–175° 实时调节、5° 步进、双指捏合、拖动调整与双击回中。
- 界面重建或旋转后保留当前 View FOV。
- 新增/更新单元测试：SBS 输出格式与显示模式选择、IMU 追踪启动策略、默认 FOV 配置。

### 安装与兼容性

- versionName `0.2.1`，versionCode `1`，minSdk 33，targetSdk 35。
- 适配 XREAL One / XREAL One Pro。
- 可与其他 vr2xr 版本共存安装；各版本设置独立存储，旧版本的 SMB 配置、投影设置不会自动迁移。

### 已知问题

- 部分片源在眼镜端观感上右半画面分辨率偏低的问题仍在定位中，本版本未改动相关显示/外接输出路径。

**vr2sbs 是一款面向 XREAL One 和 XREAL One Pro 眼镜的 Android VR SBS 视频播放器。** 它支持本地文件、HTTP(S) 地址、Android 分享 Intent 和 SMB2/SMB3 网络共享，并提供手机端控制与 IMU 头部追踪。

本仓库是 [Skarian/vr2xr](https://github.com/skarian/vr2xr) 的持续维护分支。原项目及作者信息见文末，本分支的重点增强如下。

## 本分支重点增强

> **本分支不只是常规维护：**在保留原版 vr2xr 播放体验的基础上，重点加入高性能网络播放、两种新的 VR180 投影模型、更快捷的 FOV 控制，以及更顺畅的追踪设置流程。

- **原生 SMB2/SMB3 播放** — 浏览网络共享、查看缩略图并排序，再通过原生 `libsmb2` 流式播放和拖动跳转大型视频；不兼容时自动回退到 `jcifs-ng`。
- **局域网 SMB 发现** — 扫描局域网中的 SMB 服务器，登录一次即可直接打开设备共享，无需输入共享名；已记住的设备以快捷按钮显示。
- **更广泛的 VR180 格式支持** — 支持半等距柱状、等距鱼眼和等立体角鱼眼内容，并可分别调节视野 FOV 与镜头 FOV。
- **更快捷的视角控制** — 单指直接拖动视角、双指缩放 FOV、使用专用按钮以 5° 步进调节 FOV，并可双击重置视角。
- **更顺畅的设置流程** — 仅在追踪确有需要时进入校准，眼镜连接后可自动启动校准，还可永久隐藏重复出现的 Full SBS 提示。
- **更安全、更易维护** — 使用 Android Keystore 加密保存的 SMB 密码，补全第三方依赖和隐私说明，并为新增策略提供单元测试。

### 相较原项目的详细改动

对比基线为原项目 `main` 分支的 [`52673c5`](https://github.com/skarian/vr2xr/commit/52673c58b376c96a19426bf2a1cff24bd0484685) 提交。

| 范畴 | 本分支的改动 |
| --- | --- |
| 视频来源 | 新增应用内 SMB2/SMB3 入口和网络共享浏览器；本地文件仍使用 Android 系统文档选择器。 |
| SMB 浏览 | 新增服务器、共享名、域和账户登录，支持文件夹导航、可播放视频过滤、缩略图、按新旧排序、保存配置及删除配置。新增局域网自动发现（含 NetBIOS 名称）和无需共享名的设备级登录。 |
| SMB 播放 | 新增带缓冲随机访问的 Media3 SMB 数据源，支持播放和拖动跳转。优先使用原生 `libsmb2` 提升吞吐，并保留 `jcifs-ng` 回退。 |
| 凭据隐私 | 保存的 SMB 密码使用 Android Keystore 中不可导出的密钥进行 AES-GCM 加密；服务器信息仅保存在应用私有存储中。 |
| 投影模式 | 在原有半等距柱状模式之外，新增 VR180 等距鱼眼和等立体角鱼眼渲染。 |
| 投影控制 | 新增鱼眼镜头 FOV 调节（`160°–220°`），将视野 FOV 范围扩展为 `25°–175°`，修复滑块步进并持久化投影设置。 |
| 触控板控制 | 用直接拖动和双指缩放 FOV 取代边缘连续自动拖动；新增按 5° 调节的 `FOV -` / `FOV +` 按钮，双击可重置视角。 |
| 追踪流程 | 眼镜连接后若不存在追踪流，会自动打开校准；选择视频后仅在必要时进入校准，随后进入 Full SBS 提示。 |
| SBS 提示 | 为 Full SBS 准备界面新增**不再显示**选项。 |
| 启动页与播放器界面 | 新增 SMB 操作入口，将需求帮助改为带文字的按钮，重新组织播放器控制区，并更新应用内项目署名。 |
| 工程维护 | 新增追踪启动策略、SMB 浏览/播放策略和投影配置的单元测试，并补充 SMB 第三方组件及隐私行为说明。 |

---

<p align="center">
  <img src="./assets/screenshots/framed/01-home-framed.png" alt="vr2sbs 主页" width="22%">
  &nbsp;
  <img src="./assets/screenshots/framed/02-calibration-framed.png" alt="vr2sbs 校准设置" width="22%">
  &nbsp;
  <img src="./assets/screenshots/framed/03-sbs-mode-framed.png" alt="vr2sbs SBS 模式设置" width="22%">
  &nbsp;
  <img src="./assets/screenshots/framed/04-player-framed.png" alt="vr2sbs 播放器控制界面" width="22%">
</p>

## 功能特性

- **支持 XREAL One**：实时头部追踪、连接状态、出厂偏差修正、重新校准、IMU 灵敏度调节，以及关闭后跳过校准的 IMU 追踪开关
- **多种视频来源**：本地文件、`http(s)` 地址、Android 分享 Intent，以及内置的 SMB2/SMB3 网络共享
- **SMB 媒体浏览器**：浏览文件夹、显示视频缩略图、按修改日期排序，并可保存多个服务器配置
- **SMB 设备发现**：扫描本地 Wi-Fi/以太网中的 SMB 服务器，解析 NetBIOS 名称，无需输入共享名即可打开设备上的共享
- **高吞吐 SMB 播放**：通过原生 `libsmb2` 实现随机读取和拖动跳转，并提供 `jcifs-ng` 兼容回退
- **多种 VR180 投影**：支持 VR180 to SBS（半等距柱状）、等距鱼眼和等立体角鱼眼投影
- **SBS 输出格式可选**：播放中可在 Half-SBS（1920×1080）与 Full-SBS（3840×1080）之间切换
- **实时投影调节**：播放过程中调节视野 FOV 和鱼眼镜头 FOV；投影模式与 SBS 输出格式会被记忆，视野 FOV 每次播放从 58° 开始
- **手机端播放控制**：播放/暂停、前后跳转 15 秒、时间轴拖动、投影控制和视角调节触控板
- **引导式追踪设置**：需要时自动进入校准，随后显示可关闭的 Full SBS 提示
- **可靠的播放会话**：眼镜输出中断时自动暂停，输出恢复后可继续播放

## 使用要求

- XREAL One 或 XREAL One Pro 眼镜
- Android 13 或更高版本（`minSdk = 33`）
- 不支持 Samsung DeX 桌面模式，请改用屏幕镜像
- 应用以 **vr2sbs**（`com.vr2sbs`）安装，可与原版 vr2xr-neo 同时安装

本应用不支持仅使用手机播放，也不会自动切换眼镜的 SBS 模式；SBS 模式仍由用户或设备控制。

## 使用指南

1. 连接 XREAL One 或 XREAL One Pro 眼镜。
2. 选择视频来源：

   - 点击 **Open file**，使用 Android 系统文件选择器；
   - 输入 `http(s)` 地址并点击 **Open URL**；
   - 点击 **Open SMB share**，打开网络共享；
   - 点击 **Scan SMB**，扫描局域网中的 SMB 设备并登录；
   - 或从其他 Android 应用将视频链接或文件分享到 vr2sbs。

3. 如果需要校准，将眼镜平放，然后点击 **Run Calibration**；不需要头部追踪时，可点击 **Disable IMU Tracking** 跳过校准。
4. 重新戴上眼镜，并将 `Display > 3D Mode` 设置为与应用的 **SBS Output** 设置一致（`Full SBS` 或 `Half SBS`）。可以选择以后不再显示此提示。
5. 点击 **Continue to VR Player**，应用会自动执行 Zero View。
6. 使用手机端控件播放、暂停、跳转、调节投影和改变视角。

如果播放期间眼镜断开，视频会暂停并等待眼镜输出恢复。

## 播放器控制

- **单指拖动**：调节偏航角和俯仰角
- **双指缩放**：连续改变视野 FOV
- **双击**：重置视角
- **FOV - / FOV +**：以 5° 为步长调节视野 FOV
- **眼镜设置**：重新校准、改变 IMU 灵敏度或启用/停用 IMU 追踪（重新启用会先进入校准）
- **投影设置**：选择投影模型并调节视野/镜头 FOV
- **播放控制行**：前后跳转 15 秒、播放/暂停或拖动时间轴

<p align="center">
  <img src="assets/readme-gifs/vr2sbs-player-2.gif" alt="使用 vr2sbs 播放器控制" width="48%">
  &nbsp;
  <img src="assets/readme-gifs/vr2sbs-player-3.gif" alt="调节 vr2sbs 投影设置" width="48%">
</p>

## SMB 网络共享

如果已经知道目标共享，点击 **Open SMB share** 并输入服务器主机名或 IP 地址，共享名可以留空（留空时会列出服务器上的全部共享）。需要身份验证时，还可以填写域、用户名和密码。

也可以点击 **Scan SMB** 自动发现：应用会扫描当前 Wi-Fi/以太网中的 SMB 设备，并在设备响应时立即显示，条件允许时显示 NetBIOS 名称。点击设备并输入用户名、密码（可选填域）后，直接进入该设备的共享列表，无需输入共享名。启用 **Remember this account securely** 可保存设备；已记住的设备显示在扫描页顶部，长按即可删除。

浏览器会显示文件夹和支持的视频，在条件允许时生成缩略图，并支持按从新到旧或从旧到新排序。视频数据由设备直接从 SMB 服务器读取，不会复制给开发者或任何中间服务。

<p align="center">
  <img src="assets/readme-gifs/vr2sbs-player-1.gif" alt="浏览并打开 SMB 网络共享中的视频" width="36%">
</p>

## 构建与测试

克隆时包含子模块，或在克隆后进行初始化：

```bash
git submodule update --init --recursive
```

使用 JDK 17 以及项目配置的 Android SDK/NDK，然后运行：

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

调试 APK 会生成在 `app/build/outputs/apk/debug/` 目录。更多开发说明见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

## 隐私与第三方组件

- 本地数据及 SMB 凭据处理方式见 [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md)。
- 内置 `libsmb2` 源码和 `jcifs-ng` 依赖说明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
- `libsmb2` 使用 LGPL-2.1-or-later 许可证；项目其余部分遵循本仓库中的相应许可证。

## 致谢

vr2xr 由 [Neil Skaria](https://github.com/skarian) 创建。本分支由 [NeoNatural](https://github.com/NeoNatural) 维护和优化。

原项目地址：[Skarian/vr2xr](https://github.com/skarian/vr2xr)；本分支地址：[NeoNatural/vr2xr-neo](https://github.com/NeoNatural/vr2xr-neo)。
