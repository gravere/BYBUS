# 容器内计费兼容

## Google 授权组件与页面恢复

内置 Google 组件使用官方签名的 microG / Companion v0.3.18.252432 成对升级，保留账号、注册信息和用户设置。
该版本修正额外授权中转页的主题及 Activity 回传；不能用自动开启全部权限或跳过用户授权代替修复。
升级验证须覆盖需要额外授权、取消和返回，不能只验证设置页或登录入口能打开。

授权 PendingIntent 的来源必须与容器记录的真实创建者一致。Android 新接口返回的创建者信息按同一 Binder token
和虚拟用户核对，仅恢复容器内的逻辑包名；未知或来自其他应用的 PendingIntent 保留系统结果。
同一授权请求的不可变属性、唯一标识和原始结果不得更改。

Google 登录页加载慢或暂时隐藏不构成网络错误。页面恢复须依据明确的主文档网络错误及仍在当前页面的证据，
不按固定五秒强制重载，不清除账号、Cookie 或设备注册。服务器拒绝身份验证时保留真实提示，
不把设备已注册或组件检查通过表示为账号验证成功。

## 注册就绪与首次商品查询

应用内购买开关、设备注册开关和服务器注册成功是三个不同状态。启用开关后，须显式启动容器内已安装的 `CheckinService`，由 microG 完成真实注册；不能把广播已发送或能力检查返回 0 当成付款准备完成。

只在缺少有效注册时触发首次注册并使用服务自身的结果通知。准备启动或用户检查付款时，可在后台有限等待；已注册路径立即返回，同一尝试共享等待、超时不无限重启。状态以同一 SettingsProvider 回读的启用标志、非零 ID 和成功时间为准，不写入或伪造注册 ID。超时保留账号和原错误，不阻塞用户无限等待，也不标记为已注册。

首次商品查询前尽量完成注册，避免 Companion 将缺少设备 ID 的授权上下文缓存后持续返回空价格。验证应观察真实注册结果和商品查询，不能用“服务已连接”代替。

## 组件与会话恢复

认证器注册表属于内部包状态，不依赖是否发送安装通知。安装 Google 组件后立即刷新完整
注册表；刷新先构建新快照，再在同一锁内发布，首次登录不需要重启容器。
账号及账号可见性记录使用同步到磁盘的原子替换；读取完成并验证全部记录后才发布内存
快照。认证器暂不可用不能作为删除已保存账号的依据，失败也不能覆盖最后一份有效文件。

容器同步安装同时观察结果与服务 Binder 死亡，并有等待上限。等待超时后保留未完成
请求，拒绝新的宿主写操作；服务侧安装锁覆盖完整安装事务，跨宿主进程重建也能阻止
重叠写入。迟到回调只完成原请求，不回写已结束的页面任务。

Google 组件就绪必须同时满足 APK 身份和所选 ABI 原生库的名称、大小及 CRC 校验。同版本但库文件缺失／不完整时，只重新部署库文件，不卸载 APK 或清除账号。目录切换中断留下的恢复目录按原事务恢复，未使用的原生库暂存目录仅在停止相关进程后清理。

账号会话领取终态 response、移除会话和清空 response 必须在同一锁内完成。Binder 死亡、断连、错误及正常结果共用一次性关闭入口；解绑与远端通知放在锁外。中间授权界面结果仅能读取仍开放的会话，不能重复领取最终回调。

## 初始化失败排查

内置 Companion 的旧式 `isBillingSupported` 对有效 API 3–27 / inapp、subs 均返回 3 时，
其固定版本源码对应 `VendingPreferences.isBillingEnabled` 为 false。启动成功、存在账号
不能证明付款开关和设备注册已完成。宿主提供独立的 Google 账号与付款状态页，只读取
容器内同一 SettingsProvider 的付款开关、注册开关与注册完成布尔状态；不读取 Token，
不显示或记录账号名称、设备标识。Google 组件完成校验与安装后，默认启用付款和设备注册，
经同一 Provider 读回确认；已有安装也自动补齐关闭的开关。仅在开关缺失时写入，未注册时
触发 microG 自身注册服务；首次启动在后台最多等待 12 秒，已有注册立即继续。失败时可在付款设置中重试，不伪造注册成功或改写计费响应。

宿主读写设置期间，必须持有该虚拟进程对应的系统占位 Provider 连接。只拿到虚拟 Binder
不构成 Android 的组件依赖：Android 17 实测会冻结闲置的 Google 进程，随后同步调用触发
`Sync transaction while frozen` 并由系统终止进程。先核对 ClientConfig 的包、进程和槽位，
通过 ContentResolver 取得短期连接，让系统按正常组件依赖恢复进程；整个设置读写结束后
释放连接。不关闭系统冻结策略，不新增常驻服务，不持久占用该连接。
访客侧 `GetContentProvider` 原有的系统占位连接继续保留；此次补齐的是宿主设置读写遗漏的依赖。
依据：[AOSP 缓存进程冻结与同步 Binder](https://source.android.com/docs/core/perf/cached-apps-freezer)。


启动时的购买失败提示与商品价格缺失须检查真实计费返回码，不能按结算页面取消故障处理。
静态检查发现两处独立的容器问题：自定义授权缓存失效时把账号类型与 OAuth scope 比较，
导致已撤销的缓存未被清除；注册标识补齐只在 API 36 生效，但内置 microG 的注册数据来源
并不随 Android 版本改变。前者按用户、账号类型和实际 token 精确失效所有匹配缓存；
后者仍仅接受容器 Companion 对确切虚拟 GServicesProvider 的 android_id 查询，且必须
从同一虚拟 GMS 的已完成注册状态读取，不生成 ID、不覆盖已有有效结果。

计费诊断按启动请求重新限额，只记录固定错误分类和授权结果是否要求交互，不记录账号、
Token、商品或票据。修正这些问题不等于已经证明当前设备的报错根因；商品查询、结算与
到账仍需各自的实际返回结果，不能以模拟器通过或代码检查代替。

## 通用 Android 路径

以系统接口差异划分，不按厂商或机型分支。AOSP 从 Android 14 开始已在
`IAccountAuthenticator` 使用 `@EnforcePermission(ACCOUNT_MANAGER)`；Android 14、15、16
都必须在同一确切虚拟 Google authenticator 上适配权限及 oneway 调用 PID，不能只处理
API 36。原身份、接口和反射结构检查仍保留，Android 8–13 沿用旧权限入口。

`broadcastIntent` 与 `broadcastIntentWithFeature` 共享相同的精确计费结果、注册通知路由。
旧入口也要归一游戏 Application context 的宿主包名别名；不能改业务返回码、票据或
广播权限。授权后台队列应能立即并发处理两项请求，避免一个慢授权串行阻塞另一个；
空绑定或已失效绑定应立即返回错误并释放会话，关闭后排队的请求不得继续调用服务。
绑定所有权独立于 authenticator Binder 记录：空 Binder、服务死亡和绑定期间会话关闭
都要释放成功建立的连接，不能因 Binder 已为空而跳过 unbind。

首次使用仍需在容器内登录账号，设备注册和应用内购买由 BYBUS 默认开启。
已登录游戏、已安装组件和计费就绪是不同状态；状态页显示真实注册结果，账号与付款仍由用户确认。

接口依据：[Android 14 authenticator AIDL](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/core/java/android/accounts/IAccountAuthenticator.aidl)、
[Android 14 权限调用](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/core/java/android/accounts/AbstractAccountAuthenticator.java)。

## 服务初始化验证

Android 15 与 Android 17 的一次性模拟器使用实际内置 Companion 服务，首次安装的
API 3/27、inapp/subs 四项能力查询均返回 0；将开关关闭后返回真实错误 3，再走普通
初始化自动恢复为 0。通过同一 Provider 读回确认持久保存，状态页无需手动开启。

Android 17 另将 Google 设置所属进程置于冻结状态，读取内核 `cgroup.events` 确认
`frozen=1` 后打开设置；同一 PID 存活并成功完成读写，随后设置页和宿主付款页正常显示。
证据：[Android 15](https://github.com/gravere/BYBUS/actions/runs/42958080448)、
[Android 17 冻结恢复](https://github.com/gravere/BYBUS/actions/runs/42958766997)。
这验证服务配置与恢复，不包含账号授权、商品价格、结算、扣款或到账；未使用真实账号。

## 实施边界

1. 将游戏计费保留在容器 microG Companion 内，取消对此业务的宿主 Play 强制转发。
   容器计费使用真实游戏包名，不把能力检查成功当作商店就绪。
2. 优先采用 microG 正式签名的固定版本；校验官方资产哈希、证书、包名与版本，
   不重签或修改 Google Play、microG 或游戏 APK。固定版本为 v0.3.18.252432，
   官方发布记录包含计费协议、回调、付款方式切换及安全修复。
3. Google 组件以保留数据的升级处理；不通过卸载清空账号。升级前核对当前虚拟包
   的签名及版本，失败保留可恢复状态；游戏安装包、下载资源、存档不迁移或清理。
4. 增加容器账号与计费设置入口。账号和授权在组件自己的界面完成；诊断不得记录
   邮箱、口令、Token、商品 ID、订单、支付票据或原始服务消息。
5. 先验证组件设置入口与账号授权，再进行一次游戏商品查询。价格可用、初始化完成、
   结算页与交易完成是四个不同结果；没有实际交易证据不能宣称付款已完全修复。
   不自动选择商品、确认购买、消费或伪造票据。

## 账号回调与客户端退出

容器内固定版本 microG 的身份服务可能在游戏进程退出后，继续回传异步退出登录结果。
这个已失效的 Binder 不应让 Google 服务一并崩溃。服务端只对游戏发起的
`IdentitySignInService` 请求包装回调；保留请求、账号操作与真实结果，仅在结果接收方
已经死亡时结束投递。权限异常、其他远程错误、其他服务及付款回调不属于这个处理范围。
包装须留在 Google 服务进程，不能放在随游戏一同退出的进程中。

依据：固定版本的
[身份服务](https://github.com/microg/GmsCore/blob/v0.3.18.252432/play-services-core/src/main/kotlin/org/microg/gms/auth/credentials/identity/IdentitySignInService.kt)
与 [Binder 协议](https://github.com/microg/GmsCore/blob/v0.3.18.252432/play-services-auth/src/main/aidl/com/google/android/gms/auth/api/identity/internal/ISignInService.aidl)。

## 来源

- [microG 固定版本及变更](https://github.com/microg/GmsCore/releases/tag/v0.3.18.252432)
- [Android Binder 调用身份](https://developer.android.com/reference/android/os/Binder#getCallingUid())
- [Google Play Billing 错误定义](https://developer.android.com/google/play/billing/errors)
- [Android 16 的 PermissionEnforcer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/os/PermissionEnforcer.java)
