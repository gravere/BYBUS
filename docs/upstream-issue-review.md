# 上游问题核对

核对基线：[limbusZhCN PR #17](https://github.com/BBBEANNN/limbusZhCN/pull/17) 与主分支 `7c3cdb14`。上游工单的机型现象不能替代 BYBUS 的复现记录。

## 安装身份

旧 BYBUS 使用上游的 `com.example.limbuszhcn`，因此 Android 将上游 APK 识别为同一应用的较低版本。已检查上游正式 APK：v1.5.2 的 versionCode 为 8，且证书与 BYBUS 不同。

BYBUS 已改用 `io.github.gravere.bybus`，同时隔离容器 Provider、权限、任务栈与宿主 action。旧身份仅保留为同签名迁移助手；独立版导入旧数据、用户确认后移除助手，即可安装上游并与 BYBUS 共存。迁移和双更新入口见 [迁移设计](package-migration.md)。

上游 APK SHA-256：`4233a4ad131dcc5f898e007ad787cdbcb3231bb506c28b0a3d4988a459b24200`；证书 SHA-256：`df648cd25ec9661b06a6c2d8ddf6ef8e7c3864b6b02c94a258a82c871e18705b`。BYBUS 证书 SHA-256：`d50bbcfb234692d5d5f90427499158608c5a4c13adb7f99a78d9b5f1af44d55b`。

## 处理边界

| 上游 Issue | BYBUS 对照结论 |
| --- | --- |
| #1 登录选项返回 / #6 Google 黑屏 | 已有 microG 同进程 UI、资源重建和精确 OAuth 回调；本次补首次静默安装的 authenticator 注册刷新。新账号授权、Apple 实际回调仍需独立运行证据。 |
| #4 Provider UID 校验 | 已使用物理 Binder 身份兼容策略；不把这视为所有机型均已验证。 |
| #5、#9 启动时跳板崩溃 | 同地址 libc 别名重复 hook 的风险仍存在，本次修正；同步修正搬运中的负偏移和 ADRP 页地址计算。 |
| #16 信号循环 / 黑屏 | 旧守卫只比较 PC/SP；本次改为完整通用寄存器上下文比较，仍终止未被处理的同步故障。不是普遍忽略 SIGSEGV。 |
| #2 Firebase / 黑屏 | BYBUS 仍使用普通服务绑定，未加入上游跳过统计服务的候选处理；缺少服务 ANR 线程栈，不能确认该机型的同根问题已解决。 |
| #3、#8、#10、#12、#13、#14 保护库或原生崩溃 | BYBUS 有独立的签名、代码族和 CRC 检查；未知保护结构及缺少完整现场的崩溃不能认定已修复，不扩展专属偏移例外。 |
| #7 设置应用后卡住 | 现有生命周期改进不能代替该故障的完整 ANR 证据；未确认消除。 |
| #15 译文 0/0 / ANR | BYBUS 区分启动索引与完整索引，校验日文资源覆盖率；未用此机制推断上游保护错误已解决。 |

## 底层修正

IO 别名注册表只认可明确的一组等价接口，并在安装入口生效前发布原函数槽位。ARM64 指令搬运参考上游 PR #17，保留 BYBUS 的动态页大小计算及 16 KiB 隔离池；回跳空间计入容量上限。宿主测试只检查生成的 ARM64 指令，不声称在 x64 上执行了 ARM64。

信号处理器可以修改通用寄存器恢复执行；PC/SP 不是全部上下文。参考 [AOSP ARM64 信号处理实现](https://android.googlesource.com/platform/art/+/c8d6e3b523f31baa548934f822c6e36adc2a52e8/runtime/arch/arm64/fault_handler_arm64.cc)，仍先交给原处理器，未消费的故障走默认终止。
