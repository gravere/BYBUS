# 容器权限查询边界

权限查询必须针对调用方指定的应用、UID 和虚拟用户。不存在的应用、未安装的用户状态、
未声明的权限不能继承 BYBUS 的权限。共享虚拟 UID 按同一用户下已安装应用的声明集合查询；
系统权限还必须受宿主实际授权约束，不能仅因声明就返回已授予。

移除 ActivityManager 对 `ACCOUNT_MANAGER` 和 PackageManager 对
`REQUEST_INSTALL_PACKAGES` 的无条件放行。查询真实宿主 UID 时保留平台实际结果，
不把宿主 UID 当作缺失的虚拟应用，也不让未知 UID 继承它的权限。跨用户权限继续拒绝。
系统 `android.permission.*` 名称不能被虚拟应用声明的同名权限覆盖。
虚拟自定义权限仍由现有容器权限注册表处理，本次不引入新的授权存储。

`PackageInfo.requestedPermissionsFlags` 的已授予位必须与同一应用、同一虚拟用户的
`checkPermission` 结果一致。单包、带版本号查询和已安装应用列表统一在权限服务生成；
不能以旧企业功能开关（摄像头、网络等禁用配置）代替 Android 权限授权状态。
解析器只提供声明列表，权限服务按已安装状态、共享 UID 与对应宿主引擎的实际授权
计算已授予位，保留其他标志。不带 `GET_PERMISSIONS` 时两组权限数组均为空。
该修正不授予新的系统权限，也不修改 Google Authenticator 的局部授权边界。

Android 的公开 `PackageManager.checkPermission` 不始终调用 `IPackageManager`。
Android 11 使用独立 `IPermissionManager` 的三参数查询；Android 15 起使用包名在前、
带持久设备 ID 的四参数查询。游戏访客进程必须同时接入这条只读查询路径，仍调用同一个
虚拟权限服务。按完整参数类型区分新旧顺序，不按两个字符串的位置猜测，也不把新
接口当成旧接口。UID 查询同样区分两参数和三参数签名。非默认虚拟设备不属于容器
权限模型，不能因此返回默认设备的授权。授予、撤销和账号服务接口保持平台原路径。

这项接入只安装在访客进程，查询接管仅对已绑定的游戏启用；Google 等其他访客、
宿主和容器服务进程保留原查询。同步进程内
`ActivityThread` 的权限服务引用，并仅禁用本进程的包权限结果缓存，避免绑定访客前
缓存的系统结果覆盖绑定后的容器结果。Android 新增 `checkPermissionForDevice` 的
默认设备查询复用现有 ActivityManager 权限判断，不授予额外权限。

回归必须在独立访客进程通过 Android 公开 `Context.getPackageManager().checkPermission`
读取结果，并与服务进程内临时包的权限元数据比较。直接调用容器内部方法只能覆盖
服务自身，不能证明公开框架入口已接通。测试覆盖绑定前后、声明与未声明、未知包、
特权权限、UID 与非默认设备；临时注册表在检查后清理，不读取真实账号或游戏资源。
build 79 的实际诊断仍出现 70036；新路径的验证不能代替该设备的游戏启动结果。

框架依据：[Android 11 ApplicationPackageManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android11-release/core/java/android/app/ApplicationPackageManager.java)、
[Android 16 PermissionManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/permission/PermissionManager.java)。

首次启动尚无日文下载资源时，继续只启用经配对验证的启动索引；资源下载完成后
自动在下一次启动编译全量索引。安全提示中断首次下载时，缺少全量资源不能单独
证明目录错误，不得通过降低配对覆盖门槛来宣称汉化已就绪。

Google 账号服务继续使用 `MicrogAuthenticatorCompat` 的局部 Binder 边界：仅确切
GoogleLoginService、同宿主 UID 和确切账号管理服务 PID 可通过账号回调检查。
这不向游戏、其他访客或宿主授予系统账号权限。不修改游戏 APK、保护错误码或退出逻辑。
Android 13 及更早版本的 `AbstractAccountAuthenticator.Transport` 在其私有 Context
上执行账号权限检查；仅包装该确切 Binder 所属 authenticator 的 Context。
Android 14 起继续使用局部 PermissionEnforcer。两条路径都保留实际 Binder UID/PID
验证和有界后台同步传输，未知调用方使用原平台判断，不保留全局账号权限放行。
框架差异参见 AOSP [Android 13](https://github.com/aosp-mirror/platform_frameworks_base/blob/android13-release/core/java/android/accounts/AbstractAccountAuthenticator.java)
及 [Android 14](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/core/java/android/accounts/AbstractAccountAuthenticator.java)。

验证使用独立云端 Android 中的生产权限服务与临时包注册表，覆盖不存在目标、
权限未声明、虚拟用户、共享 UID、卸载状态、宿主真实权限以及 Google 回调身份规则。
另用框架真实 Authenticator、Proxy/Stub 和合成结果回调验证新旧系统的局部权限处理，
不创建系统账号、不读取凭据。调试入口不进入正式 APK，不安装或启动游戏，不操作购买。
这些检查能证明权限查询错误已修复，不能证明某次游戏 70036 的具体原因或实际启动结果。
