# 容器内计费兼容

## 初始化失败排查

启动时的购买失败提示与商品价格缺失须检查真实计费返回码，不能按结算页面取消故障处理。
静态检查发现两处独立的容器问题：自定义授权缓存失效时把账号类型与 OAuth scope 比较，
导致已撤销的缓存未被清除；注册标识补齐只在 API 36 生效，但内置 microG 的注册数据来源
并不随 Android 版本改变。前者按用户、账号类型和实际 token 精确失效所有匹配缓存；
后者仍仅接受容器 Companion 对确切虚拟 GServicesProvider 的 android_id 查询，且必须
从同一虚拟 GMS 的已完成注册状态读取，不生成 ID、不覆盖已有有效结果。

计费诊断按启动请求重新限额，只记录固定错误分类和授权结果是否要求交互，不记录账号、
Token、商品或票据。修正这些问题不等于已经证明当前设备的报错根因；商品查询、结算与
到账仍需各自的实际返回结果，不能以模拟器通过或代码检查代替。

## 当前结果（内部版本 33）

签名 v1.0 / code33 已覆盖安装到 vivo X100 Ultra / Android 16。一次游戏启动保留
原有账号和资源，进入主界面与商店；商品价格恢复。受限诊断确认注册标识桥接成功，
Companion 实际读到非零标识；两批商品请求分别返回 code=0、20 和 19 项详情，
游戏随后查询 inapp / subs 持有记录也返回 0。没有改写业务包名、商品结果或交易票据。

用户随后打开结算页，确认账号与币种一致；没有确认购买。退出结算页后游戏一直
显示连接中，取消流程尚未恢复，不能将价格恢复等同于完整付款已通过。
保留原字体原生库，SHA-256 与内部版本 23 一致。10 项身份与注册边界测试、
Release / lintVital、签名、内置资产及 16 KiB 对齐校验通过；未运行完整测试套件。

## 当前授权与证据

2026-09-30 用户明确要求继续处理 Purchase Initialize Failed，接受在 BYBUS 内建立
独立 Google 计费环境，并由本人登录或授权同一 Google 账号。手机原版能显示商品价格
是用户提供的对照。GitHub 上传仍暂停；字体与游戏原生代码不属于本次改动。

签名 v1.0 / code24 仅补充有限诊断，已覆盖安装。一次游戏启动确认：宿主 Play
能力检查 API27 返回 0，实际商品查询 API20 / inapp / 20 项返回 6，无商品详情及
DEBUG_MESSAGE。安装与汉化仍可进入游戏，不代表计费已恢复。

读取实际 Play 50.2.11 与游戏 Billing 客户端的代码后，确认 transaction901 的调用链为
`muh → qko.m → qla.m → qkv → vus.u → qqx.e → qlb.d`。
`qkx` 在清除身份前记录 Binder 的真实调用 UID；`qlb.d` 比较该 UID 的已安装包列表与
请求包名，不匹配返回 RESULT_ERROR(6)。BYBUS 与原版有不同的系统 UID，因此此处是
当前直连方案的确定阻断条件。错误码本身仍不能排除其他并存问题。
反编译材料仅存私有临时目录，不纳入仓库或交付。

## 实施边界

1. 将游戏计费保留在容器 microG Companion 内，取消对此业务的宿主 Play 强制转发。
   容器计费使用真实游戏包名，不把能力检查成功当作商店就绪。
2. 优先采用 microG 正式签名的固定版本；校验官方资产哈希、证书、包名与版本，
   不重签或修改 Google Play、microG 或游戏 APK。当前候选为 v0.3.17.252432，
   官方发布记录包含计费协议、回调、付款方式切换及安全修复。
3. Google 组件以保留数据的升级处理；不通过卸载清空账号。升级前核对当前虚拟包
   的签名及版本，失败保留可恢复状态；游戏安装包、下载资源、存档不迁移或清理。
4. 增加容器账号与计费设置入口。账号和授权在组件自己的界面完成；诊断不得记录
   邮箱、口令、Token、商品 ID、订单、支付票据或原始服务消息。
5. 先验证组件设置入口与账号授权，再进行一次游戏商品查询。价格可用、初始化完成、
   结算页与交易完成是四个不同结果；没有实际交易证据不能宣称付款已完全修复。
   不自动选择商品、确认购买、消费或伪造票据。

## 来源

- [microG 固定版本及变更](https://github.com/microg/GmsCore/releases/tag/v0.3.17.252432)
- [Android Binder 调用身份](https://developer.android.com/reference/android/os/Binder#getCallingUid())
- [Google Play Billing 错误定义](https://developer.android.com/google/play/billing/errors)
- [Android 16 的 PermissionEnforcer](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/os/PermissionEnforcer.java)

## 验证记录

- code24：Release / lintVital、APK 资源与签名、16 KiB 对齐通过；Native 与 code23 相同。
- code24：已在用户手机覆盖安装，一次游戏启动复现初始化失败并导出受限诊断。
- code25：9 项受影响单元测试通过，Release / lintVital、签名、资源、16 KiB 对齐通过；
  Native 与 code23 相同。已覆盖安装，点击账号入口触发组件升级，尚未启动游戏。
- code25 真机设置页出现 AppCompat 主题异常：容器恢复资源时清除了主题，却只应用
  Activity 显式声明的主题，遗漏 application 继承主题。改用 Android 的
  `ActivityInfo.getThemeResource()` 恢复完整主题继承；计费设置尚待重验。
- 登录页 WebView 调试限制到宿主 debug 构建，状态日志不再读取 URL path/hash。
  独立计费的商品查询、授权与结算均未验证，不视为可用付款能力。
- code26：Release、签名与资源检查通过并覆盖安装。设置入口重验确认主题异常消失，
  随后发现恢复上下文时清空 LayoutInflater 丢失 FragmentActivity 提前注册的 Factory2，
  导致 FragmentContainerView 无法创建。改为针对恢复后的上下文克隆现有 inflater，
  保留目标 Activity 的 factories；未启动游戏。
- code27：Release / lintVital、签名、资源与对齐通过，覆盖安装成功；设置首页、账号页、
  Play 商店服务页均可打开，已有账号保留。手动开启容器内的应用内购买和设备注册；
  开关状态不等同于服务器注册或付款成功，商品查询继续验证中。
- code27 一次游戏启动进入标题页、主界面和商品目录；账号与资源保留，价格为空。
  真实容器服务连接，API27 能力检查返回 0，API20 商品查询返回 5/internal。
  限定进程日志确认授权请求被 Android 16 `IAccountAuthenticator` 的
  `PermissionEnforcer` 以 ACCOUNT_MANAGER 权限拒绝，Companion 等待回调 15 秒后超时。
  同时容器 GServicesProvider 拒绝被误写为宿主包名的 android_id 查询。
- 后续修复只桥接虚拟账号管理服务到虚拟 Google authenticator 的权限边界：精确校验
  组件、Binder 接口、宿主物理 UID 和虚拟系统服务 PID，其他调用仍走原权限检查。
  Companion 与 Services 保留业务包名；仅对容器 GServicesProvider 的这两个调用方
  保留 Google 包名和真实 Binder UID，不读取手机原版 Google 的账号数据库或 Token。
- code28：3 项身份边界测试、Release / lintVital、签名及资源检查通过，覆盖安装成功。
  一次启动进入主界面，账号、资源保留；桥接安装事件已出现，但商品价格仍空，授权超时。
  Android 16 平台 `IAccountAuthenticator` 为 oneway 接口；该调用不携带 PID，故不能满足
  确切账号服务 PID 的检查。客户端仅对此已校验组件采用同步 Binder 传输，在有界后台
  队列执行，原账号结果回调不变；不把 PID=0 或同 UID 的任意访客视为可信。
  来源：[平台接口](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/accounts/IAccountAuthenticator.aidl)、
  [Binder PID 语义](https://developer.android.com/reference/android/os/Binder#getCallingPid())。
- 设备注册设置为开但状态仍是未注册。固定 microG 源码显示开关通过显式
  `TriggerReceiver` 广播开始注册；Android 16 `ContextImpl` 使用
  `broadcastIntentWithFeature`，旧容器只处理 `broadcastIntent`。补齐的转发仅接受
  容器 GMS 发往自身 Checkin TriggerReceiver、无 action 的显式广播，目标限定宿主内部
  接收器；不改手机 Google 服务、不重定向其他广播，不把开关开启等同于注册成功。
- code29：5 项边界测试及 Release / lintVital、签名、资源校验通过，覆盖安装成功。
  一次游戏启动后，授权超时消失，Companion 已发出真实商品请求；Google 返回 HTTP400 /
  DF-DFERH-01，仍未显示价格，未进行购买。注册广播转发事件出现；Google 主服务未启动时
  不被旧广播规则唤醒。主服务运行后可到达 CheckinService，其后云推送重连精确闹钟被
  系统权限拒绝，该日志不等同于注册或计费失败，不为此请求闹钟权限。
- 进一步发现旧 `includeMicrogSettingsProviderIfMissing` 给 `:ui` 复制了独立
  SettingsProvider。上游明确要求各进程通过单个 Provider 访问 SharedPreferences；
  UI 与主服务各持一份缓存会使注册时间、设备标识和设置不同步。恢复主进程单一持有，
  UI 通过 Binder 读写；这也会在打开设置时按需启动主服务，无需放宽广播后台启动规则。
  `getContentProvider` 对这个精确 Provider 不再以宿主占位 Binder 非空作为查询前提。
- code30：6 项边界测试、Release / lintVital、签名、资源校验通过并覆盖安装。设置页已
  显示真实注册时间；一次启动进入游戏主界面，账号与资源保留，商品目录价格仍空。
  实际商品请求继续返回 HTTP400 / DF-DFERH-01，授权回调不再超时。用户确认容器账号
  与手机 Play 付款账号相同。此错误也有上游报告，但不能仅凭相同错误认定同一根因：
  [microG #3582](https://github.com/microg/GmsCore/issues/3582)。
- 后续核对发现 GServicesProvider 也走同一宿主占位查询，需按精确包名、类名、authority
  独立取得虚拟 Binder。增加实际 android_id 单键查询的只读诊断，仅记录可读和非零两项
  布尔结果，不保存标识或查询参数，不改变或关闭 Cursor，并恢复读取前的位置。
- code31：8 项边界测试和签名 Release 校验通过，一次启动进入主界面和商品目录，价格
  仍空、商品接口返回 5/network。实际采样位置错误导致没有标识查询事件，不能据此
  推断标识有效。修正只读采样到 query 返回之后，并将 DF-DFERH-01 归为固定
  play_backend 类别；不记录原始服务消息，不改变商品请求和响应。
- code32：签名 Release / lintVital 和打包校验通过，沿用未改动策略的 8 项单元测试结果。
  一次启动保留账号和资源到达主界面、商品目录，价格仍空。新增的实际读取记录确认
  `google_registration_id readable=0 nonzero=0`，授权边界检查通过，商品返回
  `5/play_backend`（原消息包含 DF-DFERH-01）。因此不能把问题完全归于上游协议。
- 后续适配仅在上述精确查询缺少有效 ID 时，读同一虚拟 GMS SettingsProvider 的
  `checkin_enable_service`、`androidId`、`lastCheckin` 三列。必须启用、ID 非零且已有注册时间，
  才以无符号十进制传给 Companion 原有编码逻辑；不生成 ID、读取手机原版 GSF、获取
  securityToken 或改写有效原结果。账号与 Google 服务授权错误继续如实返回。
- code33：上述适配在真机命中 `google_registration_id_bridge registered=1` 和
  `google_registration_id readable=1 nonzero=1`；商品查询由 `5/play_backend`
  恢复为 `0/details=20` 与 `0/details=19`，商店直接显示台币价格。保留登录和资源，
  没有选择商品、开启结算或执行购买。最终构建只做一次安装和一次游戏启动；此前各版
  的排查次数与结果保留在上述记录中，不把本版次数当作整个排查过程的次数。
- code33 用户自行打开结算页，随后纠正最初的疑虑，确认账号和币种一致；未确认付款。
  退出后停在游戏商店的连接中。系统实际接收器表确认游戏订阅
  `com.example.limbuszhcn_VA_protected_com.android.vending.billing.PURCHASES_UPDATED`，
  但 Android 16 `ContextImpl.sendBroadcast` 调用的 `broadcastIntentWithFeature` 只处理
  Google 注册广播，计费通知未使用对应容器转发。后续修复只接管游戏发给自身、无显式
  组件的该精确 action，保持完整原始 Intent/返回码，外层仅投递到宿主内接收器；
  接收端核对虚拟目标包，并记录实际发送/接收的返回码，不保存账号、金额或票据。
- 安装前的返回链检查同时发现：通用 PendingIntent 包装无条件增加 NEW_TASK，桥接页
  启动访客后立即 finish。这会让系统的 for-result 关系提前取消。仅对确切 Companion
  InAppBillingHostActivity 移除包装引入的 NEW_TASK，保持 PendingIntent 不可变属性；
  在记录的发起 Activity 属于 Limbus 时，由桥接页 startActivityForResult 持有结算页面，
  原样转交其 resultCode/Intent。桥接页重建时不重复开启结算。启动失败返回明确错误，
  不用固定延时或合成的付款结果解除游戏等待。
- code34 真机未通过退出验证：用户退出后仍停在连接中。诊断确认桥接页已经收到真实
  Activity 结果（`resultCode=-1`、Intent 存在），但没有结果广播发送/接收记录；商品查询
  仍为 39 项、返回 0。不能把 Activity 桥接成功写成整个取消流程已修复。
- 当前游戏 APK 的 `billing.properties` 为 8.3.0。对照 Google Maven 同版本官方 AAR：
  `ProxyBillingActivity` 发送的是 `LOCAL_BROADCAST_PURCHASES_UPDATED`，目标取自
  Application context；旧 `PURCHASES_UPDATED` 是另一个外部通知入口。容器还存在
  `registerReceiverWithFeature` 参数错误：permission 为 null 时回落到旧索引 4，
  实际把 receiver 清空。系统接收器表恰好只存在带非空权限的旧通知，缺少本地通知。
  修复按方法签名定位 permission（与值是否为 null 无关），不改 receiver/userId/flags。
  两个精确结果 action 都按游戏自身通知处理；由于现有 Application context 返回宿主
  包名，仅当虚拟调用方确为游戏且目标是本游戏或当前宿主时，归一为游戏目标，再进入
  宿主内部受保护广播。其他调用方/目标/显式组件/未知 action 均不适用，不改整个
  Application identity、不改返回码或购买 payload。记录有限的注册、路由和真实结果码。
  参考：[Google 官方 Billing 8.3.0 AAR](https://dl.google.com/dl/android/maven2/com/android/billingclient/billing/8.3.0/billing-8.3.0.aar)、
  [Android 16 IActivityManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/app/IActivityManager.aidl)。
- code35：8 项受影响检查通过（4 项广播范围、4 项注册参数/空值边界）；签名 Release /
  lintVital、内置组件哈希和 16 KiB 对齐通过。Native 与 code23–34 完全相同；此前通过的
  2 项结算 Activity 策略检查对应代码未变，本版没有重跑整套测试。退出行为仍待真机确认。
- code35 已覆盖安装一次，已安装 APK 的 SHA-256 与构建一致。一次启动保留账号和资源，
  到达主界面与商品目录，价格正常。系统接收器表现在同时存在受保护的本地结果和旧结果
  action，确认原先缺失的本地接收器已恢复。已请用户打开结算后退出，等待实际交互结果；
  代理没有选择商品、打开结算或执行购买。
- code35 用户确认退出结算后可以正常操作。紧接此次操作的同版诊断确认：本地接收器
  存在、结算 Activity 返回、`billing_update_sent code=1 action=local` 和
  `billing_update_received code=1 action=local` 均发生，真实取消通知已送达游戏。
  39 项商品查询继续成功，账号和资源保留。此结论覆盖商品显示、结算打开和正常取消；
  没有确认订单、扣款、消耗或到账测试，不能写作完整付款验证通过。
