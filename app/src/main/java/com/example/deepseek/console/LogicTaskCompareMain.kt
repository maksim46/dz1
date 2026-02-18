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
 * Логическая задача: решаем через API четырьмя способами —
 * прямой ответ, пошагово, через сгенерированный промпт, multi-agent (отдельные запросы с ролями).
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

    val task = """
      У двух шоферов есть брат Андрей, а у Андрея братьев нет. Как же это так?
    """.trimIndent()

    val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val sep = "=" .repeat(60)

    println(sep)
    println("ЗАДАЧА")
    println(sep)
    println(task)
    println()

    println(sep)
    println("1. ПРЯМОЙ ОТВЕТ (без дополнительных инструкций)")
    println(sep)
    streamCompletion(client, apiKey, listOf("user" to task))
    println()

    println(sep)
    println("2. С ИНСТРУКЦИЕЙ «РЕШАЙ ПОШАГОВО»")
    println(sep)
    streamCompletion(client, apiKey, listOf("user" to "Решай пошагово.\n\n$task"))
    println()

    println(sep)
    println("3. СНАЧАЛА ПРОМПТ ОТ МОДЕЛИ, ПОТОМ РЕШЕНИЕ")
    println(sep)
    val promptRequest = "Составь один краткий промпт (инструкцию) для решения следующей логической задачи. " +
        "Промпт должен подсказывать, как подходить к задаче. Выдай только текст промпта, без решения.\n\nЗадача:\n$task"
    val generatedPrompt = requestFullResponse(client, apiKey, listOf("user" to promptRequest))
    if (!generatedPrompt.isNullOrBlank()) {
        println("Сгенерированный промпт: $generatedPrompt")
        println()
        println("Результат по этому промпту:")
        streamCompletion(client, apiKey, listOf(
            "user" to generatedPrompt.trim(),
            "user" to task
        ))
    } else {
        println("Не удалось получить промпт от модели.")
    }
    println()

    println(sep)
    println("4. MULTI-AGENT (4 запроса: аналитик → инженер → критик → координатор)")
    println(sep)

    // Шаг 1: эксперт-аналитик
    println("Шаг 1 — Роль: эксперт-аналитик")
    println("-".repeat(40))
    val analystSystem = "Ты эксперт-аналитик.."
    val response1 = requestFullResponse(client, apiKey, listOf(
        "system" to analystSystem,
        "user" to task
    ))
    println(response1 ?: "(нет ответа)")
    println()

    // Шаг 2: эксперт-инженер (видит анализ)
    println("Шаг 2 — Роль: эксперт-инженер")
    println("-".repeat(40))
    val engineerSystem = "Ты эксперт-инженер."
    val response2 = requestFullResponse(client, apiKey, listOf(
        "system" to engineerSystem,
        "user" to task,
       // "assistant" to (response1 ?: ""),
     //   "user" to "Используй анализ выше и построй решение задачи."
    ))
    println(response2 ?: "(нет ответа)")
    println()

    // Шаг 3: эксперт-критик (видит анализ и решение)
    println("Шаг 3 — Роль: эксперт-критик")
    println("-".repeat(40))
    val criticSystem = "Ты эксперт-критик."
    val response3 = requestFullResponse(client, apiKey, listOf(
        "system" to criticSystem,
        "user" to task,
     //   "assistant" to (response1 ?: ""),
     //   "user" to "Решение инженера:",
    //    "assistant" to (response2 ?: ""),
    //    "user" to "Сравни анализ и решение, проверь выводы и дай итоговый ответ."
    ))
    println(response3 ?: "(нет ответа)")
    println()

    // Шаг 4: координатор (видит всех троих, даёт итоговое резюме)
    println("Шаг 4 — Роль: координатор")
    println("-".repeat(40))
    val coordinatorSystem = "Ты координатор. На основе анализа, решения и критики сформулируй краткое итоговое резюме: кто какой профессией занимается (или итоговый ответ на задачу). Без повторения чужих текстов."
    val response4 = requestFullResponse(client, apiKey, listOf(
        "system" to coordinatorSystem,
        "user" to task,
        "assistant" to (response1 ?: ""),
        "user" to "Решение аналитика:",
        "assistant" to (response2 ?: ""),
        "user" to "рещение инженера",
        "assistant" to (response3 ?: ""),
        "user" to "Критика. Дай итоговое резюме по задаче."
    ))
    println(response4 ?: "(нет ответа)")
    println()
    println(sep)
    println("Готово.")
}

private fun streamCompletion(
    client: OkHttpClient,
    apiKey: String,
    messages: List<Pair<String, String>>
) {
    val body = buildBody(messages, stream = true)
    println("Request body (JSON):")
    println(body)
    println("-".repeat(40))
    val request = Request.Builder()
        .url("https://api.deepseek.com/chat/completions")
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                System.err.println("HTTP ${response.code}: ${response.body?.string() ?: ""}")
                return
            }
            response.body?.byteStream()?.let { stream ->
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
        }
    } catch (e: Exception) {
        System.err.println("Ошибка: ${e.message}")
        e.printStackTrace()
    }
}

private fun requestFullResponse(
    client: OkHttpClient,
    apiKey: String,
    messages: List<Pair<String, String>>
): String? {
    val body = buildBody(messages, stream = false)
    println("Request body (JSON):")
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
        null
    }
}

private fun buildBody(messages: List<Pair<String, String>>, stream: Boolean): String {
    val messagesJson = messages.joinToString(",") { (role, content) ->
        """{"role":"$role","content":${escapeJson(content)}}"""
    }
    val streamPart = """"stream":$stream"""
    return """
        {
            "model": "deepseek-chat",
            "messages": [$messagesJson],
            $streamPart
        }
    """.trimIndent()
}

private fun escapeJson(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

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
