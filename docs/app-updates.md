# BYBUS 应用更新与发布

应用升级独立于游戏安装及零协汉化更新。发布仓库为 `gravere/BYBUS`。应用通过固定仓库 ID `1415176635` 查询公开的 GitHub Releases API，避免仓库改名影响检查与下载。

更新请求只接受该仓库 ID 对应的最新发行版与资源 API，以及 GitHub 的发行文件域名。界面中的网页入口使用当前仓库名称。旧版 v1.0 仅接受原仓库名称的 API 路径，改名后需覆盖安装 v1.0.1 一次。

## 更新路径

- 设置里的「更新」使用现有详情面板和返回层级，同页提供 BYBUS 与汉化更新；首页不等待此查询，游戏启动不依赖查询成功。两种更新仍使用各自的资源来源、工作线程和校验流程。
- 用户主动检查最新正式 Release。Release 附带 `bybus-update.json`，声明包名、显示版本、递增的内部版本、最低系统版本、APK 文件名、大小与 SHA-256。忽略草稿和预发布，不按显示版本 `v1.0` 判断新旧。
- 元数据与下载都有超时、大小限制；仅允许固定 GitHub API 和 GitHub 官方 Release 文件域名，不把任意返回网址当可执行文件源。
- 下载到宿主独立缓存，流式计算 SHA-256；校验完整文件、实际包名、内部版本、最低系统版本和 APK 密码学签名。签名必须与当前 BYBUS 一致。下载失败不替换现有应用，不触碰容器数据。
- 使用限于单个升级缓存目录的 FileProvider 授予临时只读权限，交给 Android 系统确认覆盖安装。不会静默安装、卸载旧版或清除数据。用户拒绝后仍可重试；进程重建后不自动发起安装。
- FileProvider 的路径必须通过 Manifest 中的 `android.support.FILE_PROVIDER_PATHS` 元数据声明；静态 `getUriForFile` 不读取子类构造函数的资源参数。发行脚本核对最终 APK 中的元数据指向和 Provider 权限，防止下载成功后无法生成安装 URI。
- 同时保留「GitHub 发行页面」入口，网络失败时展示真实原因，不把查询失败写成已是最新版。

## 发行文件与版本

| 文件 | 用途 |
| --- | --- |
| `BYBUS-<版本>-arm64-v8a.apk` | 手动下载安装包，如 `BYBUS-v1.0-arm64-v8a.apk` |
| `BYBUS.apk` | 已发布更新器使用的固定名称；与上述安装包逐字节相同 |
| `bybus-update.json` | 应用更新元数据 |
| `SHA256SUMS.txt` | 全部安装包与更新元数据的 SHA-256 |

Release 标题采用 `BYBUS <版本>`。对外版本来自 `versionName`，内部 `versionCode` 用于 Android 覆盖升级，每次发布安装包都须递增。后续正式版本使用 `v1.0.1` 等递增版本名，使下载后的文件也可区分版本。

现有更新协议严格核对 `<版本>-build.<versionCode>` 标签和 `BYBUS.apk` 名称，保留该标签格式与兼容附件。改名不能破坏旧更新器；下载说明优先链接带版本的 APK，兼容附件在 GitHub 中标注用途。本地两种文件名尽量通过硬链接共用一份内容。

## 发布流程

1. 更新版本、中文 Release 说明，完成受影响功能的验证。
2. 使用 `scripts/prepare-release.py` 生成发行文件。
3. 建立与源码提交对应的发布标签，上传全部附件到草稿 Release。
4. 核对上传文件的大小与 SHA-256，再发布为正式版本。

已发布 APK 与标签保持不变；文案与文件名调整可添加内容相同的下载别名，并更新校验清单。旧更新元数据与兼容附件必须继续有效。

构建依赖由 `scripts/fetch-build-assets.py` 固定版本、大小与 SHA-256。完整构建步骤见[构建指南](building.md)。

参考：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases)、[Release 文件接口](https://docs.github.com/en/rest/releases/assets)、[Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)。
