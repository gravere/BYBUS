# BYBUS 应用更新与发布

应用升级独立于游戏安装及零协汉化更新。固定发布仓库为 `gravere/LimbusZhLauncher`，公开读取 GitHub Releases，无 Token、账号或可配置镜像地址；不把开发机器的凭据写入应用。

## 更新路径

- 设置里的「应用更新」使用现有详情面板和返回层级；首页不等待此查询，游戏启动不依赖查询成功。
- 用户主动检查最新正式 Release。Release 附带 `bybus-update.json`，声明包名、显示版本、递增的内部版本、最低系统版本、APK 文件名、大小与 SHA-256。忽略草稿和预发布，不按显示版本 `v1.0` 判断新旧。
- 元数据与下载都有超时、大小限制；仅允许固定 GitHub API 和 GitHub 官方 Release 文件域名，不把任意返回网址当可执行文件源。
- 下载到宿主独立缓存，流式计算 SHA-256；校验完整文件、实际包名、内部版本、最低系统版本和 APK 密码学签名。签名必须与当前 BYBUS 一致。下载失败不替换现有应用，不触碰容器数据。
- 使用限于单个升级缓存目录的 FileProvider 授予临时只读权限，交给 Android 系统确认覆盖安装。不会静默安装、卸载旧版或清除数据。用户拒绝后仍可重试；进程重建后不自动发起安装。
- 同时保留「GitHub 发行页面」入口，网络失败时展示真实原因，不把查询失败写成已是最新版。

## 发布边界

- 源码仓库不保存可从官方重建的 microG APK。`scripts/fetch-build-assets.py` 固定上游 Release、文件大小和 SHA-256，构建前获取并拆分；本机已有匹配文件直接复用。正式 APK 继续内置完全相同的组件，不增加手机首次运行的可执行依赖下载。

- 当前用户要求公开发布。上传前审查当前源码和发行附件中的凭据、个人账号、设备信息和本机路径。首次公开发布使用审查后的当前源文件树，独立创建公开发布历史，避免把本地调试历史带入公开仓库；本地开发历史保持原样。
- Release 附件包含签名 `BYBUS.apk`、`bybus-update.json` 和 `SHA256SUMS.txt`；发布说明使用中文。上传完成并核对 SHA-256 后才将草稿转为正式版本。
- 私钥、签名密码、诊断包、设备截屏、账号数据、原版游戏资源及临时文件均不进入仓库或发行附件。
- 显示版本保持 `v1.0`，内部版本递增；首次带应用更新功能的版本为 41。发布标签 `v1.0-build.41` 唯一绑定本次源码与安装包，后续构建不得覆盖同一标签的已发布文件。

参考：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases)、[Release 文件接口](https://docs.github.com/en/rest/releases/assets)、[Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)。
