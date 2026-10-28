# 云端运行检查

`Android runtime inspection` 工作流在公开仓库的标准 Ubuntu runner 上运行，使用 Android SDK 官方镜像和 KVM。单次最多 20 分钟；截图、页面结构和诊断只保留 1 天，不上传 APK 副本。

在 GitHub Actions 中手动运行即可。工作流读取最新正式 Release，验证完整 APK 的大小、SHA-256 和固定 BYBUS 签名，再安装原始发行包。无需上传签名密钥、Google 账号或游戏资源。

检查内容：

- Android 用户解锁和系统服务就绪。
- 正式 APK 安装及首页前台状态。
- 首页、翻译项目、设置三页的原始 PNG。
- 游戏更新二级菜单返回设置页。

`report.json` 记录实际发行版本、APK 摘要、系统 ABI、页大小、Native Bridge 及已通过项目。失败会保留原因，不把模拟器启动标志当作应用运行成功。截图必须经人工核对后才能放入 README，不会自动发布。

Android 11 x86_64 的 ARM 翻译环境只用于运行基线，不代表 ARM64 原生设备或 16 KB 兼容性。该工作流不操作真实商品或付款，不证明 Google 登录、结算、扣款或到账成功。支付策略单元测试、Android 回调测试和真实购买应分别报告。
