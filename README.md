# BYBUS

面向 Android 的《边狱巴士》简体中文启动器。基于 [limbusZhCN](https://github.com/BBBEANNN/limbusZhCN) 与 VirtualApp，使用[都市零协会](https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany)汉化资源。

[下载正式安装包](https://github.com/gravere/LimbusZhLauncher/releases/latest) · [提交问题](https://github.com/gravere/LimbusZhLauncher/issues) · [第三方组件](THIRD_PARTY_NOTICES.md)

## 安装与使用

1. 在手机安装官方《边狱巴士》，保留这份系统安装包。支持识别不同渠道安装的官方签名版本。
2. 在本仓库的 **Releases** 下载 `BYBUS.apk` 并安装；已有 BYBUS 的用户直接覆盖安装。
3. 打开 BYBUS，点击「启动游戏」。首次会导入系统安装包，在容器内登录并下载游戏资源。
4. 资源下载完成后返回 BYBUS，再启动一次，应用会建立并保存汉化索引。以后从 BYBUS 进入游戏即可。

需要 ARM64 设备和 Android 8.0 或更高版本，无需 Root、LSPosed 或 Shizuku。不同系统、游戏版本的兼容性仍可能不同。

**不要卸载 BYBUS 或清除其数据来更新。** 容器单独保存账号、资源与汉化；覆盖安装保留这些数据，卸载或清除数据会删除。系统原版和容器的数据目录彼此隔离，因此首次在 BYBUS 内需要重新登录、下载资源。建议大资源只在 BYBUS 内下载，保留系统原版的安装包即可。

## 三种更新

| 更新对象 | 入口 | 行为 |
| --- | --- | --- |
| BYBUS 应用 | 设置与工具 → 更新 → 检查应用更新 | 检查本仓库最新正式 Release，下载安装包，校验后交给系统确认覆盖安装 |
| 游戏本体 | 设置与工具 → 游戏更新 | 查看多个渠道的版本与安装入口，也可选择完整本地安装包；返回后识别系统版本并同步 |
| 零协汉化 | 启动前自动检查，或设置与工具 → 更新 → 检查汉化更新 | 获取零协公开 Release，校验文件并复用持久索引；已有有效缓存时支持网络失败回退 |

应用更新不会要求 GitHub Token。它核对 APK 的 SHA-256、实际包名、递增版本号和签名，拒绝其他签名的安装包。检查失败会显示原因，不会误报「已是最新版本」。也可以打开 [GitHub 发行页面](https://github.com/gravere/LimbusZhLauncher/releases/latest)手动下载。

显示版本目前为 **v1.0**；内部构建号持续递增，用于可靠区分更新。当前构建 **43** 调整菜单图标：升级、游戏、同步、应用与汉化各自采用独立图形，并修复应用更新调用系统安装器的配置。**构建 41 / 42 用户请先从 GitHub 下载构建 43 手动覆盖安装一次**；旧版可以下载更新，但无法打开安装器。设置页显示当前构建号。

## 功能

- 从容器启动游戏，保存汉化索引，不修改官方 APK 或游戏原始 JSON。
- 每次启动检查游戏与汉化状态；并行获取汉化发布信息，已有文件的校验结果在单次操作中复用，等待游戏实际打开前保持「正在启动」。
- 默认显示零协 GitHub 更新状态，也提供 ParaTranz 项目跟踪入口；上游不可访问时如实显示状态。
- 使用零协所用的中文字体，按译文来源接管字形、材质与排版，持续完善复杂页面的显示。
- 手机、平板横屏和分屏布局；设置详情共用面板高度和返回逻辑。
- 支持容器内 Google 账号与计费环境。已验证商品显示、结算页打开及取消返回；**实际扣款和到账尚未验证**。

已验证代码结构的后续游戏版本可以自动检查并适配；遇到未知保护结构仍会提示兼容性问题。不能保证任意未来游戏版本无需修改即可运行。翻译覆盖也取决于零协发布内容和游戏文本结构，不宣称所有场景均已核对。

## 反馈问题

在「设置与工具 → 导出诊断」生成诊断包，提交问题时写明 BYBUS 构建号、Android 版本、游戏版本和复现步骤。诊断包会进行有限脱敏，仍请先自行检查，再决定是否公开上传。不要上传账号密码、付款凭据或整个游戏数据目录。

## 从源码构建

环境：JDK 17、Android SDK 35、Build Tools 35.0.0、NDK `30.0.14904198`、Gradle 8.10.2。容器源码已随仓库保存，无需另行初始化子模块。

先安装 Python 3.10 或更高版本，运行 `python scripts/fetch-build-assets.py` 获取固定版本的 microG 构建组件。脚本核对大小与 SHA-256，已有匹配文件直接复用。组件会编入 APK，手机首次运行无需另行下载这些依赖。

```powershell
$env:JAVA_HOME = '你的 JDK 17 路径'
$env:ANDROID_HOME = '你的 Android SDK 路径'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
$env:HOST_OS = 'windows'
$env:HOST_ARCH = 'x86_64'
.\gradlew.bat :app:assembleRelease
```

正式签名通过本地 `signing/keystore.properties` 配置 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`。该目录已忽略，不会进入仓库。未配置签名时不能生成可覆盖 BYBUS 发行版的安装包；自行签名的构建也不能覆盖另一密钥签名的版本。

发布前递增 `app/build.gradle.kts` 中的 `versionCode`，更新 `docs/release-notes.md`，再生成发行附件：

```powershell
python scripts/prepare-release.py --apk app/build/outputs/apk/release/app-release.apk --output dist/release --aapt "$env:ANDROID_HOME/build-tools/35.0.0/aapt.exe"
```

输出 `BYBUS.apk`、`bybus-update.json`、`SHA256SUMS.txt`。使用 `v1.0-build.<构建号>` 建立正式 Release，上传全部文件并核对校验值后发布。签名私钥只保存在维护者本机，不上传仓库，也不内置到 APK。

## 项目说明

- [应用更新与发布](docs/app-updates.md)
- [架构与数据流](docs/project-architecture.md)
- [多渠道游戏更新](docs/multichannel-game-updates.md)
- [自动兼容边界](docs/automatic-native-compatibility.md)
- [运行时文本处理](docs/runtime-text-and-478.md)
- [协作约定](AGENTS.md)

首次公开提交为清理后的当前源码快照，不包含开发机器的 Git 调试历史、诊断包或签名材料。上游来源和版权说明保留。

## 来源与致谢

BYBUS 建立在以下项目和贡献者的工作之上。感谢他们开放源码、维护兼容能力、制作中文翻译与字体。

| 项目 | 在 BYBUS 中的作用 |
| --- | --- |
| [BBBEANNN / limbusZhCN](https://github.com/BBBEANNN/limbusZhCN) | 基础项目及最初的 Android 汉化、容器接入工作；BYBUS 在其基础上继续修改 |
| [都市零协会 / LocalizeLimbusCompany](https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany) | 简体中文翻译、汉化发布资源及所使用的中文字体来源；感谢译者、校对和维护者持续更新 |
| [ServenScorpion / VirtualApp](https://github.com/ServenScorpion/VirtualApp) | 本仓库固定引入的 Android 容器源码；同时感谢 VirtualApp 原项目及相关贡献者 |
| [microG / GmsCore](https://github.com/microg/GmsCore) | 容器中使用的 Google 服务兼容组件与 Companion |
| [be5invis / Sarasa Gothic](https://github.com/be5invis/Sarasa-Gothic) | 游戏中文显示使用的更纱黑体（Sarasa Gothic SC Bold） |
| [Android Open Source Project](https://source.android.com/) 与 [AndroidX](https://developer.android.com/jetpack/androidx) | Android 基础代码、apksig 签名验证与 Jetpack Compose 等界面组件 |

以上项目保持各自的版权和许可证，列名不代表其作者参与或认可 BYBUS。固定版本、来源记录和许可文本见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，VirtualApp 引入记录见 [UPSTREAM.txt](third_party/virtualapp-upstream/UPSTREAM.txt)。

本项目为非官方社区工具，与 Project Moon 无隶属或背书关系。项目原创代码采用 [GPL v3](LICENSE)；VirtualApp、microG、字体等依各自条款，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。中文翻译版权归都市零协会及其相应权利人。
