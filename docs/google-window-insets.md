# Google 登录窗口与系统导航

[Issue #32](https://github.com/gravere/BYBUS/issues/32) 报告 microG 登录页全屏时底部按钮不能点击，
分屏后恢复。固定版本的 AssistantActivity 使用没有 inset 处理的 `auth_root` 全高布局；
容器宿主 target 35 后，系统强制的 edge-to-edge 窗口会使底部按钮进入导航区。

兼容只作用于容器 GMS 中继承该旧 AssistantActivity 的已知布局，在目标 onCreate 后
读取当前窗口的系统栏、刘海和键盘 Insets。根据根布局与窗口的实际重叠补足 padding，
不重复添加系统已经避让的空间，不累加上次 padding；旋转、分屏和键盘变化时重新计算。
保留已有 padding，向子布局传递扣除已处理区域的 Insets。游戏、现代 Compose 结算页
和自行处理 Insets 的设置布局不套用旧登录页补偿。

依据：[Android 窗口 Insets](https://developer.android.com/develop/ui/views/layout/edge-to-edge)、
[microG 登录布局](https://github.com/microg/GmsCore/blob/v0.3.17.252432/play-services-core/src/main/res/layout/login_assistant.xml)。
