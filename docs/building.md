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

## 签名与发行文件

在本地 `signing/keystore.properties` 配置 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`，然后运行 `:app:assembleRelease`。同一应用的覆盖升级必须使用相同签名。

更新 `app/build.gradle.kts` 中的版本和 `docs/release-notes.md` 后，生成发行文件：

```powershell
python scripts/prepare-release.py --apk app/build/outputs/apk/release/app-release.apk --output dist/release --aapt "$env:ANDROID_HOME/build-tools/35.0.0/aapt.exe"
```

脚本检查 APK 版本、签名与安装器配置，输出带版本及架构的安装包、应用更新兼容包、更新元数据和校验文件。文件约定与发布流程见[应用更新与发布](app-updates.md)。
