package com.example.deepseek.console

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.deepseek.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Один и тот же запрос с разным temperature (0, 0.7, 1.2).
 * Сравнение по точности, креативности, разнообразию и рекомендации по настройке.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8))

    val apiKey = BuildConfig.DEEPSEEK_API_KEY
    if (apiKey.isBlank()) {
        System.err.println("Ошибка: задайте deepseek.api.key в api.properties или local.properties")
        System.exit(1)
    }

        //  val prompt = "Сколько пивных бутылок поместится в пакет"
    val prompt = "что есть в домике колобка?"


    val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val sep = "=" .repeat(60)

    println(sep)
    println("ЗАПРОС (один и тот же для всех трёх вызовов)")
    println(sep)
    println(prompt)
    println()

    // Запрос с temperature = 0
    println(sep)
    println("1. ОТВЕТ при temperature = 0")
    println(sep)
    val resp0 = requestWithTemperature(client, apiKey, prompt, temperature = 0f)
    println(resp0 ?: "(нет ответа)")
    println()

    // Запрос с temperature = 0.7
    println(sep)
    println("2. ОТВЕТ при temperature = 0.7")
    println(sep)
    val resp07 = requestWithTemperature(client, apiKey, prompt, temperature = 0.7f)
    println(resp07 ?: "(нет ответа)")
    println()

    // Запрос с temperature = 1.2
    println(sep)
    println("3. ОТВЕТ при temperature = 1.2")
    println(sep)
    val resp12 = requestWithTemperature(client, apiKey, prompt, temperature = 1.2f)
    println(resp12 ?: "(нет ответа)")
    println()

    // Сравнение и рекомендации от модели
    println(sep)
    println("СРАВНЕНИЕ И РЕКОМЕНДАЦИИ (анализ модели)")
    println(sep)
    val comparisonPrompt = """
        Ниже один и тот же запрос к модели и три ответа при разных значениях temperature (0, 0.7, 1.2).

        Запрос: $prompt

        Ответ 1 (temperature = 0):
        ${resp0 ?: "(нет ответа)"}

        Ответ 2 (temperature = 0.7):
        ${resp07 ?: "(нет ответа)"}

        Ответ 3 (temperature = 1.2):
        ${resp12 ?: "(нет ответа)"}

        Выполни по пунктам:
        1) Сравни ответы по точности (насколько фактологично и последовательно).
        2) Сравни по креативности (идеи, формулировки).
        3) Сравни по разнообразию (насколько ответы отличаются друг от друга).
        4) Сформулируй, для каких задач лучше подходит каждая настройка: temperature = 0, temperature = 0.7, temperature = 1.2 (приведи примеры типов задач).
    """.trimIndent()

    val comparison = requestWithTemperature(client, apiKey, comparisonPrompt, temperature = 0.3f, system = "Ты эксперт по оценке текстов и настройке языковых моделей. Отвечай структурированно и по делу.")
    println(comparison ?: "(нет ответа)")
    println()
    println(sep)
    println("Готово.")
}

private fun requestWithTemperature(
    client: OkHttpClient,
    apiKey: String,
    userMessage: String,
    temperature: Float,
    system: String? = null
): String? {
    val messages = if (system != null) {
        listOf("system" to system, "user" to userMessage)
    } else {
        listOf("user" to userMessage)
    }
    val body = buildBodyWithTemperature(messages, temperature)
    println("Request body (JSON), temperature=$temperature:")
    println(body)
    println("-".repeat(40))
    val request = Request.Builder()
        .url("https://api.deepseek.com/chat/completions")
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                System.err.println("HTTP ${response.code}: $json")
                return null
            }
            extractContent(json)
        }
    } catch (e: Exception) {
        System.err.println("Ошибка: ${e.message}")
        e.printStackTrace()
        null
    }
}

private fun buildBodyWithTemperature(messages: List<Pair<String, String>>, temperature: Float): String {
    val messagesJson = messages.joinToString(",") { (role, content) ->
        """{"role":"$role","content":${escapeJson(content)}}"""
    }
    return """
        {
            "model": "deepseek-chat",
            "messages": [$messagesJson],
            "stream": false,
            "temperature": $temperature
        }
    """.trimIndent()
}

private fun escapeJson(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

private fun extractContent(json: String): String? {
    val contentKey = "\"content\":\""
    val start = json.indexOf(contentKey)
    if (start == -1) return null
    val contentStart = start + contentKey.length
    var end = contentStart
    while (end < json.length) {
        when (json[end]) {
            '\\' -> end += 2
            '"' -> break
            else -> end++
        }
    }
    return json.substring(contentStart, end)
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
