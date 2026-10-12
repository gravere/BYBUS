# BYBUS

Android《边狱巴士》简体中文启动器，使用[都市零协会](https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany)汉化资源。

[下载 APK](https://github.com/gravere/BYBUS/releases/latest) · [使用说明](#开始使用) · [问题反馈](https://github.com/gravere/BYBUS/issues) · [构建指南](docs/building.md)

## 功能

- **中文游戏体验**：在独立容器中运行游戏，保存汉化资源与配置，无需 Root。
- **汉化自动更新**：每次启动前检查零协发布的汉化包，已下载内容可持续使用。
- **统一更新入口**：检查 BYBUS、游戏与汉化更新，支持多个游戏安装渠道。
- **翻译项目跟踪**：查看零协 GitHub 更新状态，提供 ParaTranz 项目入口。
- **手机与平板布局**：适配竖屏、横屏与分屏窗口。

## 开始使用

当前游戏运行方案支持 **Android 16（API 36）、64 位 ARM（arm64-v8a）、4 KB 内存页设备**，并需已安装官方《边狱巴士》。游戏构建仍须通过应用内兼容检查；其他系统和 16 KB 内存页设备暂不支持运行游戏。

1. 从 [Releases](https://github.com/gravere/BYBUS/releases/latest) 下载 `BYBUS-v1.0.3-arm64-v8a.apk` 并安装。
2. 打开 BYBUS，点击「启动游戏」，按提示导入已安装的游戏。
3. 首次进入时登录账号并下载游戏资源；完成后返回 BYBUS，再启动一次以应用汉化。
4. 此后从 BYBUS 启动游戏即可。

BYBUS 与系统原版的数据独立，首次使用需要重新登录和下载资源。保留系统里的游戏安装包即可，大型资源可只在 BYBUS 内下载。

## 更新

| 更新内容 | 操作入口 |
| --- | --- |
| BYBUS | 设置与工具 → 更新 → 检查应用更新 |
| 游戏 | 设置与工具 → 游戏更新 |
| 汉化 | 启动前自动检查，也可在「更新」中手动检查 |

手动更新 BYBUS 时，下载最新 APK **覆盖安装**即可保留数据。卸载应用或清除数据会删除容器内的资源和配置。各版本变化见 [Release 说明](https://github.com/gravere/BYBUS/releases)。

## 反馈与贡献

遇到闪退、漏译或显示问题，请在 [Issues](https://github.com/gravere/BYBUS/issues/new/choose) 选择对应模板，提供设备型号、Android 版本、BYBUS 与游戏版本，以及复现步骤。运行问题可附上「设置与工具 → 导出诊断」生成的诊断包。

欢迎提交改进建议和 Pull Request。参与开发请阅读[构建指南](docs/building.md)与[架构说明](docs/project-architecture.md)。

## 致谢

感谢以下项目及其贡献者：

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
