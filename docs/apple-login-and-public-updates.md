# Apple 登录与公共更新查询

## 登录边界

Apple 登录由游戏现有 Firebase OAuth 发起，BYBUS 不注册替代 OAuth 客户端、不接收密码、不更改游戏客户端标识或证书。容器只桥接已核实的官方浏览器授权流程与回调。回调必须绑定本次游戏会话，限定 scheme、host、路径和 Firebase 所需字段；不复制任意 Bundle、Binder、组件或浏览器 flags。Firebase 继续验证事件、加密结果与账号状态。日志不输出授权 URL、结果、令牌或用户信息。

游戏构建 494 的 `GenericIdpActivity` 先解析无 data 的 ACTION_VIEW，再启动官方 handler；返回读取 `link/eventId/encryptionEnabled/firebaseError` extras。`apn` 同时参与生成浏览器回跳目标，不能换成 BYBUS 包名。专用、非导出的 WebView 授权窗口保留原 URL，截获发往原版包的回跳，仅在已绑定事件且顶层为原 Firebase handler 时传递上述有界字段。没有 JavaScript bridge、任意外部跳转或网页证书豁免。真实 Apple 账号授权需要另行验收。

## 公共查询

匿名 GitHub REST 配额按公网出口 IP 共享。仅在每台设备上降频不能解决用户数量增长和共用代理造成的耗尽。

- 应用版本与 APK 地址从正式发行的公开更新清单取得，下载使用固定版本的网页资产地址，保留文件长度、SHA-256、包名、版本和签名验证，不再依赖 releases/latest 或 assets REST。
- 零协正式版本由仓库工作流集中获取并验证，只发布版本、发布时间、确切资源名/URL/摘要至 `updates` 分支。客户端读取静态 JSON，不携带 GitHub 凭据。
- 首页与启动使用同一份缓存和串行查询。短期重复请求合并，缓存跨进程重启保留；错误不清除可用译包。ETag 条件请求减少传输，但不把匿名 304 当作免配额。
- 静态源与旧 API 的限流状态分离，旧版本的等待截止时间不能阻断新地址。遵守新地址自己的 Retry-After，失败使用有界退避。
- 工作流失败保留最后成功的元数据；超过新鲜度窗口不得显示已是最新。首次联网失败仍明确提示，不能用未知包或失效数据冒充最新版本。

工作流每小时的 7/27/47 分钟触发，发布时间以 GitHub Actions 实际调度为准；调度可能延迟。静态信息最多接受 6 小时内的同步时间。客户端短时间重复检查使用 30 秒 HTTP 缓存，零协主页与启动复用 5 分钟的验证结果，过期后再查询。普通失败最少等待 1 分钟并逐步退避；明确限流遵守服务端更晚的截止时间。应用升级移除旧 REST 等待键和零协旧 `.retry` 文件，只清理接口状态，不清理译包、游戏数据或有效快照。ParaTranz 和用户自定义下载链接独立处理各自的网络状态。

登录返回、异常回调、重复查询、缓存重启、静态数据失效和更新完整性分别验证。模拟验证与真实 Apple 账号登录明确区分。

## Activity 回调与恢复顺序

Firebase Auth 23.2.0 的 `GenericIdpActivity.onNewIntent` 只保存 Intent，实际的结果解密、事件校验和本地广播发生在 `onResume`。Android 12 起，`ActivityThread.handleNewIntent` 接收 `ActivityClientRecord`，旧的 `(IBinder, List)` 镜像不再适用；直接调用该方法还会漏掉标准的 pause/newIntent/resume 顺序。

容器在 Android 9+ 通过系统 `ClientTransactionHandler.executeTransaction` 执行 `NewIntentItem`，由原生命周期执行器恢复页面；按实际方法签名支持 Android 15 的 token-on-item 和 Android 16 的构造器入口。旧系统保留原完整 `performNewIntents` 调用。任务先排入结果再移到前台，避免先恢复旧 Intent 导致 SDK 取消授权。找不到目标或平台接口时显式记录失败，不能静默当成送达，不修改 Firebase 会话或凭据。

回归必须覆盖目标已恢复、目标处于后台、返回后 SDK 风格的 onResume 消费，以及失效 token；仅验证 Intent 内容不足以证明路由正确。

诊断 #69 同时记录到容器服务死亡后，应用信息、运行进程及账号枚举仍命中失效 Binder。上述只读查询遇到 `DeadObjectException` 时清除客户端引用并重新获取一次，第二次失败仍抛出真实错误；不重放登录、购买、安装或其他写操作，不把空结果伪装成查询成功。进程初始化与授权返回增加不含 Intent、URL 或账号的有限阶段记录，避免系统清空 logcat 后只剩笼统的启动 -1。

手动刷新须贯穿界面、仓库和公共缓存：跳过成功缓存及本地网络错误退避，仍遵守当前地址明确返回的限流截止时间。自动检查继续合并与退避，不能用上次失败冒充一次新的联网检查。汉化资源的手动更新也走同样入口。

Apple 授权窗口独立允许登录所需 Cookie；仅跟踪允许的顶层导航，不读取 Cookie 或网页内容。回跳判定以已观察到的官方 handler 请求为准，不只依赖 WebView 尚未更新的 getUrl；结果仍须绑定原事件并交还原 Firebase SDK 验证。取消、网页故障和无效结果必须有可返回的状态，不能静默吞掉回跳。

microG 添加账号可能调用 finishAndRemoveTask。若 LoginActivity 是当前游戏/登录任务中的子页面，只结束该登录页并保留真实账号回调及父页面；独立任务的退出行为不变。Android 12+ 的 ActivityClient 和旧 ActivityManager 路径须共用同一判断，不修改账号结果或自动创建账号。

参考：[Firebase Android Apple 登录流程](https://firebase.google.com/docs/auth/android/apple)、
[WebViewClient 回调边界](https://developer.android.com/reference/android/webkit/WebViewClient)、
[每个 WebView 的第三方 Cookie 设置](https://developer.android.com/reference/android/webkit/CookieManager#setAcceptThirdPartyCookies(android.webkit.WebView,%20boolean))，
以及固定版本 microG 的 [LoginActivity](https://github.com/microg/GmsCore/blob/v0.3.17.252432/play-services-core/src/main/java/org/microg/gms/auth/login/LoginActivity.java)
和 [AuthSignInActivity](https://github.com/microg/GmsCore/blob/v0.3.17.252432/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AuthSignInActivity.kt)。
