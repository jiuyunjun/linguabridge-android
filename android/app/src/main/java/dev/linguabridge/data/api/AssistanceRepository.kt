package dev.linguabridge.data.api

import dev.linguabridge.domain.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class ProxySettings(val baseUrl: String, val deviceToken: String) {
    fun validate(allowDebugLoopback: Boolean) {
        val url = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("代理地址格式无效")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") { "请填写不含账号、路径或参数的代理根地址" }
        require(url.host != "openai.com" && !url.host.endsWith(".openai.com")) { "请填写自己的认证代理地址，不能直接连接 OpenAI" }
        require(url.isHttps || (allowDebugLoopback && url.scheme == "http" && url.host in setOf("127.0.0.1", "localhost"))) { "代理必须使用 HTTPS；Debug 仅允许本机回环 HTTP" }
        require(deviceToken.matches(Regex("[A-Za-z0-9_-]{32,256}")) && !deviceToken.startsWith("sk-")) { "请填写独立的代理设备 Token，不能填 OpenAI 密钥" }
    }
}

sealed interface AssistOutcome {
    data class Success(val result: AssistResult) : AssistOutcome
    data class Error(val message: String) : AssistOutcome
}

class AssistanceRepository(
    private val allowDebugLoopback: Boolean = false,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(85, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
) {
    suspend fun generate(settings: ProxySettings, input: AssistRequest): AssistOutcome {
        try { settings.validate(allowDebugLoopback); input.validate() }
        catch (invalid: IllegalArgumentException) { return AssistOutcome.Error(invalid.message ?: "输入无效") }
        val root = JSONObject().apply {
            put("requestId", input.requestId); put("operation", input.operation.name.lowercase())
            put("targetLanguage", input.targetLanguage); put("pageType", input.pageType)
            put("sourcePackage", input.sourcePackage); put("targetBlockId", input.targetBlockId)
            put("userIntent", input.userIntent)
            if (input.tone.isNotEmpty()) put("tone", input.tone)
            put("blocks", JSONArray().apply { input.blocks.forEach { block -> put(JSONObject().apply {
                put("id", block.id); put("path", block.path); put("text", block.text)
                put("bounds", JSONArray(listOf(block.bounds.left, block.bounds.top, block.bounds.right, block.bounds.bottom)))
            }) } })
        }
        val url = settings.baseUrl.toHttpUrlOrNull()!!.newBuilder().addPathSegments("v1/assist").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${settings.deviceToken}")
            .post(root.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(AssistOutcome.Error(
                        if (e is java.io.InterruptedIOException) "请求超时；上下文仍保留，可手动重试" else "无法连接代理，请检查地址和网络；上下文仍保留"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val outcome = response.use {
                        if (!it.isSuccessful) AssistOutcome.Error(when (it.code) {
                            401, 403 -> "代理认证失败，请检查设备 Token"
                            400, 413, 415 -> "代理拒绝输入，请缩短上下文并核对配置"
                            422 -> "模型未提供结果，请调整请求后手动重试"
                            429 -> "请求过于频繁，请稍后手动重试"
                            408, 504 -> "代理处理超时；上下文仍保留，可手动重试"
                            else -> "代理暂时不可用；上下文仍保留，可手动重试"
                        }) else try {
                            val source = it.body?.source() ?: throw IOException()
                            source.request(65537)
                            require(source.buffer.size <= 65536)
                            val bytes = source.readByteArray()
                            val json = JSONObject(String(bytes, Charsets.UTF_8))
                            require(json.get("requestId") is String && json.getString("requestId") == input.requestId)
                            require(json.get("text") is String && json.get("notes") is String && json.get("needsClarification") is Boolean && json.get("demo") is Boolean)
                            val text = json.getString("text"); val notes = json.getString("notes")
                            val needs = json.getBoolean("needsClarification")
                            val questions = json.getJSONArray("questions").let { array ->
                                require(array.length() <= 3)
                                List(array.length()) { index -> require(array.get(index) is String); array.getString(index).also { q -> require(q.isNotBlank() && q.codePointCount(0, q.length) <= 300) } }
                            }
                            require(text.codePointCount(0, text.length) <= 8000 && notes.codePointCount(0, notes.length) <= 1000)
                            require(!needs || questions.isNotEmpty())
                            val translations = json.getJSONArray("translations").let { array ->
                                require(array.length() <= input.blocks.size)
                                val known = input.blocks.map { b -> b.id }.toSet()
                                List(array.length()) { index ->
                                    val item = array.getJSONObject(index)
                                    require(item.length() == 2 && item.get("id") is String && item.get("text") is String)
                                    BlockTranslation(item.getString("id"), item.getString("text")).also { t ->
                                        require(t.id in known && t.text.isNotBlank() && t.text.codePointCount(0, t.text.length) <= 8000)
                                    }
                                }.also { list -> require(list.map { t -> t.id }.distinct().size == list.size) }
                            }
                            if (input.operation == AssistOperation.TRANSLATE_PAGE) require(!needs && translations.isNotEmpty())
                            else require(translations.isEmpty() && (needs || (text.isNotBlank() && questions.isEmpty())))
                            require(json.get("model") is String)
                            val model = json.getString("model"); require(model.isNotBlank() && model.length <= 200)
                            AssistOutcome.Success(AssistResult(text, notes, needs, questions, model, json.getBoolean("demo"), translations))
                        } catch (_: java.io.InterruptedIOException) { AssistOutcome.Error("读取结果超时；上下文仍保留，可手动重试")
                        } catch (_: Exception) { AssistOutcome.Error("代理返回格式错误；上下文仍保留，可手动重试") }
                    }
                    if (continuation.isActive) continuation.resume(outcome)
                }
            })
        }
    }
}
