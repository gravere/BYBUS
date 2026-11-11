<p align="center">
  <img src="docs/assets/brand-mark.svg" width="112" height="112" alt="BYBUS 应用图标">
</p>

<h1 align="center">BYBUS</h1>

<p align="center">
  安卓/Android《边狱巴士》简体中文启动器<br>
  免 Root · 不绑定特定加速器 · 首次进入即加载汉化
</p>

<p align="center">
  <a href="https://github.com/gravere/BYBUS/releases/latest"><img src="https://img.shields.io/github/v/release/gravere/BYBUS?style=flat&amp;label=release&amp;color=254B40" alt="最新发行版"></a>
  <a href="#开始使用"><img src="https://img.shields.io/badge/Android-8.0%2B-254B40?style=flat&amp;logo=android&amp;logoColor=white" alt="最低系统要求：Android 8.0"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-254B40?style=flat" alt="原创代码许可证：GPL v3"></a>
</p>

<p align="center">
  <a href="https://github.com/gravere/BYBUS/releases/latest/download/BYBUS-Android.apk"><strong>下载完整 APK</strong></a>
  &nbsp;·&nbsp;
  <a href="#开始使用">开始使用</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/gravere/BYBUS/releases/latest">发行说明</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/gravere/BYBUS/issues/new/choose">问题反馈</a>
</p>

---

BYBUS 使用[都市零协会](https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany)的简体中文汉化资源。**先在手机或平板上安装官方《边狱巴士》，安装好后不用打开原版，直接打开 BYBUS，跟着提示操作即可。** 汉化准备、游戏登录和首次资源下载都从 BYBUS 完成，以后也从 BYBUS 启动游戏。

## 界面预览

<p align="center">
  <img src="docs/assets/screenshots/home.png" width="30%" alt="BYBUS 首页">
  &nbsp;
  <img src="docs/assets/screenshots/project.png" width="30%" alt="都市零协会项目动态">
  &nbsp;
  <img src="docs/assets/screenshots/settings.png" width="30%" alt="设置与工具">
</p>

## 功能特点

- **装好原版，直接用 BYBUS** — 登录和首次资源下载都在 BYBUS 启动的游戏里完成，大资源只需下载一遍。
- **不绑定特定加速器** — 自备能访问游戏服务器、GitHub 和所用登录服务的网络即可，无需开通加速器会员。BYBUS 本身不提供网络加速。
- **免 Root，免手动替换文件** — 无需修改游戏安装包，也不用反复复制汉化文件，准备过程由 BYBUS 完成。
- **首次下载资源时就有中文** — 进入游戏前先准备都市零协会正式发布的汉化包，资源下载后，已有译文的内容直接显示中文，不用退出重进。
- **汉化自动跟进** — 每次启动前检查都市零协会的正式发布，有更新才下载，否则继续使用本地已有的汉化包。
- **更新和修复保留已有数据** — BYBUS 支持应用内增量更新；汉化异常可一键修复，保留账号与已下载的游戏资源。

## 开始使用

需要 **Android 8.0 及以上、64 位 ARM（arm64-v8a）的手机或平板**，以及能连接游戏服务器、GitHub 和所用登录服务（如 Google）的网络。BYBUS 不提供网络加速。

1. **安装原版游戏** — 在设备上安装官方《边狱巴士》。安装完成后不用打开它，也不用在原版里下载资源。
2. **安装并打开 BYBUS** — [下载完整 APK](https://github.com/gravere/BYBUS/releases/latest/download/BYBUS-Android.apk) 并安装，打开后按首页提示操作。
3. **进入游戏** — BYBUS 会识别原版、准备汉化并启动游戏。在这个游戏窗口中登录账号、完成首次资源下载，即可游玩。
4. **以后直接从 BYBUS 启动** — 不要卸载已安装的原版；官方发布新版本时仍需[更新原版](#更新)。汉化更新由 BYBUS 在启动前自动检查。

> [!NOTE]
> 按以上步骤，首次游戏资源只需在 BYBUS 中下载一遍。如果你以前已在原版游玩，原版的资源与登录状态不会自动带入 BYBUS，需要在 BYBUS 内重新登录并下载资源。

使用 Google 登录时，可以直接在游戏登录界面选择 Google，按提示添加或选择账号；也可以提前在「设置与工具 → Google 账号与付款」中添加。手机系统里已登录的 Google 账号不会自动出现在 BYBUS 中，需要在 BYBUS 里添加一次，可以使用同一个账号。商店与付款服务默认开启。

<details>
<summary><strong>发行文件怎么选？首次安装请选择完整 APK</strong></summary>

| 文件 | 用途 |
| --- | --- |
| `BYBUS-Android.apk` | 完整安装包，首次安装和手动更新都选它 |
| `BYBUS.apk` | 旧 BYBUS 的一次性迁移衔接更新，覆盖旧版并保留数据；首次安装不要选它 |
| `bybus-app-update.json`、`bybus-update.json`、`SHA256SUMS.txt` | 更新描述与校验值，无需手动安装 |
| Source code | 项目源码，不是安装包 |

</details>

## 更新

| 更新内容 | 操作入口 |
| --- | --- |
| BYBUS 应用本身 | 设置与工具 → 应用更新 |
| 原版游戏 | 设置与工具 → 游戏更新，按页面提示更新原版 |
| 汉化 | 启动前自动检查，也可在「设置与工具 → 汉化设置 → 汉化资源」中手动检查 |

手动更新时，下载最新完整 APK **覆盖安装**即可保留数据；应用内更新不可用时也可采用此方式。卸载 BYBUS，或在手机系统设置里清除它的应用数据，会删除 BYBUS 内的游戏资源、本地登录信息和设置。

遇到汉化文件或字体加载异常，可打开「设置与工具 → 修复」。修复时会关闭游戏，保留账号和已下载的游戏资源，完成后从首页重新启动。

需要释放空间时，可使用 **BYBUS 内的「设置与工具 → 清除数据」**，确认后清理已下载的游戏资源与缓存。这个操作会保留账号，下次进入游戏需要重新下载资源。

<details>
<summary><strong>已有旧版 BYBUS，如何保留数据并迁移？</strong></summary>

1. **保留旧版，不要卸载或清除数据。** 在旧版中检查更新并完成一次覆盖更新；没有更新入口时，可安装[旧版衔接更新](https://github.com/gravere/BYBUS/releases/latest/download/BYBUS.apk)，保留数据并开放迁移入口。
2. 从[最新发行页](https://github.com/gravere/BYBUS/releases/latest)下载 `BYBUS-Android.apk`，安装新版 BYBUS。
3. 在新版「设置与工具 → 数据迁移」中，确认识别到旧版后点击「开始迁移」，无需手动导出文件。确认新版正常后，再卸载旧版。

迁移需要额外空间，会替换新版的同名数据；旧数据保留，账号可能需要重新授权。最新发行始终提供衔接入口，无需逐版升级。

</details>

<details>
<summary><strong>如何导入已有资源？</strong></summary>

在「设置与工具 → 数据迁移 → 选择资源文件」中选择兼容格式的资源 ZIP。只补充缺少的资源，保留已有文件，不迁入账号；资源版本可以不同，由游戏补齐更新。来源应用需提供兼容导出功能，不能直接读取任意应用的私有数据。

</details>

<details>
<summary><strong>增量更新如何工作？</strong></summary>

应用内更新复用当前 APK 中未变化的内容，下载差异后合成并校验完整 APK，再交给系统安装。支持通用增量的旧版本可直接更新，无需逐版下载，也不依赖历史发行文件。

不能复用或分段下载失败时会使用完整包；安装仍需完整 APK 的临时空间。较早的更新器需先完整升级一次。**首次安装和手动更新始终下载完整 APK，无需寻找增量附件。**

</details>

各版本变化见[发行说明](https://github.com/gravere/BYBUS/releases)。Releases 保留最近三个正式构建的发行文件。

## 文档与贡献

| 文档 | 内容 |
| --- | --- |
| [构建指南](docs/building.md) | 本地环境、编译与打包 |
| [架构说明](docs/project-architecture.md) | 模块职责与运行流程 |
| [Android 适配说明](docs/android-runtime-portability.md) | 设备条件与已知限制 |
| [第三方声明](THIRD_PARTY_NOTICES.md) | 组件来源、版权与许可 |

欢迎提交建议、[问题反馈](https://github.com/gravere/BYBUS/issues/new/choose)和 Pull Request。反馈表按启动、文字显示、账号与商店、更新与数据、界面和功能建议分组。选择对应入口，说明问题出现在哪一步；截图和诊断包可按需补充。

## 致谢

感谢以下项目及其贡献者，也感谢 [UMP40](https://github.com/UMP40) 对原生运行时与 Google 登录兼容的代码贡献。

| 项目 | 贡献 |
| --- | --- |
| [BBBEANNN / limbusZhCN](https://github.com/BBBEANNN/limbusZhCN) | BYBUS 的基础项目，提供 Android 汉化与容器接入实现 |
| [都市零协会 / LocalizeLimbusCompany](https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany) | 简体中文翻译、校对、汉化资源与持续维护；项目图标使用其官方 GitHub 头像 |
| [ServenScorpion / VirtualApp](https://github.com/ServenScorpion/VirtualApp) | Android 应用容器；同时感谢 VirtualApp 原项目的贡献者 |
| [microG / GmsCore](https://github.com/microg/GmsCore) | Google 服务兼容组件与 Companion |
| [be5invis / Sarasa Gothic](https://github.com/be5invis/Sarasa-Gothic) | 更纱黑体，游戏中文字体来源 |
| [Android Open Source Project](https://source.android.com/) 与 [AndroidX](https://developer.android.com/jetpack/androidx) | Android 基础组件、签名验证与界面框架 |

## 许可证

项目原创代码采用 [GPL v3](LICENSE)。第三方组件和翻译保留各自版权与许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

BYBUS 是非官方社区项目，与 Project Moon 无隶属关系。
