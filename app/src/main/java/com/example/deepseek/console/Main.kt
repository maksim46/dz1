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
import kotlin.concurrent.thread

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun main(args: Array<String>) {

    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8))
    val input = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))

    val apiKey = BuildConfig.DEEPSEEK_API_KEY
    if (apiKey.isBlank()) {
        System.err.println("Ошибка: задайте deepseek.api.key в local.properties")
        System.exit(1)
    }

    if (args.isNotEmpty()) {
        // Режим одного запроса из аргументов (gradlew, скрипты)
        sendRequest(apiKey, args.joinToString(" "), null)
        return
    }

    // Интерактивный режим: ввод в консоли (запуск из IDE по зелёному треугольнику)
    println("DeepSeek — введите запрос (пустая строка или 'exit' для выхода):")
    println("---")
    while (true) {
        print("> ")
        val line = input.readLine()?.trim() ?: break
        if (line.isEmpty() || line.equals("exit", ignoreCase = true)) break
        sendRequest(apiKey, line, input)
        println("---")
    }
}

private fun sendRequest(apiKey: String, prompt: String, input: BufferedReader?) {
    println("Запрос: $prompt")
    if (input != null) println("Ответ (Enter — остановить вывод):")
    println()

    val url = "https://api.deepseek.com/chat/completions"
    val body = """
        {
            "model": "deepseek-chat",
            "messages": [
                {"role": "user", "content": ${escapeJson(prompt)}}
            ],
            "stream": true
        }
    """.trimIndent()

    val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder()
        .url(url)
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    val call = client.newCall(request)
    if (input != null) {
        // Интерактивный режим: поток выводит ответ, по Enter отменяем запрос
        val streamThread = thread(name = "stream") {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string() ?: ""
                        System.err.println("HTTP ${response.code}: $errBody")
                        return@thread
                    }
                    val stream = response.body?.byteStream() ?: return@thread
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
                }
            } catch (e: Exception) {
                if (!call.isCanceled()) {
                    System.err.println("Ошибка: ${e.message}")
                }
            }
        }
        input.readLine()
        call.cancel()
        streamThread.join(3000)
    } else {
        // Режим без консоли (аргументы): просто стриминг без остановки по Enter
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    System.err.println("HTTP ${response.code}: $errBody")
                    return
                }
                val stream = response.body?.byteStream() ?: return
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
            }
        } catch (e: Exception) {
            System.err.println("Ошибка: ${e.message}")
            e.printStackTrace()
        }
    }
}

/** Извлекает content из delta в SSE-чанке: {"choices":[{"delta":{"content":"..."}}]} */
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
