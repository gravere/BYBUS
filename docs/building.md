# 构建 BYBUS

## 环境

| 工具 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK | API 35 |
| Android Build Tools | 35.0.0 |
| Android NDK | 30.0.14904198 |
| Gradle | 8.10.2，由仓库 Wrapper 提供 |
| Python | 3.11 及以上 |

VirtualApp 源码已随仓库保存，无需初始化子模块。当前发行目标为 `arm64-v8a`。

## 获取源码与依赖

```sh
git clone https://github.com/gravere/BYBUS.git
cd BYBUS
python scripts/fetch-build-assets.py
```

依赖脚本下载固定版本的 microG 组件并验证 SHA-256；已有匹配文件会直接复用。

## 本地构建

设置 `JAVA_HOME` 和 `ANDROID_HOME`，使其分别指向 JDK 17 和 Android SDK。也可在根目录 `local.properties` 中设置 `sdk.dir`。

Windows PowerShell：

```powershell
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
$env:HOST_OS = 'windows'
$env:HOST_ARCH = 'x86_64'
.\gradlew.bat :app:assembleDebug
```

Linux：

```sh
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug
```

输出文件为 `app/build/outputs/apk/debug/app-debug.apk`。默认调试签名不能覆盖已安装的正式版。

## 局部回归验证

文本接管与生命周期的本地回归用例可单独运行：

```sh
./gradlew :app:testDebugUnitTest --tests '*TranslationIndexCompilerTest' --tests '*DiagnosticExportStateTest' --tests '*DeviceCompatibilityReportTest' --tests '*PendingUiStateTest'
python scripts/extract-runtime-test.py /path/to/temp
c++ -std=c++17 -I/path/to/temp scripts/native-managed-tests.cpp -o /path/to/temp/native-managed-tests
/path/to/temp/native-managed-tests
c++ -std=c++17 -I/path/to/temp scripts/native-material-tests.cpp -o /path/to/temp/native-material-tests
/path/to/temp/native-material-tests
c++ -std=c++17 scripts/native-text-tests.cpp -o /path/to/temp/native-text-tests
/path/to/temp/native-text-tests
c++ -std=c++17 -I/path/to/temp scripts/native-legacy-font-tests.cpp -o /path/to/temp/native-legacy-font-tests
/path/to/temp/native-legacy-font-tests
```

原生用例从当前运行时代码提取翻译、材质与旧字体加载函数，模拟 IL2CPP GC 接口，验证上下文、64 位引用、容量参数、材质共享与释放、字体加载失败和图集匹配边界；它不替代游戏内视觉核对。Windows 可使用 Visual Studio 的 C++ 编译器，并启用 `/std:c++17 /utf-8`。

语言 getter 与 Google 广播改动可只运行以下回归：

```sh
c++ -std=c++17 scripts/native-language-getter-tests.cpp -o /path/to/temp/native-language-getter-tests
/path/to/temp/native-language-getter-tests
c++ -std=c++17 scripts/native-integrity-tests.cpp -o /path/to/temp/native-integrity-tests
/path/to/temp/native-integrity-tests
./gradlew :app:testDebugUnitTest --tests '*ReceiverRegistrationCompatTest' --tests '*MicrogAuthUiPolicyTest' --tests '*LimbusAuthenticationCompatTest' --tests '*LimbusBillingBroadcastPolicyTest' --tests '*LimbusBillingUiPolicyTest'
```

## 签名与发行文件

在本地 `signing/keystore.properties` 配置 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`，然后运行 `:app:assembleRelease`。同一应用的覆盖升级必须使用相同签名。

只有发布新 APK 时才需要在 `app/build.gradle.kts` 设置版本；普通源码提交和文档维护不升版。`docs/release-notes.md` 只填写本次更新内容、验证范围与兼容说明，版本标题、下载地址和补丁列表由脚本生成：

```powershell
python scripts/prepare-release.py --apk app/build/outputs/apk/release/app-release.apk --output dist/release --aapt "$env:ANDROID_HOME/build-tools/35.0.0/aapt.exe"
```

脚本检查 APK 版本、签名与安装器配置，输出带版本及架构的安装包、应用更新兼容包、更新元数据、校验文件和可直接粘贴到 GitHub Release 的 `release-notes.md`。上传元数据 `deltas` 中声明的全部补丁，发行正文使用生成的说明文件。

脚本仅从当前已签名 APK 生成通用内容复用描述，嵌入 `bybus-update.json`，不依赖旧 APK，也不再逐版本生成 `.delta`。发布前应使用现存的旧正式 APK 检查跨版本重建，再验证公开下载的 Range 响应。协议与文件约定见[应用更新与发布](app-updates.md)。

更新器的局部回归：

```sh
./gradlew :app:testDebugUnitTest --tests '*selfupdate.*'
```

`scripts/apk_reuse.py --target current.apk --base previous.apk` 可查看通用描述在不同旧 APK 上的可复用内容与预计下载量，`--base` 可重复传入。该命令不检查 Android 签名，正式发行必须使用 `prepare-release.py`。历史 `apk_delta.py` 仅保留供旧格式回归。
