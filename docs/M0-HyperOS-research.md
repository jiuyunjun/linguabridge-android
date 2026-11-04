# M0 空树：HyperOS 资料核对

日期：2026-11-04。结论：有与 HyperOS / 微信无障碍差异化访问相关的线索，**未证实本机根因，未找到小米官方说明本版本统一屏蔽微信节点**。

## 本机证据

- Xiaomi 25128PNA1C；adb 属性读取到 HyperOS `OS4.0.5.0.XPACNXM`，Fixture 运行时 androidSdk 37；微信 8.0.80 / 3220。
- 用户确认是普通微信单聊，有已发送文字气泡。
- adb UiAutomation 仅得到一个 `[0,0][0,0]` 空节点，正文/描述为空。
- 普通 App 服务直连、没有检查条覆盖层：4 次根探测均不可见、className null、childCount 0；3 次清节点缓存返回 true。`serviceFlags=16`、`capabilities=1`。
- 临时包含非重要视图（flags=18）后再测，4 次根探测仍相同、0 个输出节点，无本地敏感子树排除、无预算截断；源码通过 finally 恢复原 flags。系统 dump 未输出可用于独立核验恢复值的 flags 字段，不能把 dump 查询写作恢复验证通过。
- 只读 `enabled_accessibility_services`：本检查器已启用，TalkBack 和 `MiuiEnhanceTBService` 未启用。系统无障碍 dump 未提到后者。`dumpsys package com.miui.accessibility` 提到该包，但未找到该组件名；**这不足以断定组件不存在**。
- 未修改安全设置、启用隐藏服务、授予 WRITE_SECURE_SETTINGS 或更改 isAccessibilityTool。

## 外部证据与边界

1. [GKD Discussion #1448](https://github.com/orgs/gkd-kit/discussions/1448)：2026-09-04 用户报告 HyperOS 4.0 Beta + 微信 8.0.78 无效，10 月另有用户报告。将原因归为小米关闭微信无障碍的是评论猜测，没有官方确认或源码证明。与本机版本系列相近，值得关注，不能当作结论。
2. [Andee 开发者实测](https://github.com/kuafuai/andee/blob/main/README.zh-CN.md)：报告小米平板 5、MIUI、微信 8.0.78 上，普通客户端和 uiautomator 均为空壳；TalkBack 配套服务启用时才得到完整树。其[源代码](https://github.com/kuafuai/andee/blob/main/app/src/main/java/net/kuafuai/andee/screen/TreeHelper.kt)指向 `com.miui.accessibility.enhance.tb.MiuiEnhanceTBService`。这是其他硬件/系统版本的开发者报告，并未在本机复现。
3. [Mobilerun Issue #142](https://github.com/droidrun/mobilerun-portal/issues/142)：vivo 用户也报告普通客户端空树、TalkBack 开启后树恢复。因此同类现象并非仅在小米社区出现，不能直接将根因归为 HyperOS 独有行为。
4. [Android View 官方文档](https://developer.android.com/reference/android/view/View#ACCESSIBILITY_DATA_SENSITIVE_YES)：Android 允许敏感视图限制普通无障碍客户端访问。这说明客户端差异化访问具有平台机制，但不证明本机微信使用了该机制或它造成了当前空树。
5. [小米官方无障碍使用说明](https://www.mi.com/global/support/faq/details/KA-728047/)介绍已下载服务与 TalkBack，提示不同机型/系统版本功能与路径有差异；未说明本机微信空树原因。

## 判断与下一步

本机普通服务和 adb 路径目前都没有可用气泡，M0 阻断；在已测试路径下纯无障碍方案暂不可行，完整兼容性仍 UNVERIFIED。现有证据支持优先做“系统读屏与普通客户端”的对照，而不是继续猜资源 ID 或仅增加遍历参数。

建议用户手动开启系统 TalkBack，仅在合成测试聊天中对照其能否朗读已发送消息；如用户授权，再由现有单次检查器取一份同页脱敏结果。若恢复节点，可证明读屏服务状态与暴露结果存在关联，但仍需区分微信自身判断与 ROM 行为；若仍为空，继续保留不确定性。此处仅提出测试，没有自动启用系统服务、切换设置或读取新内容。
