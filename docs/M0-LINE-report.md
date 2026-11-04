# LINE M0 本轮报告

日期：2026-11-04（Asia/Tokyo）。范围：按用户要求停止微信，改为 LINE 单目标可行性检查。

本轮实现：
- 仅 LINE 包 `jp.naver.line.android`：服务订阅、根/子节点门禁、新鲜根校验、Debug Receiver、UI 说明、主机前台校验同步切换；不接受任意其他包，微信被明确拒绝。
- 新版 0.2-m0-line / versionCode 7。schemaVersion 5 记录目标与应用版本；导出仍默认 `[REDACTED]`，原文不上云、不落原始文件、不打印日志。
- `LineUiAdapter` 继续返回 Unverified，没有根据 ID 名称直接构造气泡、发送者或时间；没有引入微信规则。
- 新增回归测试：旧微信根/嵌套子树拒读、提交前切回微信丢弃、主机工具拒绝旧微信目标。Release 与权限边界保持一致。

已验证（实际命令）：

| 命令 | 结果 |
|---|---|
| android：`.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease --console=plain` | PASS，14s；30 tests（23+7），0 failures、0 errors；Lint 与两种构建通过 |
| `.\scripts\verify-apks.ps1` | PASS；新增核对服务 XML 仅 LINE、没有微信包名；签名、Release 隔离与权限检查通过 |
| `python -m unittest discover -s scripts -p test_adb_redacted_tree.py -v` | PASS，5 tests |
| `python -m py_compile scripts/adb_service_inspect.py` | PASS |
| `adb -s c073b16f shell dumpsys package jp.naver.line.android` | LINE 26.3.1 / 260310142，minSdk 30、targetSdk 36（这是 LINE 的配置，不是本 App） |
| `adb -s c073b16f install -r <Debug APK 绝对路径>` | PASS，Success；未自动启动 Activity，保持用户 LINE 页面 |
| `python scripts/adb_redacted_tree.py --serial c073b16f --target line --output captures/line-adb-tree-20261104.json` | 命令完成，但无可用节点：1 个子树被过滤，原因未记录；不作为 LINE 服务可行性的正/负结论 |
| `python scripts/adb_service_inspect.py --serial c073b16f --target line --confirmed-test-chat --output captures/line-service-20261104.json` | 首次 SERVICE_NOT_CONNECTED；只读确认系统仍启用服务、重试后 COMPLETE，返回 57 个可见节点 |
| `go test ./...` | 未执行，M0 无 Go 模块 |

真机证据：
- Xiaomi 25128PNA1C，HyperOS OS4.0.5.0.XPACNXM，Fixture 运行时 SDK 37；用户明确确认普通 LINE 测试单聊，前台活动为 ChatHistoryActivity。
- 标准 flags=16、capabilities=1；刷新前后根可见、FrameLayout、childCount 13，无需临时包含非重要视图。
- 访问 67 节点，输出 57 可见节点；跳过 9 个不可见节点正文、排除 1 个 EDITABLE 子树，66 次子节点查询均非空，未截断。
- 14 节点有 text 字段、24 节点有 description 字段；主机确认非默认正文为 0，保存的文字均为 `[REDACTED]`。
- Fixture 中 4 个 `jp.naver.line.android:id/chat_ui_message_text`，4 个 `chat_ui_row_timestamp`，2 个 `chat_ui_date_indicator_text`，另有 header、已读数、链接预览结构。均来自本次真实结构；仅记录观测，不作为跨版本稳定规则或消息唯一 ID。
- 4 个消息文本候选均具有宽高大于 0 的屏幕 bounds；本版 Lint 0 errors、7 个版本提示。安装后包信息确认 0.2-m0-line / versionCode 7。
- 脱敏样本保存在忽略目录 `captures/line-service-20261104.json`，没有擅自提升为可提交原始 Fixture。

产物 SHA256：

```text
Debug:   D9A42735B3C35BF7BA6689F2A2BC238DB7BA9ADC938E9AE55A5E5485E1112B01
Release: 4F6F377FDEA3DDA161D6DF43D6B78ECFE528A68E07DF2CB8E931DEBC719450E0
```

已知限制：
- **初步可行**：已证实当前普通服务可读 LINE 页面结构和消息文本字段，不等于完整聊天采集或 M0 完成。
- 正文已默认脱敏，本轮未判断语义/重复关系；双方归属、气泡子树分组、两个会话、上下慢滚和专门的草稿排除测试未完成。
- 完整 LINE 气泡适配与滚动兼容性仍 UNVERIFIED；没有 Room、序列合并或翻译。不继续微信采集，微信历史原因仍未知。

下一步：使用合成 LINE 测试消息，按 `docs/M0-LINE-checklist.md` 完成双方、重复 OK、长文本、输入框草稿、两会话和上下滚动 Fixture，再确定基于证据的适配规则与是否通过 M0。
