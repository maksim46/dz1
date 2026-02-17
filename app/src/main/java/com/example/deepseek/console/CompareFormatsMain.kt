package com.example.deepseek.console

import android.os.Build
import androidx.annotation.RequiresApi
import com.example.deepseek.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * сравниваем форматы ответов DeepSeek API —
 * без ограничений, с форматом JSON, с max_tokens, со stop-последовательностью.
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

    val baseMessages = listOf(
        "user" to "Объясни, зачем нужно пить воду."
    )

    val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    val separator = "=" .repeat(60)

    println("$separator")
    println("1. БЕЗ ОГРАНИЧЕНИЙ")
    println("Объясни, зачем нужно пить воду.")
    println("$separator")
    createCompletion(client, apiKey, baseMessages)
    println()

    println("$separator")
    println("2. С ЯВНЫМ ФОРМАТОМ ОТВЕТА (JSON)")
    println("   Системный промпт: «Ответ дай в формате JSON с ключами 'причина' и 'пояснение'.»")
    println("$separator")
    val resp2 = createCompletion(
        client, apiKey,
        listOf(
            "system" to "Ответ дай в формате JSON с ключами 'причина' и 'пояснение'.",
            baseMessages.single()
        )
    )
    println()

    println("$separator")
    println("3. С ОГРАНИЧЕНИЕМ ДЛИНЫ (max_tokens=10)")
    println("   Параметр: max_tokens = 10")
    println("$separator")
    createCompletion(client, apiKey, baseMessages, maxTokens = 10)
    println()


    println("$separator")
    println("4. СО СТОП-ПОСЛЕДОВАТЕЛЬНОСТЬЮ (stop)")
    println("   Параметр: stop = [\"\\\\n\\\\n\"]")
    println("   Ответ обрывается на первом вхождении любой из строк.")
    println("$separator")
    createCompletion(client, apiKey, baseMessages, stop = listOf("\n\n"))
    println()
    println("$separator")
}

/**
 * Вызов chat/completions с опциональными max_tokens и stop.
 */
private fun createCompletion(
    client: OkHttpClient,
    apiKey: String,
    messages: List<Pair<String, String>>,
    maxTokens: Int? = null,
    stop: List<String>? = null
): String? {
    val messagesJson = messages.joinToString(",") { (role, content) ->
        """{"role":"$role","content":${escapeJson(content)}}"""
    }
    val opt1 = maxTokens?.let { """"max_tokens":$it""" } ?: ""
    val opt2 = stop?.let { """"stop":[${it.joinToString(",") { s -> escapeJson(s) }}]""" } ?: ""
    val optional = listOf(opt1, opt2).filter { it.isNotEmpty() }.joinToString(", ")
    val optionalLine = if (optional.isNotEmpty()) ", $optional" else ""

    val body = """
        {
            "model": "deepseek-chat",
            "messages": [$messagesJson],
            "stream": true$optionalLine
        }
    """.trimIndent()

    val request = Request.Builder()
        .url("https://api.deepseek.com/chat/completions")
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                System.err.println("HTTP ${response.code}: $errBody")
                return null
            }
            val stream = response.body?.byteStream() ?: return null
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val s = line!!
                    if (!s.startsWith("data: ")) continue
                    val data = s.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    val chunk = extractDeltaContent(data)
                    if (chunk.isNotEmpty()) {
                        print(chunk)
                        System.out.flush()
                    }
                }
                println()
            }
            null
        }
    } catch (e: Exception) {
        System.err.println("Ошибка: ${e.message}")
        e.printStackTrace()
        null
    }
}

/** Извлекает content из delta в SSE-чанке. */
private fun extractDeltaContent(json: String): String {
    val contentKey = "\"content\":\""
    val start = json.indexOf(contentKey)
    if (start == -1) return ""
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

private fun escapeJson(s: String): String {
    return "\"" + s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\""
}

