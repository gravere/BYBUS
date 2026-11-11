# 首次运行与跨设备登录

适用范围：首次导入、没有容器 Google 账号、尚未下载游戏资源的手机和平板。
不以品牌、型号或屏幕尺寸选择登录与汉化路径。

## 发现与处理

- 启动前校验完整零协包并准备资源清单与中文字体目录。游戏第一次读取内置或刚下载的
  本地化 JSON 时，直接按资源、身份与显示字段合并译文，不改游戏原始文件。
  因此首次下载在同一个游戏会话完成，不再要求返回 BYBUS 或重启。实现边界见
  [直接加载汉化](live-localization.md)。既有完整索引继续作为显示后备，并保留 90%
  日文来源就绪门槛；独立启动索引仍只接收可核对的五个启动表，不能冒充完整索引。
- microG AuthSignInActivity 在没有账号时启动 LoginActivity。旧客户端本地转发
  只覆盖 signin 包；LoginActivity 返回 VAMS 后，其来源 Activity 没有 VAMS
  记录，resultTo 被清空。仅对内置 microG 的确切认证页面、且目标声明进程等于
  当前虚拟进程时，沿用系统 Activity result 链；不把跨进程页面装进当前进程。
- PendingIntent 登录页的系统父页是宿主跳板，不在 VAMS 任务记录内。对已经由
  Google 登录跳板核对并标记的 AssistedSignInActivity，恢复游戏调用方；AuthSignInActivity
  保留其 microG 内部调用方。Android ActivityManager 与 ActivityClient 查询使用同一
  个按 Activity token 管理的记录，销毁时删除；不伪造登录成功、账号或 Token。

## 验证边界

验证覆盖启动/完整索引隔离、损坏/冲突原文、缓存晋级、登录精确目标与跨进程拒绝、
调用方记录和页面销毁清理。纯 JVM 测试不等于 Google 网络登录或平板真机验证。
原生游戏支持仍由签名、ELF 结构、API、ABI 和页大小共同决定。后续平台扩展与实际
验证范围见 [Android 适配说明](android-runtime-portability.md)。APK 的文件对齐、
容器运行时适配与 Google 登录成功属于不同层次，不能互相替代。

针对性检查包含首次启动无日文资源、同一读取器收到后续资源、元数据保持、重复身份
和损坏文件拒绝。云端实际游戏的加载证据单独记录；不连接用户设备。

## 上游依据

- [microG AuthSignInActivity](https://github.com/microg/GmsCore/blob/v0.3.17.252432/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AuthSignInActivity.kt)
- [microG AssistedSignInActivity](https://github.com/microg/GmsCore/blob/v0.3.17.252432/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AssistedSignInActivity.kt)
- [Google 登录的无账号场景](https://developer.android.com/identity/sign-in/credential-manager-siwg)
- [Android 原生代码与页大小](https://developer.android.com/guide/practices/page-sizes)
