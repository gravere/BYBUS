# BYBUS

Android《边狱巴士》简体中文启动器，使用[都市零协会](https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany)汉化资源。无需 Root，在独立容器中运行官方游戏，启动前同步汉化，日常从 BYBUS 进入即可。

[下载完整 APK](https://github.com/gravere/BYBUS/releases/latest/download/BYBUS.apk) · [发行说明](https://github.com/gravere/BYBUS/releases/latest) · [开始使用](#开始使用) · [问题反馈](https://github.com/gravere/BYBUS/issues) · [构建指南](docs/building.md)

## 亮点

- **免 Root，保留官方安装包**：导入设备上已安装的官方游戏，在容器内接管中文显示，不修改游戏 APK 或原始文本文件。
- **汉化持续同步**：启动前检查零协汉化更新，复用已校验的缓存；汉化索引与配置持久保存，重启后无需重新配置。
- **减少重复下载**：系统保留游戏安装包，大型游戏资源可只在 BYBUS 内下载。BYBUS 自身更新匹配到差异补丁时优先增量下载，无匹配补丁时使用完整包。
- **多渠道游戏更新**：导入不绑定 Google Play；提供 Google Play、Uptodown 等更新入口，也支持本地安装包。更新游戏安装文件时保留容器资源与账号数据。
- **零协项目动态**：默认展示 GitHub 更新状态，可切换 ParaTranz 查看可用统计或前往项目页面；接口不可用时明确提示。
- **手机与平板自适应**：竖屏单列顺序浏览，横屏在空间足够时采用双列，并根据分屏窗口和系统字体大小调整布局。

## 开始使用

准备一台 **Android 8.0 及以上、64 位 ARM（arm64-v8a）的手机或平板**，并先安装官方《边狱巴士》。具体兼容范围见下方说明。

1. [下载最新完整 APK](https://github.com/gravere/BYBUS/releases/latest/download/BYBUS.apk) 并安装。
2. 打开 BYBUS，点击「启动游戏」，按提示导入已安装的游戏。
3. 首次进入时登录账号并下载游戏资源；完成后返回 BYBUS，再启动一次以启用完整汉化。
4. 此后从 BYBUS 启动游戏即可。

BYBUS 与系统原版的数据独立，首次使用需要重新登录和下载资源。日常保留系统里的官方游戏安装包，并从 BYBUS 启动游戏；无需在两边都下载和维护完整游戏资源。游戏更新新增的资源仍需正常下载。

首次下载资源前，可先准备启动、登录和提示文本的汉化；完整汉化需要游戏资源下载完成。Google 登录使用容器内的独立账号环境，新设备需添加账号，不能直接沿用系统 Play 商店的登录状态。

### 发行文件怎么选

| 文件 | 用途 |
| --- | --- |
| `BYBUS-<版本>-arm64-v8a.apk` 或 `BYBUS.apk` | 内容相同的完整安装包，首次安装或手动更新任选一个 |
| `.delta` | 应用自动处理的差异补丁，不能独立安装 |
| `bybus-update.json`、`SHA256SUMS.txt` | 更新元数据与校验值，无需手动安装 |
| Source code | 项目源码，不是安装包 |

## 更新

| 更新内容 | 操作入口 |
| --- | --- |
| BYBUS | 设置与工具 → 更新 → 检查应用更新 |
| 游戏 | 设置与工具 → 游戏更新 |
| 汉化 | 启动前自动检查，也可在「更新」中手动检查 |

手动更新 BYBUS 时，下载最新 APK **覆盖安装**即可保留数据。卸载应用或清除数据会删除容器内的资源和配置。各版本变化见 [Release 说明](https://github.com/gravere/BYBUS/releases)。

应用内更新会在已安装 APK 与发行补丁匹配时优先下载差异内容，自动合成并校验完整 APK，再打开系统安装页面；无匹配补丁或合成失败时使用完整包。增量更新减少下载流量，合成与安装仍需完整 APK 的临时空间。

如果旧安装包没有增量更新能力，或应用内更新不可用，直接下载最新完整 APK 覆盖升级即可。

Releases 保留最近三个正式构建的发行文件。

## 兼容范围

BYBUS 面向 Android 手机和平板，不限定品牌。启动前检查系统与处理器条件、实际 4 KB / 16 KB 页大小、官方游戏签名及原生结构；32 位系统、x86、Android 8.0 以下和未识别的游戏保护结构不支持。

同结构的游戏更新可自动适配，新的保护结构仍可能需要更新 BYBUS。汉化覆盖也取决于零协已发布的译文和设备上已下载的游戏资源；设备条件通过不等于所有页面或功能均已实测。

内置 Google 组件仍有部分 4 KB 原生库，16 KB 设备上的 Google 登录和付款兼容性尚未确认。真机覆盖情况和技术边界见[Android 适配说明](docs/android-runtime-portability.md)。

## 反馈与贡献

遇到闪退、漏译或显示问题，请在 [Issues](https://github.com/gravere/BYBUS/issues/new/choose) 选择对应模板，提供设备型号、Android 版本、BYBUS 与游戏版本，以及复现步骤。运行问题可附上「设置与工具 → 导出诊断」生成的诊断包。

欢迎提交改进建议和 Pull Request。参与开发请阅读[构建指南](docs/building.md)与[架构说明](docs/project-architecture.md)。

## 致谢

感谢以下项目及其贡献者，也感谢 [UMP40](https://github.com/UMP40) 对原生运行时与 Google 登录兼容的代码贡献。

| 项目 | 贡献 |
| --- | --- |
| [BBBEANNN / limbusZhCN](https://github.com/BBBEANNN/limbusZhCN) | BYBUS 的基础项目，提供 Android 汉化与容器接入实现 |
| [都市零协会 / LocalizeLimbusCompany](https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany) | 简体中文翻译、校对、汉化资源与持续维护 |
| [ServenScorpion / VirtualApp](https://github.com/ServenScorpion/VirtualApp) | Android 应用容器；同时感谢 VirtualApp 原项目的贡献者 |
| [microG / GmsCore](https://github.com/microg/GmsCore) | Google 服务兼容组件与 Companion |
| [be5invis / Sarasa Gothic](https://github.com/be5invis/Sarasa-Gothic) | 更纱黑体，游戏中文字体来源 |
| [Android Open Source Project](https://source.android.com/) 与 [AndroidX](https://developer.android.com/jetpack/androidx) | Android 基础组件、签名验证与界面框架 |

## 许可证

项目原创代码采用 [GPL v3](LICENSE)。第三方组件和翻译保留各自版权与许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

BYBUS 是非官方社区项目，与 Project Moon 无隶属关系。
