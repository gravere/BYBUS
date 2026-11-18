# 游戏语言与 Google 广播兼容

游戏自行读取已保存的语言。BYBUS 将日文、英文或韩文资源表中的显示字段按记录匹配到同一份汉化包。目录与表名不符，或同名表无法唯一确定时，保留原文。

## 旧 microG 广播注册

Android 14+ 的系统看到的是 targetSdk 35 的 BYBUS。容器内 targetSdk 小于 33 的
microG 仍可能按旧 API 不传导出标志，因此系统可能拒绝注册。

只对包名 `com.google.android.gms`、有效 targetSdk < 33、API >= 34 的真实接收器注册，
在参数签名明确匹配 `IntentFilter, String, int, int` 尾部且未指定导出方式时补
`RECEIVER_EXPORTED`。保留其他位、已有 EXPORTED/NOT_EXPORTED、空接收器的 sticky 查询、
其他访客和未知签名。继续使用容器原有 action 保护与身份检查，不扩展账号权限。
