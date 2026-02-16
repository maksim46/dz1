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
        sendRequest(apiKey, args.joinToString(" "))
        return
    }

    // Интерактивный режим: ввод в консоли (запуск из IDE по зелёному треугольнику)
    println("DeepSeek — введите запрос (пустая строка или 'exit' для выхода):")
    println("---")
    while (true) {
        print("> ")
        val line = input.readLine()?.trim() ?: break
        if (line.isEmpty() || line.equals("exit", ignoreCase = true)) break
        sendRequest(apiKey, line)
        println("---")
    }
}

private fun sendRequest(apiKey: String, prompt: String) {
    println("Запрос: $prompt")

    val url = "https://api.deepseek.com/chat/completions"
    val body = """
        {
            "model": "deepseek-chat",
            "messages": [
                {"role": "user", "content": ${escapeJson(prompt)}}
            ],
            "stream": false
        }
    """.trimIndent()

    val client = OkHttpClient()
    val request = Request.Builder()
        .url(url)
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    try {
     //   println("Запрос к API: $request")
        client.newCall(request).execute().use { response ->
            val json = response.body?.string() ?: ""
        //    println("Ответ API: $json")
            if (!response.isSuccessful) {
                System.err.println("HTTP ${response.code}: $json")
                return
            }
            val content = extractContent(json)
            if (content != null) {
                println(content)
            } else {
                System.err.println("Не удалось извлечь ответ. Ответ API: $json")
            }
        }
    } catch (e: Exception) {
        System.err.println("Ошибка: ${e.message}")
        e.printStackTrace()
    }
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
