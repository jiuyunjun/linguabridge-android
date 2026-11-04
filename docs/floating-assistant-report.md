# 悬浮球助手执行报告

日期：2026-11-04。版本：0.4-floating-assistant / versionCode 9。范围以 design.md 第 15 节为准。

## 本轮实现

- 无障碍悬浮球（TYPE_ACCESSIBILITY_OVERLAY，无悬浮窗权限）：任意合格前台 App 常驻；可拖动并记忆位置，长按临时隐藏到切换 App；系统 UI、设置、输入法、桌面、本 App 和用户隐藏列表中的 App 不显示。
- 点悬浮球：读取一次当前前台 App 的新鲜窗口（沿用敏感/可编辑子树排除和大小上限），提取屏幕文字，直接调用代理 `translate_page`，面板逐段显示原文/译文，可复制。
- 回复标签：输入意图 + 语气（礼貌/随意/正式）→ 代理 `reply`（语言可“跟随对方”）→ 草稿；“填入输入框”用 ACTION_SET_TEXT 写入，失败时复制并提示；不点发送。
- 设置页重写：无障碍状态、代理配置、翻译/回复语言、悬浮球开关、隐藏 App 列表、清空。旧的单页审核/确认流程已删除。
- Go 代理：新增 `translate_page`（`translations` 校验）、`tone`、reply 的 `auto` 语言；整屏输出 6000 tokens，上游请求上限 18000 字节，默认超时 60 秒（Android 90 秒）。
- 文档：design.md 第 15 节、AGENTS.md 当前范围说明、README、proxy/README。

## 已验证

| 实际命令 | 结果 |
| --- | --- |
| `go vet ./...`、`go test ./...`（proxy） | PASS；新增整屏翻译解析、非法 ID/重复/空结果、tone/auto 校验、Demo 整屏测试；全部 Fake Transport，无真实调用 |
| `.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease assembleDebugAndroidTest --console=plain`（android） | PASS；45 个 JVM 测试，0 失败；Lint 0 error（UseKtx/依赖版本等 warning） |
| `.\scripts\verify-apks.ps1`（Windows PowerShell 需先 `Add-Type -AssemblyName System.IO.Compression.FileSystem`） | PASS；Debug 服务无预设包名、无 SYSTEM_ALERT_WINDOW；Release 不含服务/网络 |
| `python -m unittest discover -s scripts -p test_adb_redacted_tree.py` | PASS |
| `adb devices` | 无设备连接 |

- 微信真机测试：UNVERIFIED（方向已停止）
- 悬浮球 / 整屏读取 / 面板输入 / 填入输入框真机测试：**UNVERIFIED**（本轮无设备）
- `test-device.ps1`（CoreDeviceTest、SettingsSmokeTest）：未执行（无设备）；测试 APK 已能编译
- 真实 OpenAI 调用与翻译质量：UNVERIFIED

## 已知限制

- 点击即上传，无逐次确认（用户明确选择）；隐私依赖隐藏列表。
- 整屏文字会混入标题、按钮等；只过滤纯数字/时间/符号。
- ACTION_SET_TEXT 覆盖输入框原有内容；部分 App 可能拒绝。
- HyperOS 上无障碍浮层的输入法行为、微信空树问题均未复测。

## 下一步

连接测试机后：安装 Debug APK → 开启无障碍 → `start-demo.ps1` + `provision-demo-device.ps1` → 在 LINE 等 App 上点悬浮球验证整屏读取、面板输入法、填入；再运行 `test-device.ps1` 与 `-Ui`。之后配置真实代理验证模型质量。
