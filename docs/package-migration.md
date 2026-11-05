# 独立安装与旧版迁移

BYBUS 使用 `io.github.gravere.bybus`，可与上游 `com.example.limbuszhcn` 并存。容器权限、Provider authority、任务栈和应用自身 action 使用宿主独立身份；源码 namespace 不承担安装身份。

## 旧版本升级路径

旧更新器固定读取 `bybus-update.json` 和 `BYBUS.apk`，且校验旧包名。这两个入口保留为同版本的轻量迁移助手；它保留旧签名和包名，覆盖升级会停止旧容器进程，原数据仍保留。助手不携带游戏运行环境，也不初始化 VirtualApp。

独立版使用 `BYBUS-Android.apk` 与 `bybus-app-update.json`。版本化完整安装包指向独立版。首次下载始终指向独立版；迁移附件标明仅供旧更新器使用。两个清单分别校验自己的包名、签名与文件摘要。

## 数据边界

用户在独立版发起导入。旧助手的只读 Provider 只接受独立 BYBUS 的确切 UID、包名及相同签名，不提供通用文件访问、写入或删除入口。数据通过私有 Binder 文件描述符流传输，不写入公共存储或上传网络。

迁移保留游戏与 Google 组件的数据目录、账号配置及可复用的汉化缓存；游戏 `cache/UnityCache` 也必须保留，[Unity 的下载资源缓存](https://docs.unity.com/en-us/engine/6000.7/manual/assets-and-media/assets-managing-runtime/assetbundles-section/caching)不能视为可丢弃代码缓存。跳过符号链接、代码缓存、Google 运行缓存和安装注册表。目标先从系统官方游戏与内置 Google 组件建立新的安装记录，再停止目标容器服务并复制数据。原有绝对路径、Provider authority 和包缓存不得按字符串全盘替换。

每个文件限制路径、长度，使用临时文件、SHA-256 校验及同步落盘后原子替换。源数据始终保留；中断可重新导入。导入期间阻止普通游戏启动，成功后才记录完成状态。空间不足应在传输前明确提示，不删除旧文件腾空间。Android Keystore 与 UID 绑定的凭据不能通过文件复制保证有效，必要时由用户重新授权 Google 账号。

只有导入完成，旧助手才提示用户确认正常后移除旧助手。卸载由系统确认，应用不得自动执行。旧助手移除后即可安装上游项目，独立 BYBUS 的数据不受影响。

## 检查要求

使用无个人数据的云端夹具验证跨应用权限、流校验、异常中断与重试，以及独立版和上游正式 APK 的实际并存安装。不能把合成数据导入等同于真实游戏账号或付款验证。
