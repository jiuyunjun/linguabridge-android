# M0 本轮开发报告

日期：2026-11-04（Asia/Tokyo）。范围：M0 检查工具实现；**M0 真机阻断门槛尚未通过**，不进入 M1。

本轮实现：
- 从仅含 AGENTS.md / design.md 的目录建立 Android 单 application module、Gradle Wrapper 与 Compose Debug 页面。
- Debug 无障碍服务：默认关闭、App 授权与当前测试单聊二次人工确认、单次读取、60 秒授权超时、串行队列、generation 失效、前后活动根包名/窗口检查、锁屏与服务中断处理。
- 有界、不可变节点观测；排除编辑/密码/敏感与外包节点子树；重复文字保留；所有发送方 UNKNOWN，不产生发送时间或消息身份。
- 原文仅在内存；手动替代文字导出到应用私有目录，默认 `[REDACTED]`、无原文回填、支持删除。无网络权限，无 OpenAI 调用。
- Release 无检查服务/界面/资源/导出代码；关闭云备份与设备迁移；新增构建说明、脱敏流程和真机检查表。
- 无真实微信规则；适配器保持 UNVERIFIED，不将调试树中的标题、工具栏或日期当作消息。

已验证（实际执行）：

| 目录 | 命令 | 结果 |
|---|---|---|
| android | 本地 Gradle 8.13 `wrapper --gradle-version 8.13 --distribution-type bin` | PASS，生成 Wrapper，配置官方 distribution SHA256 |
| android | `.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease --console=plain` | 最终 PASS，BUILD SUCCESSFUL in 22s |
| android | `testDebugUnitTest`（上行任务） | PASS：14 tests，0 failures，0 errors，0 skipped |
| android | `assembleDebug` / `assembleRelease`（上行任务） | PASS；Debug APK 有签名，Release 为 unsigned 占位应用 |
| android | `lintDebug`（上行任务） | PASS：0 errors、7 warnings，全部为 SDK / 工具链 / 依赖新版本提示；无 baseline 或错误抑制 |
| 仓库根目录 | `.\scripts\verify-apks.ps1` | PASS：Debug 签名、服务声明及资源；Release 不含检查声明、资源或 DEX 类；两者无 INTERNET / SYSTEM_ALERT_WINDOW 权限 |
| 仓库根目录 | `adb devices -l`（开始及结束均执行） | 命令成功，设备列表为空 |
| — | `adb install`、真机启用、微信双会话/双向滚动 | 未执行：无连接设备 |
| — | `go test ./...` | 未执行：M0 无 Go 模块，代理属于 M3 |

首次组合构建中 Debug/Release 与当时测试通过，但 `lintDebug` 因本机 `local.properties` 的 Windows 盘符未转义而 FAIL；修复该忽略文件后重跑通过。后续补齐备份排除与弱引用管理，再次运行上表最终组合命令通过。

单元测试覆盖：关闭/其他包拒读；敏感、不可见与外包子树排除；重复文本独立保留；节点、深度、单字段和总字符上限；大量空子节点访问限制；停止/窗口切换 generation 失效；读取异常释放根节点；读取中授权撤销不提交部分树；正文/描述默认脱敏；未知结构适配器拒绝解析。

产物 SHA256：

```text
android/app/build/outputs/apk/debug/app-debug.apk
8872615CCCF94445D252CF5A0C38CABB616F178D9D560B8CD54AE6FD973F8230

android/app/build/outputs/apk/release/app-release-unsigned.apk
CE52527A3802788BEF8A71966DA6EA2D37D1229D741F24CBD42054DFC3C3E152
```

已知限制：
- 微信真机测试：**UNVERIFIED**。设备型号、Android 版本、微信版本均未提供；没有真实 Fixture。
- 未验证 adb 安装、手动启用、覆盖层/活动根语义、真实单聊识别、双方区分、输入框排除或滚动锚点；合成测试不能证明这些设备行为。
- 人工确认只为可行性检查授权，不替代后续可靠解析器。检查器可观察非消息结构，不将其持久化为消息。没有证据时不会自动开始正式采集。
- minSdk 26 暂定；compileSdk / targetSdk 35，未声称任何 Android 或微信版本兼容性。
- 本阶段没有 Room、序列合并、会话自动关联或翻译；M0 验收框保持未勾选。

下一步：
- 用户连接目标设备并按 `docs/M0-device-checklist.md` 执行；提供标注设备、Android / 微信版本及用例的**手动脱敏 Debug Fixture**。
- 分析两个单聊、重复文本、双方、输入框与上下慢滚的实际结构；用证据决定继续纯无障碍路线，或记录“纯无障碍方案暂不可行”。通过 M0 真机门槛后再实施 M1。

## 后续设备安装与首次导出分析（同日）

本轮实现：
- 用户连接 Xiaomi 25128PNA1C 后，adb 定向安装与启动初版成功；用户报告已导出。
- 取回 `files/fixtures/m0-fixture.json` 到忽略目录 `captures/m0-device-export-20261104.json`。316 字节、schemaVersion 1、节点数 0、未截断。文件运行时记录 androidSdk 37；adb 微信包信息为 8.0.80 / versionCode 3220。没有输出或记录聊天正文。
- 原导出没有根节点过滤原因，**无法判断是不可见、敏感节点或其他问题**；不将其解释成“微信不暴露气泡”。
- 增加 `0.1-m0.1`（versionCode 2）诊断：访问节点计数、排除子树原因/数量、根节点排除原因；不读取被排除节点文字、描述或子树。空结果明确提示不可用，schemaVersion 2 保留原因，过滤规则未放宽。

已验证：
- `android/.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease --console=plain`：PASS，24s；18 项单元测试、0 failures、0 errors；Lint 0 errors、7 个版本提示。
- `.\scripts\verify-apks.ps1`：PASS，两种构建权限/隔离检查及 Debug 签名有效。
- `adb -s c073b16f install -r android/app/build/outputs/apk/debug/app-debug.apk`：PASS（实际执行使用绝对 APK 路径），Success。
- `adb -s c073b16f shell am start -n dev.linguabridge.debug/dev.linguabridge.ui.InspectorActivity`：PASS。
- `adb -s c073b16f shell dumpsys package dev.linguabridge.debug`：确认已安装 versionCode 2 / versionName 0.1-m0.1。
- `go test ./...`：未执行，M0 无 Go 模块。
- 微信气泡/滚动/输入框真机验收：UNVERIFIED；仅验证安装、启动与可取回空导出，不能替代消息可行性验收。

更新产物 SHA256：

```text
Debug:   83AC7E13E4CE4EFE2D614CE735F8BC76A587667AB9F7468E6E28802527C0E3AD
Release: 5CE99D10489C66E2A5B8274AE47E76B98F523D0E59C2DA45B1D83C83D856EB93
```

已知限制：初版空结果无法回溯过滤原因；尚未获得新版诊断或真实可用节点，不加入微信结构适配规则。

下一步：用户使用已安装新版，在当前纯文本测试单聊重新启动、确认、检查并导出。取回 schemaVersion 2 的诊断后再决定如何处理；如系统标记为敏感，不绕过该标记。

## 第二份诊断与遍历修复（同日）

本轮实现：
- 取回新版导出至忽略目录 `captures/m0-device-diagnostic-20261104.json`。设备 Xiaomi 25128PNA1C、运行时 androidSdk 37、微信版本沿用已读取的 8.0.80。
- 诊断为 schemaVersion 2 / 0.1-m0.1：`visitedNodes=1`、`rootExclusion=NOT_VISIBLE`、`excludedSubtrees.NOT_VISIBLE=1`、`nodes=[]`、`truncated=false`。证据定位到检查器过早跳过不可见根节点的整棵树；尚不能判断后代是否可见。
- 修复为 0.1-m0.2 / versionCode 3 / schemaVersion 3：不可见节点不读正文/描述，但可在预算内检查安全后代；敏感与外包节点仍阻断整棵子树，并优先于可见性检查。不可见节点也消耗遍历预算。增加单独的不可见节点、子节点查询和空返回计数；UI 区分跳过正文与整棵子树排除。
- 新增回归测试：不可见根下可见后代、多个不可见容器、不可见敏感容器仍阻断后代、不可见容器受节点/深度预算约束。

已验证：
- android 目录：`.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease --console=plain`：PASS，13s；21 tests，0 failures，0 errors；Lint 0 errors、7 个版本提示。
- 仓库根目录：`.\scripts\verify-apks.ps1`：PASS，Debug 签名及两种构建隔离/权限检查。
- `adb -s c073b16f install -r <Debug APK 绝对路径>`：PASS，Success；`adb -s c073b16f shell am start -n dev.linguabridge.debug/dev.linguabridge.ui.InspectorActivity`：PASS；`dumpsys package dev.linguabridge.debug` 确认 versionCode 3 / versionName 0.1-m0.2。
- `go test ./...`：未执行，M0 无 Go 模块。
- 微信气泡与滚动真机验收：UNVERIFIED；本轮仅用真实诊断定位遍历问题，修复后的后代结构仍需新 Fixture。

产物 SHA256：

```text
Debug:   8F4D907C53C84DE23565C0688FBAE0ADBF755722D142BBDA7F840FD4C28B732A
Release: 0E523747A55B86130AEC5B64349E73F8F15A2BDE105B192DCA0B67C9A2323F9E
```

已知限制：还未观察根节点后代，不推断微信资源 ID 或气泡容器；本修复不能视为微信适配通过。

下一步：用户在已安装的 0.1-m0.2 中对当前纯文本测试单聊重新启动、确认和导出。检查 schemaVersion 3 的可见节点与诊断后，再判断后续气泡可行性。

## 第三份设备导出：空根节点（同日）

本轮实现：
- 取回 schemaVersion 3 / 0.1-m0.2 导出，保存至忽略目录 `captures/m0-device-v3-20261104.json`，不输出聊天正文。无代码改动。
- 实际结果：`visitedNodes=1`、`invisibleNodesSkipped=1`、`childRequests=0`、`nullChildren=0`、`rootExclusion=NOT_VISIBLE`、`excludedSubtrees={}`、`nodes=[]`、`truncated=false`。
- 由当前遍历代码可确认：没有预算截断，没有敏感/外包子树阻断；根节点 `childCount` 为 0，因此没有调用子节点查询。尚不能判断空根来自页面状态、系统/微信限制或窗口访问路径，不增加臆测适配规则。

已验证：
- `adb devices -l`：PASS，目标设备仍为 Xiaomi 25128PNA1C；导出运行时 androidSdk 37。
- `adb -s c073b16f exec-out run-as dev.linguabridge.debug cat files/fixtures/m0-fixture.json`：PASS，取回本次导出。
- `adb -s c073b16f shell dumpsys accessibility`：命令成功，仅提取安全配置字段；包含检查服务组件及 `capabilities=1`、三类已配置事件、`notificationTimeout=200`。读取窗口内容能力已声明，不能据此保证微信一定暴露节点。
- `adb -s c073b16f shell dumpsys package com.tencent.mm`：确认微信 8.0.80 / versionCode 3220。
- `testDebugUnitTest` / 构建 / Lint：本轮未重跑，仅分析导出和更新文档；最近代码验证仍为 21 tests PASS、Debug/Release 构建与 Lint PASS。
- `go test ./...`：未执行，M0 无 Go 模块。
- 微信气泡/滚动真机验收：UNVERIFIED。

已知限制：当前采集路径未获得可用气泡证据；不能将这一份空根结果直接归因为微信不支持无障碍或宣称系统权限全部正常。M0 继续阻断 M1。

下一步：向用户确认本次采集是否在普通微信、前台可见的已发送纯文本单聊中，或来自分身/工作资料/隐私空间/其他页面；得到页面环境信息后再选择有依据的排查步骤，避免反复无依据改版与导出。

## 普通单聊确认、缓存探测及 adb 对照准备（同日）

用户确认先前采集时为普通微信单聊，屏幕上有已发送文字气泡；随后明确请求 adb 直接取树或直接接无障碍，愿意停留在微信页面。

本轮实现：
- 0.1-m0.3 / schemaVersion 4：最多 4 次不含正文的根元信息探测，比较清理本服务节点缓存前后结果；不可见空根时追加 500ms / 750ms 等待，正文最多遍历一次，窗口/包名/授权变化就拒绝结果。
- 新增纯 Kotlin 可替换根读取测试，验证缓存对照、有限次数、窗口变化、等候时撤销、敏感根仍阻断和节点释放。
- 新增 `scripts/adb_redacted_tree.py`：用户明确授权下的一次 adb 对照工具，前后核对微信前台，仅内存接收原始 XML；过滤输入/密码/外包子树，文字替换为不含原文的临时符号，不保存或打印原始树。
- 不增加全局监听，不加入跨窗口内容读取，不改变 isAccessibilityTool 或敏感标记。adb UiAutomation 与普通无障碍服务结果分别记录。

已验证：
- android：`.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease --console=plain`：PASS，11s；27 项测试（21+6），0 failures、0 errors；Lint PASS。
- `.\scripts\verify-apks.ps1`：PASS，Debug 签名与 Release 隔离/权限检查通过。
- `python -m unittest discover -s scripts -p test_adb_redacted_tree.py -v`：PASS，3 项脱敏测试（重复关系、输入/密码/外包排除、结构字段与遍历上限）。
- `adb devices -l`：目标 Xiaomi 25128PNA1C 连接正常；此前已确认微信 8.0.80。仅检查前台活动元信息时，显示 InspectorActivity；未在非微信页面请求 UI 树。
- `go test ./...`：未执行，M0 无 Go 模块。
- 微信气泡真机兼容性：UNVERIFIED。新的根探测及 adb 实际取树尚需用户切换页面后执行。
- `adb -s c073b16f install -r <Debug APK 绝对路径>`：PASS，Success；安装后包信息确认 versionCode 4 / 0.1-m0.3。未自动启动 Activity，避免打断用户准备中的微信页面。

产物 SHA256：

```text
Debug:   1ECACED8217773C6C64671A2D20777178CF259D391E365047D1FE891251DC301
Release: 4374090F532EEA7532BD94639E4AA542B82D6AC0E5C29B7A3A66A74D3B080CAF
```

已知限制：缓存问题仍是假设，不是已证实原因；UiAutomation 视角即使可读也不能代替普通 App 服务验收。不会在目标页面未确认时读树。

下一步：等待用户确认已停留在普通微信测试单聊并收起键盘，然后执行一次 adb 脱敏对照；无需用户再手工导出这条 adb 路径。

## adb 实际对照与普通服务直连入口（同日）

本轮实现与实际观测：
- 用户确认微信测试单聊前台后，运行 `python scripts/adb_redacted_tree.py --serial c073b16f --output captures/adb-tree-20261104.json`：命令 PASS；返回 1 节点、无截断、无子树排除。唯一节点边界 `[0,0][0,0]`，正文/描述为空，class 字段为空或不符合已允许格式，故输出 `[REDACTED_CLASS]`；无可用气泡证据。
- adb UiAutomation 与 App 服务均未获取气泡，但两条路径权限不同，不能因此直接判定原因或兼容性。没有保存或输出原始 UI XML。
- 为检查工具本身，启动自有 InspectorActivity 并以 `--self-test` 做对照：两次未得到 XML；最终安全状态 `No XML hierarchy returned; raw output withheld`。只访问自有 App，不访问其他 App，不读取微信数据库或通知，不操作微信。
- 添加仅自有 Debug 包可用的 `--self-test` 开关及其测试，不扩展为任意包采集器。
- 0.1-m0.4 / versionCode 5 新增受 `android.permission.DUMP` 保护的 Debug Receiver；用户对当前单聊明确授权后，可用 `scripts/adb_service_inspect.py --confirmed-test-chat` 一次性触发普通无障碍服务，复用全部采集/隐私控制。自动保存默认 `[REDACTED]` 结果，避免用户反复手动导出。Release 不含此入口。

已验证：
- android：`.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease --console=plain`：PASS，9s；27 项 Kotlin 测试通过，构建及 Lint PASS。
- `.\scripts\verify-apks.ps1`：PASS，检查 Debug DUMP 权限/Receiver 声明，Release 无该 Receiver、检查器或导出 DEX 类。
- `python -m unittest discover -s scripts -p test_adb_redacted_tree.py -v`：PASS，4 tests。
- `adb -s c073b16f install -r <Debug APK 绝对路径>`：PASS；包信息确认 0.1-m0.4 / versionCode 5。
- `adb -s c073b16f shell am broadcast -n dev.linguabridge.debug/dev.linguabridge.accessibility.DebugInspectReceiver -a dev.linguabridge.debug.PROBE_NO_CAPTURE`：PASS，固定无采集 action 被安全忽略，验证 shell 可达受保护 Receiver；没有读取树。真实采集 action 尚未在本轮页面确认前触发。
- `cmd package check-permission android.permission.DUMP com.android.shell 0`：设备返回 Unknown command；未以该命令推断权限。后续通过上行 no-op 广播验证 shell 可达。
- `go test ./...`：未执行，M0 无 Go 模块。
- 设备：Xiaomi 25128PNA1C，导出运行时 androidSdk 37，微信 8.0.80 / 3220；气泡级兼容性仍 UNVERIFIED。

产物 SHA256：

```text
Debug:   84491DB22023E47A0AFB83146FA383BCB2232258EEC605A482906C261B4563B6
Release: 0613A4CF5A47E3643FEC53A23D14B4815A48382BAEED852EB127C1FC5E4A10C7
```

已知限制：adb 对照没有内容，自有页面对照未返回 XML，尚不清楚平台/窗口/缓存的具体原因；不能宣称单聊解析或 M0 通过。普通服务无覆盖层模式实际读取仍待执行。

下一步：由于自有页面对照打开了检查器，已请用户返回测试单聊并确认；收到确认后，用已安装新入口直接调用服务并取回默认脱敏结果，无需用户手动导出。

## 普通服务直连结果、非重要视图对照与 HyperOS 资料核对（同日）

本轮实现：
- 用户确认返回测试单聊后，首个脚本因前台仍是 InspectorActivity 被安全拦截；活动和窗口焦点元信息双重核对后做最多 30 秒等待，实际约 3 秒内检测到微信并完成一次服务直连。没有在检查器前台读树。
- 标准直连保存 `captures/adb-service-20261104.json`：COMPLETE、0 节点、4 次同一窗口根探测，className null / 不可见 / childCount 0；3 次 clearCache 返回 true；flags=16、capabilities=1，无截断或敏感子树排除。
- 新版 0.1-m0.5 / versionCode 6 增加本次临时 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS，对照 flags=18，finally 恢复原配置；增加配置调用异常的可恢复错误处理。
- 新版安装后保持微信前台，直接运行增强诊断，保存 `captures/adb-service-include-unimportant-20261104.json`：COMPLETE、0 节点，4 次根仍不可见、className null、childCount 0，3 次 clearCache 返回 true，flags=18、capabilities=1。
- 按用户请求网上核对系统限制；本机 HyperOS OS4.0.5.0.XPACNXM。找到相似 HyperOS 4 报告与 MIUI / TalkBack 配套服务实测，未找到针对本机版本的官方屏蔽确认。只读核对本机 TalkBack/配套服务未启用；没有改变安全设置或启用隐藏服务。来源与边界详见 `docs/M0-HyperOS-research.md`。

已验证：
- android：`.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease --console=plain`：最终 PASS，18s；27 项 Kotlin 测试 0 failures/0 errors；Lint 0 errors、7 个版本提示。
- `.\scripts\verify-apks.ps1`：PASS。
- `python -m unittest discover -s scripts -p test_adb_redacted_tree.py -v`：PASS，4 tests。
- `python -m py_compile scripts/adb_service_inspect.py`：PASS。
- `adb -s c073b16f install -r <Debug APK 绝对路径>`：PASS；包信息确认 0.1-m0.5 / versionCode 6。
- `python scripts/adb_service_inspect.py --serial c073b16f --confirmed-test-chat --output captures/adb-service-20261104.json`：微信前台后 PASS，COMPLETE（空树是诊断结果，不是气泡验收通过）。
- `python scripts/adb_service_inspect.py --serial c073b16f --confirmed-test-chat --include-unimportant-views --output captures/adb-service-include-unimportant-20261104.json`：PASS，COMPLETE；实际记录 flags=18，结果仍空。
- `dumpsys accessibility` 未提供可匹配的 flags 字段，无法用该输出独立证明恢复后的值；成功结果说明 finally 中恢复调用没有抛异常，不能写作完整外部恢复验证。
- `go test ./...`：未执行，M0 无 Go 模块。
- 微信真机气泡/滚动：UNVERIFIED / M0 阻断；当前已测试纯无障碍路径暂不可行。

产物 SHA256：

```text
Debug:   1F7860518D3FEFA02F22265C7CB2C4A314C7D9396BADEB0D9D843FAA5AE191FC
Release: 1239871863FCCC69E3186F58A59C811C03CE90DB1EDCF796940BA5B4CA0F3744
```

已知限制：未取得真实气泡节点，未确认是 ROM、微信按客户端区别暴露或其他平台问题；网络报告不是本机已复现事实。没有微信资源 ID 规则、真实发送者或消息身份推断。

下一步：优先由用户手动使用系统 TalkBack 对同一合成测试单聊做可读性对照；若用户授权，再使用当前一次性服务入口取回脱敏结果。保持 UNVERIFIED，不进入 M1，不擅自改为 OCR。
