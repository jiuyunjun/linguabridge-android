# 认证代理

Go 1.23+；无第三方依赖。`go test ./...` 和 `go vet ./...` 不产生真实 OpenAI 请求。

`POST /v1/assist` 使用 `Authorization: Bearer <DEVICE_TOKEN>`。operation：
- `translate_page`：整屏翻译，返回 `translations:[{id,text}]`（只引用请求中的段落、不重复；纯标签可省略），不接受 targetBlockId / userIntent。
- `translate`：只翻译 targetBlockId 一段。
- `reply`：必须有 userIntent；可选 `tone`（polite/casual/formal）；`targetLanguage` 可为 `auto`（跟随对方语言）。

语言 zh/ja/en（auto 仅 reply），页面类型 auto/chat/email/article/other。正文包含 requestId、sourcePackage、blocks（id/path/bounds/text）。具体校验见 `internal/assist/model.go`。

环境变量（`.env.example` 只是说明，程序不会自动读取 .env）：

| 变量 | 用途 |
| --- | --- |
| DEVICE_TOKEN | 随机设备 Token，32–256 个 URL 安全字符，不能用 OpenAI Key |
| OPENAI_API_KEY | 仅服务端保存，真实模式必填 |
| OPENAI_MODEL | 已核对可用的 Responses 模型 ID，真实模式必填 |
| DEMO_MODE | 只有 `1` 启用固定模拟结果；真实调用设 `0` |
| LISTEN_ADDR | 默认 `127.0.0.1:8787`，仅允许回环 IP |
| REQUEST_TIMEOUT_SECONDS | 默认 60（整屏翻译输出较长），允许 1–120 秒；Android 客户端总超时 90 秒 |

设置进程环境后运行 `go run ./cmd/server`。真实模式产生付费请求；自动测试和 start-demo.ps1 始终不使用真实引擎。服务器用固定 Responses 地址、store=false、严格 JSON Schema，无工具调用。store=false 不保证零保留。

认证、48KiB 请求体、6000 字节文字预算、序列化上游输入 18000 字节预算、每 6 秒一额度/10 次突发、2 个并发与超时均在服务端执行。上游错误返回固定安全信息，不反射原文或凭据。模型内容被当作不可信结果，禁止自动执行。

公网使用有效证书的 HTTPS 反向代理，代理到回环监听；不要暴露 HTTP。反向代理也须关闭请求正文/认证头日志，并设置请求体与超时限制。本仓库未配置实际域名、证书或真实 OpenAI 凭据，部署未验证。
