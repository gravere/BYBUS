# 首次运行与跨设备登录

适用范围：首次导入、没有容器 Google 账号、尚未下载游戏资源的手机和平板。
不以品牌、型号或屏幕尺寸选择登录与汉化路径。

## 发现与处理

- 完整索引要求至少 90% 的日文资源配对成功。新设备在资源下载前没有完整索引，
  但运行时已切到日文；已使用过的设备缓存会掩盖此问题。保留完整索引的门槛，
  增加独立的启动索引，只接收 MainUIText、LoginUIText 和三个 BattleHint 表中
  按身份配对成功的文本。来源必须是当前已安装 APK 的严格有界 TextAsset 或当前
  容器下载的日文原文，译文仍来自已校验的零协包。缺失或未知布局保持原文。
  启动索引不能替换完整索引、不能计为资源准备完成；下载完成后的下一次启动
  仍升级为完整索引。两种指针分开保存，运行时优先选择通过校验的完整索引。
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
原生游戏支持仍由签名、ELF 结构、API、ABI 和页大小共同决定；当前已批准环境仍为
Android 16 / arm64 / 4 KB。APK 的 16 KB 文件对齐不等于容器运行时支持 16 KB。

## 上游依据

- [microG AuthSignInActivity](https://github.com/microg/GmsCore/blob/v0.3.17.252432/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AuthSignInActivity.kt)
- [microG AssistedSignInActivity](https://github.com/microg/GmsCore/blob/v0.3.17.252432/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AssistedSignInActivity.kt)
- [Google 登录的无账号场景](https://developer.android.com/identity/sign-in/credential-manager-siwg)
- [Android 原生代码与页大小](https://developer.android.com/guide/practices/page-sizes)
