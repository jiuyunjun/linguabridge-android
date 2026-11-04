# LinguaBridge for Android（悬浮球）

开启无障碍服务后，任意 App 上会出现一个悬浮球：
- **点一下**：读取当前屏幕可见文字（本地），打开面板。默认回到上次用的标签，可在设置里固定为“翻译”或“回复”。
- **翻译**：切到翻译标签才请求整屏翻译（原文/译文对照，直连模式下逐段流式显示）。只想回复时不会产生翻译请求。
- **回复**：用中文写一句想说的话，结合屏幕上的对话生成目标语言回复；点“填入输入框”写进当前 App 的输入框（不会自动发送），写不进去时自动复制。

密码框和输入框里的文字不读；原文不保存。点悬浮球即上传当前屏幕文字到你配置的代理（再到 OpenAI），没有逐次确认——不想用的 App 请在设置里加入“不显示悬浮球”。设计与约束见 [design.md 第 15 节](design.md)，本轮验证见 [报告](docs/floating-assistant-report.md)。

## 构建

PowerShell 7、JDK 17/21、Android SDK 35。Gradle 8.13 / AGP 8.11.1 / Kotlin 2.2.21；compileSdk / targetSdk 35，minSdk 26。唯一实测设备为 Xiaomi 25128PNA1C / HyperOS OS4.0.5.0.XPACNXM / SDK 37。

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease assembleDebugAndroidTest --console=plain
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
cd ..
.\scripts\verify-apks.ps1
```

Release 目前仅为无网络、无采集服务的占位页；可用版本是 Debug APK。

Debug APK 固定用仓库内的 `android/keystore/debug.keystore`（仅调试用，公开密码 `android`）签名，换电脑重编译也能覆盖安装，不会丢失无障碍授权和已保存的代理 Token。不要把它用于 Release。

## 调试：直连 OpenAI（不经代理）

复制 `android/openai.properties.example` 为 `android/openai.properties`（已被 git 忽略），填 `OPENAI_API_KEY` 和 `OPENAI_MODEL`，重新 `assembleDebug` 并安装。有 Key 时悬浮球直接调用 `https://api.openai.com/v1/responses`（SSE 流式输出），设置页显示“OpenAI 直连（调试）”，可点“更换模型”从 `/v1/models` 拉取聊天模型列表切换；清空 Key 再编译即回到代理模式。

**Key 会被编译进 Debug APK**，只装在自己手机上，不要分享该 APK。Release 不包含此功能（`verify-apks.ps1` 会检查）。

## 本地模拟体验

Go 代理使用标准库。根目录执行：

```powershell
.\scripts\start-demo.ps1
.\scripts\provision-demo-device.ps1 -Serial <serial>
.\scripts\test-device.ps1 -Serial <serial>
# 设置页界面测试；须解锁并保持亮屏，60 秒主机超时判失败
.\scripts\test-device.ps1 -Serial <serial> -Ui
# 停止本脚本拥有的模拟代理
.\scripts\stop-demo.ps1
```

模拟代理只绑定 127.0.0.1:8787，经 adb reverse 连接；独立设备 Token 保存在被忽略的本地文件，通过 Debug DUMP 入口写入手机 Keystore 加密配置。脚本不采集页面。模拟输出明确标注“固定模拟结果，未调用 OpenAI”，不代表翻译质量。

手机打开 LinguaBridge 配置代理与语言 → 系统设置里开启“LinguaBridge”无障碍服务 → 打开任意聊天/邮件 App，点右侧悬浮球。模拟代理只返回固定文字（“固定演示译文 b1”等），面板会标注“固定模拟结果 · 未调用 OpenAI”。

## 真实服务端

详见 [代理说明](proxy/README.md)。OpenAI Key 和模型 ID 只由服务器环境变量配置，Android 只保存独立设备 Token。公网必须由有效 HTTPS 反向代理承接；Debug HTTP 仅允许 localhost / 127.0.0.1。尚未调用真实 OpenAI，效果与生产部署均未验证。

## 安全边界

排除编辑、密码、输入法及系统标记敏感的节点/子树；系统界面、输入法、桌面和本 App 不显示悬浮球。每次点击只读一次当前前台 App 的新鲜窗口，最多 60 段、每段 1500 字符、合计 6000 UTF-8 字节。屏幕文字只在打开的面板内存中，关闭或切换 App 即清除。回复只有在你点“填入输入框”时写入，不点发送。清空会删除配置、加密 Token 与私有脱敏导出。

诊断导出（不保存原文，需目标 App 在前台）：

```powershell
python scripts/adb_service_inspect.py --serial <serial> --package <foreground.package> --confirmed-page --output captures/page-redacted.json
```
