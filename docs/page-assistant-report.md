# 页面上下文助手执行报告

日期：2026-11-04。当前版本：0.3-page-assistant / versionCode 8。用户要求从微信/LINE 单聊检查改为用户主动触发的单页上下文翻译及回复助手；以 design.md 第 14 节为本期范围。旧 M0–M4 的 Room、聊天身份和滚动合并延期，不宣称旧 MVP 整体完成。

## 本轮实现

- Compose 页面：选择目标 App、单次读取、合成邮件、手动文字、逐段勾选编辑、翻译目标、回复要求、语言/页面类型、逐次上传内容审核、复制结果与清空。
- 服务空闲只订阅自身包，检查期只允许明确选择的目标包；保留锁屏、窗口/generation、串行、有界遍历和敏感子树过滤。页面节点不当作已解析消息，不编造发送者/时间/稳定 ID。
- 最多 60 段、每段 1500 Unicode 字符、上下文与用户要求合计 6000 UTF-8 字节。原文在内存；导出默认脱敏，schema 6。
- Android OkHttp 认证代理客户端、取消/超时与安全错误；Token 以 Android Keystore AES-GCM 加密；逐次确认后才请求，结果不自动填写或发送。
- 标准库 Go 代理：认证、校验、大小/速率/并发/超时限制，Responses 严格 JSON Schema 与完整 output 遍历；不可信页面与静态指令隔离。Mock 测试和明确标记的固定模拟模式，不自动调用真实模型。
- Debug 回环网络例外及 adb reverse 联调脚本；Release 继续无网络/采集的占位版本。

## 已验证

设备：Xiaomi 25128PNA1C / HyperOS OS4.0.5.0.XPACNXM / Android SDK 37。compileSdk / targetSdk 35，minSdk 26 不代表旧系统兼容性已验证。

| 实际命令 | 结果 |
| --- | --- |
| `.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease assembleDebugAndroidTest --console=plain`（android 目录） | PASS；42 个 JVM 测试，0 failure/error；Lint 无 error，仍有依赖更新/UseKtx 等 warning |
| `go test ./...`（proxy 目录） | PASS；Fake HTTP Transport，不产生真实付费请求 |
| `go vet ./...`（proxy 目录） | PASS |
| `python -m unittest discover -s scripts -p test_adb_redacted_tree.py -v` | PASS，5 项历史诊断脱敏测试 |
| `python -m py_compile scripts/adb_service_inspect.py` | PASS；新通用诊断命令尚未对第三方页面执行 |
| `.\scripts\verify-apks.ps1` | PASS；Debug 权限/签名/默认自身包/回环例外，Release 无服务、Receiver 或网络权限 |
| `adb -s c073b16f install -r ...app-debug.apk` | PASS，主 App 已安装 |
| `adb -s c073b16f install -r -t ...app-debug-androidTest.apk` | PASS；首次系统拒绝安装后，用户明确同意重试 |
| `.\scripts\stop-demo.ps1` / `.\scripts\start-demo.ps1` / `.\scripts\provision-demo-device.ps1 -Serial c073b16f` | PASS；仅本进程回环代理；设备 Token 加密配置；无页面采集 |
| `.\scripts\test-device.ps1 -Serial c073b16f` | PASS，2 个真机核心测试，无跳过：Keystore 加密读写删除；Android 客户端经认证代理执行两种合成任务并拒绝错误 Token |
| `.\gradlew.bat assembleDebugAndroidTest --console=plain`（android 目录，本次修改测试后） | PASS；更新后的辅助测试 APK 重装成功，产品 APK 未改动 |
| `.\scripts\test-device.ps1 -Serial c073b16f -Ui` | PASS，2 项、无跳过；合成邮件翻译/回复、取消及逐次上传确认、模拟标记、复制、清空后页面/结果/配置删除，以及独立 Keystore 读写删除 |

测试仅处理自有合成邮件/假凭据，没有读取新的第三方页面，没有真实 OpenAI 请求。Mock 验证请求格式/错误/指令结构，不能证明模型永不受提示注入或翻译质量。

Debug APK SHA-256：`EC8A2163ED894613505AD7AFB38F3B3EBE6DDFB78853BA1927F879217AEEA6AC`。

## 已知限制

- 微信真机测试：UNVERIFIED（历史空树，未得到可用消息，用户已停止此方向）。微信 8.0.80，不能归因为已证实的 HyperOS 系统屏蔽。
- LINE 26.3.1：历史普通服务读取 57 个可见节点、4 个文字节点的初步证据；当前通用 UI 的完整第三方流程未验收。邮件等其他 App：UNVERIFIED；不保证每个 App 都暴露节点。
- 合成邮件的模拟端到端界面已通过；不替代第三方 App 的节点获取或真实模型质量验收。脚本要求手机解锁亮屏，不自动解锁或更改安全设置。
- 真实 OpenAI、域名/证书、HTTPS 生产部署和语言质量：UNVERIFIED；手机目前只有明确标记的固定模拟结果。
- 无 OCR、历史采集、跨页拼接、Room、聊天身份、自动输入或发送。敏感节点过滤不能代替用户避开敏感页面并审核文字。

## 下一步

由用户选择一个可分享的目标页面验证实际节点。真实翻译需配置服务端 Key、模型和 HTTPS，随后由用户明确触发单次调用并评估结果。使用方式见根 README 与 proxy/README；凭据不提交仓库或写入 APK。

## 本次界面测试修复证据

用户明确解锁并保持亮屏后，仍出现测试启动等待，因此锁屏并非唯一原因。测试线程栈实际停在 `Instrumentation.startActivitySync → InstrumentationActivityInvoker.startActivity → ActivityScenario.launchInternal`，主线程在消息循环，测试正文尚未进入。该路径可对照 [AndroidX 官方实现](https://github.com/android/android-test/blob/main/core/java/androidx/test/core/app/InstrumentationActivityInvoker.java)，未确定系统内部阻塞原因，也不归因为通用 HyperOS 限制。

测试改为 createEmptyComposeRule，通过 instrumentation 的 shell 接口仅启动本 App，再检查自身 Activity 的 RESUMED 状态；其余仍为实际 Compose 控件点击/输入/断言，没有跳过任务流程，也没有修改手机安全设置。启动后发现测试误用了精确文本匹配，改为 substring=true 后，两项测试完整通过。旧 Espresso 反射异常、早期中止与等待超时均为历史失败，不隐去；最新脱敏结果保存在被忽略的 captures/device-ui-tests.log。
