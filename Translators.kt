package com.gametranslator

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** แปลง Task ของ Google ให้ใช้กับ coroutine ได้ */
suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

class Translators(private val prefs: Prefs) {

    companion object {
        const val CLAUDE_MODEL = "claude-haiku-5-5"
    }

    private var mlkitClient: Translator? = null

    private fun mlkit(): Translator = mlkitClient ?: Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.THAI)
            .build()
    ).also { mlkitClient = it }

    /** เตรียมตัวแปล (ML Kit ต้องโหลดโมเดลภาษาไทยครั้งแรกครั้งเดียว) */
    suspend fun prepare() {
        if (prefs.engine == 0) {
            mlkit().downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
        }
    }

    fun close() {
        runCatching { mlkitClient?.close() }
    }

    suspend fun translate(text: String, names: List<String>): String = when (prefs.engine) {
        1 -> gemini(text, names)
        2 -> claude(text, names)
        else -> mlkitTranslate(text, names)
    }

    // ---------- ชื่อที่ไม่ต้องแปล ----------
    private fun system(names: List<String>): String {
        val keep = if (names.isEmpty()) "(none)" else names.joinToString(", ")
        return "You translate video game text from English to Thai. " +
            "Output only the translation, nothing else. Keep a natural game-dialogue tone. " +
            "Never translate or transliterate these character names, keep them exactly as written: $keep. " +
            "The text comes from OCR so it may contain small errors; fix them from context."
    }

    private fun mask(text: String, names: List<String>): Pair<String, Map<String, String>> {
        var t = text
        val map = LinkedHashMap<String, String>()
        names.sortedByDescending { it.length }.forEachIndexed { i, name ->
            val re = Regex("\\b" + Regex.escape(name) + "\\b", RegexOption.IGNORE_CASE)
            if (re.containsMatchIn(t)) {
                val token = "XQ${i}QX"
                t = re.replace(t, token)
                map[token] = name
            }
        }
        return t to map
    }

    private fun unmask(text: String, map: Map<String, String>): String {
        var out = text
        for ((token, name) in map) {
            out = Regex(Regex.escape(token), RegexOption.IGNORE_CASE)
                .replace(out, Regex.escapeReplacement(name))
        }
        return out
    }

    // ---------- ML Kit (ออฟไลน์) ----------
    private suspend fun mlkitTranslate(text: String, names: List<String>): String {
        val (masked, map) = mask(text, names)
        val out = mlkit().translate(masked).awaitResult()
        return unmask(out, map)
    }

    // ---------- Gemini ----------
    private suspend fun gemini(text: String, names: List<String>): String {
        val key = prefs.geminiKey
        require(key.isNotBlank()) { "ยังไม่ได้ใส่ Gemini API key" }
        val model = prefs.geminiModel.ifBlank { "gemini-2.5-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val headers = mapOf("x-goog-api-key" to key)

        fun body(withThinking: Boolean): JSONObject = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(names))))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            )
            if (withThinking) {
                // ปิด thinking เพื่อให้ตอบเร็ว (บางรุ่นไม่รองรับ จะลองใหม่ด้านล่าง)
                put(
                    "generationConfig",
                    JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                )
            }
        }

        var (code, resp) = postJson(url, headers, body(true))
        if (code == 400) {
            val retry = postJson(url, headers, body(false))
            code = retry.first
            resp = retry.second
        }
        if (code !in 200..299) error("Gemini $code: ${errMsg(resp)}")

        val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        return buildString {
            for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
        }.trim()
    }

    // ---------- Claude ----------
    private suspend fun claude(text: String, names: List<String>): String {
        val key = prefs.claudeKey
        require(key.isNotBlank()) { "ยังไม่ได้ใส่ Anthropic API key" }
        val body = JSONObject()
            .put("model", CLAUDE_MODEL)
            .put("max_tokens", 500)
            .put("system", system(names))
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", text))
            )
        val (code, resp) = postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
            body
        )
        if (code !in 200..299) error("Claude $code: ${errMsg(resp)}")

        val content = JSONObject(resp).getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) append(content.getJSONObject(i).optString("text"))
        }.trim()
    }

    // ---------- HTTP ----------
    private suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun errMsg(resp: String): String = try {
        val o = JSONObject(resp)
        val e = o.opt("error")
        when (e) {
            is JSONObject -> e.optString("message", resp)
            is String -> e
            else -> resp
        }.take(160)
    } catch (e: Exception) {
        resp.take(160)
    }
}
