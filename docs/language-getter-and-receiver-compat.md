# 语言 getter 与 Google 广播兼容

本次整合 [UMP40 的 PR #2](https://github.com/gravere/BYBUS/pull/2) 中的短函数修复，
以及 [PR #1](https://github.com/gravere/BYBUS/pull/1) 中尚未覆盖的旧 microG 广播注册行为。
现有精确 Activity 白名单、可信 PendingIntent 身份标记、共享调用者记录和计费返回链不替换。

## 语言 getter 写入边界

`LocalGameOptionData.GetLanguage` 可能只有 8 字节，普通远跳 hook 的 16/20 字节写入
会破坏后面的 `SetLanguage`。这两个入口只需返回日语枚举值，不需要原函数跳板。

- IL2CPP 初始化完成后，通过元数据核对所属类、方法名、无参数实例方法和返回类型。
  `il2cpp_method_get_flags` 的返回值才是方法标志，输出参数是实现标志，不能混用。
  返回类型须为无命名空间的 `LOCALIZE_LANGUAGE`、底层为 Int32，且常量 JP 仍等于 2。
- 从全局托管方法地址集合取下一入口。写入仅含 `mov w0, #2; ret`（8 字节），入口有
  BTI 时保留原 landing pad（共 12 字节）。未知边界或空间不足直接跳过，不能回退到长 hook。
- 写入前核对完整范围所在的可读可执行映射及实际 4 KB / 16 KB 页边界；只有同一映射
  覆盖完整页范围时才临时加写权限，完成后清指令缓存并恢复权限。读取首指令也先检查映射。
- 使用原有 owned-patch 账本登记精确写入范围；登记不可用时不写。写后核对字节，异常时
  在同一个账本事务内恢复原字节。完整 CRC 与未知修改拒绝规则不变。
- 日志报告安装及权限恢复结果，不再把计数器或合成调用说成游戏实际调用。

## 旧 microG 广播注册

Android 14+ 的系统看到的是 targetSdk 35 的 BYBUS。容器内 targetSdk 小于 33 的
microG 仍可能按旧 API 不传导出标志，因此系统可能拒绝注册。

只对包名 `com.google.android.gms`、有效 targetSdk < 33、API >= 34 的真实接收器注册，
在参数签名明确匹配 `IntentFilter, String, int, int` 尾部且未指定导出方式时补
`RECEIVER_EXPORTED`。保留其他位、已有 EXPORTED/NOT_EXPORTED、空接收器的 sticky 查询、
其他访客和未知签名。继续使用容器原有 action 保护与身份检查，不扩展账号权限。

## 验证范围

用主机 C++ 验证相邻函数哨兵、8/12 字节范围、BTI、异常边界、方法标志和完整性账本；
用 JVM 验证广播标志及现有登录/计费路由，再构建签名正式 APK 和差异更新。
这不替代手机/平板上实际 Google 网络登录与游戏设置操作验证。
