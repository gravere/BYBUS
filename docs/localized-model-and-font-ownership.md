# 本地化模型与语言字体归属

## 问题与边界

用户报告同一词条随页面和选择顺序改变译文、字体。原有路径在 JSON 读取、
TextData formatter、最终 TMP 赋值分别处理；短 getter 无法安全 inline hook，
格式化后又可能丢失稳定 ID。按最近输出字符串识别字体不能作为语言组件的归属依据。

Project Moon 的 1.74.0 公告给出 Title/Context 字体配置，并指出自定义字体与
官方字体混用的问题：
https://steamcommunity.com/games/1973530/announcements/detail/533221941907030184
该 PC 安装指南不等于 Android 可直接启用 CustomLocalizeManager。
当前 Android 构建的元数据确认存在 JsonDataList<T>、LocalizeTextData、
UtilityUI 的语言字体组件。以下是 BYBUS 对这些共用边界的适配，不声称官方 Android API。

## 实施约束

- 已校验零协包提供带命名空间的稳定 ID/字段索引，与字体目录同时原子生成。
  仅接管明确的显示字段；跨文件重复键只有译文一致时可用，冲突不能按读取顺序覆盖。
  不读取任意 ID，不改图标键、技能 ID、数值或游戏资源文件。
- 在 TextDataSet 的实际闭合 JsonDataList<T> 类型上核对 GetData/Find 的实例 ABI，
  在返回本地化模型时处理显示字段，先于短 getter 和富文本展开。
  共享泛型函数按地址只安装一次，并核对实际实例类；非本地化数据继续原函数。
  所有相邻函数空间与模块边界检查保留，不能覆盖 8 字节 getter。
  TextDataSet 加载结束后，通过已核对的 GetList 返回类型遍历相同模型；直接枚举列表的
  使用方同样拿到译文。短 getter 仅通过 runtime_invoke 调用，不 inline patch。
- UtilityUI 的语言字体组件给目标 TMP 提供明确归属，选中状态、逐字片段和
  重复绑定不再改变字体归属。字体设置与材质、颜色、原字号分开；数字专用和图标
  字体类别不被登记为语言正文。归属使用弱引用与 Unity instance ID 防止对象池地址误用。
- 未由语言组件管理的 TMP 仍需完整译文证明，不能按非 ASCII 或正文子串泛化。
- 字体初始化失败保留原文；缺失或冲突译文保持原文，不补机器翻译。

## 必要验证

用生产函数验证首次/重复/逆序取词、相同词条跨组件、逐字片段、池复用、状态字体
重设、数字字体排除、共享泛型调用隔离及重复 ID 冲突。真实游戏画面和 hook 安装
未观察到时单独列明，元数据核对和模拟不代替真机验证。
