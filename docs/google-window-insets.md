# Google 登录窗口与系统导航

[Issue #32](https://github.com/gravere/BYBUS/issues/32) 报告 microG 登录页全屏时底部按钮不能点击，
分屏后恢复。固定版本的 AssistantActivity 使用没有 inset 处理的 `auth_root` 全高布局；
容器宿主 target 35 后，系统强制的 edge-to-edge 窗口会使底部按钮进入导航区。

兼容只作用于容器 GMS 中继承该旧 AssistantActivity 的已知布局，在目标 onCreate 后
读取当前窗口的系统栏、刘海和键盘 Insets。根据根布局与窗口的实际重叠补足 padding，
不重复添加系统已经避让的空间，不累加上次 padding；旋转、分屏和键盘变化时重新计算。
保留已有 padding，向子布局传递扣除已处理区域的 Insets。游戏不套用该补偿，
设置页保留原有的 `fitsSystemWindows`。

同一固定版本的 `InAppBillingHostActivity` 使用非浮动、底部对齐的透明窗口，
`SheetUIPage` 未处理 Insets；`PlayWebViewActivity` 的全屏 WebView 也未处理。
仅对这两个精确的 Companion Activity，在其内容根布局应用相同的重叠计算。
不按“使用 Compose”推定页面已适配，也不对所有 Google Activity 统一加 padding。

依据：[Android 窗口 Insets](https://developer.android.com/develop/ui/views/layout/edge-to-edge)、
[microG 登录布局](https://github.com/microg/GmsCore/blob/v0.3.17.252432/play-services-core/src/main/res/layout/login_assistant.xml)。

付款窗口依据：[主题](https://github.com/microg/GmsCore/blob/v0.3.17.252432/vending-app/src/main/res/values/styles.xml)、
[页面](https://github.com/microg/GmsCore/blob/v0.3.17.252432/vending-app/src/main/java/org/microg/vending/billing/ui/SheetUIPage.kt)、
[网页入口](https://github.com/microg/GmsCore/blob/v0.3.17.252432/vending-app/src/main/java/org/microg/vending/billing/ui/PlayWebViewActivity.kt)。

## 检查结果

Android 15/17 的真实 Google 登录页已核对手机竖屏手势、竖屏三键、横屏侧边导航、
平板竖屏与横屏五种布局；两侧按钮完整显示且不与实际系统导航区域重叠。
[布局记录](https://github.com/gravere/BYBUS/actions/runs/42954609125)中的取消点击已到达登录页，
该轮随后因调试入口旋转重建而重新打开登录页，整体任务失败；调试入口已补重建保护。

[窗口检查](https://github.com/gravere/BYBUS/actions/runs/42958080448)确认相同主题的底部窗口与
全屏窗口在键盘弹出/收起时按钮可见、可点击；此处使用布局夹具，不模拟购买结果。
宿主付款状态页及手势/三键、明暗主题、横屏导航检查在 Android 15 完成，
[Android 17 补充检查](https://github.com/gravere/BYBUS/actions/runs/42958766997)亦通过。
截图已人工核对。实际账号授权和结算流程不在这次窗口检查范围内。
