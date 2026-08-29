package com.antisdvg.data.repository

import com.antisdvg.BuildConfig
import com.antisdvg.data.model.Book
import com.antisdvg.data.remote.ChatMessage
import com.antisdvg.data.remote.ChatRequest
import com.antisdvg.data.remote.DeepSeekClient
import com.antisdvg.data.remote.VerifyResult
import org.json.JSONObject

/**
 * Talks to the DeepSeek API to verify that the user actually read a given
 * page range of a book.
 *
 * The AI picks one of three task types (retelling analysis, 3-question quiz,
 * or score) and returns structured JSON that we parse into a [VerifyResult].
 */
class AIRepository {

    /**
     * Asks DeepSeek to verify a reading session.
     *
     * @param book the book being read.
     * @param startPage first page of this session (1-based).
     * @param endPage last page of this session (1-based).
     * @param userText the user's retelling or answers (voice transcribed).
     * @param questions pre-generated questions if the caller held a quiz.
     * @return a [VerifyResult] with the AI's verdict.
     */
    suspend fun verifyReading(
        book: Book,
        startPage: Int,
        endPage: Int,
        userText: String,
        questions: List<String> = emptyList(),
        apiKey: String = BuildConfig.DEEPSEEK_API_KEY
    ): VerifyResult {
        val prompt = buildSystemPrompt(book, startPage, endPage)
        val userMessage = buildUserMessage(userText, questions)

        val response = DeepSeekClient.api.chat(
            authorization = "Bearer ${apiKey.ifBlank { BuildConfig.DEEPSEEK_API_KEY }}",
            request = ChatRequest(
                messages = listOf(
                    ChatMessage("system", prompt),
                    ChatMessage("user", userMessage)
                )
            )
        )

        if (!response.isSuccessful) {
            return VerifyResult(
                ok = false,
                taskType = VerifyResult.TASK_SCORE,
                feedback = "Не удалось связаться с ИИ (${response.code()}). Проверьте ключ DeepSeek и интернет."
            )
        }

        val body = response.body()
        val raw = body?.choices?.firstOrNull()?.message?.content
        if (raw.isNullOrBlank()) {
            return VerifyResult(
                ok = false,
                taskType = VerifyResult.TASK_SCORE,
                feedback = "ИИ вернул пустой ответ. Попробуйте ещё раз."
            )
        }

        return parse(cleanJson(raw))
    }

    private fun buildSystemPrompt(book: Book, startPage: Int, endPage: Int): String {
        val context = buildString {
            appendLine("Ты — доброжелательный преподаватель по чтению, но не педант.")
            appendLine("Пользователь прочитал страницы $startPage-$endPage книги «${book.title}» (${book.author}).")
            appendLine()
            appendLine("Проверь, понял ли он суть прочитанного: ключевые события, героев, логику истории.")
            appendLine("НЕ выпытывай имена второстепенных персонажей, точные даты, числа и мелкие детали.")
            appendLine("Если человек передаёт главное своими словами — считай это пройденным, даже если пересказ неполный.")
            appendLine("Отвечай кратко: 1-2 предложения обратной связи на русском.")
            appendLine()
            appendLine("Ответь ТОЛЬКО валидным JSON (без markdown) в формате:")
            appendLine("{\"taskType\": \"score\", \"passed\": true|false, \"feedback\": \"1-2 предложения\", \"questions\": []}")
            appendLine()
            appendLine("passed=true, если понята суть. passed=false, только если ответ явно мимо темы — похоже, что человек и не читал.")
        }
        return context
    }

    private fun buildUserMessage(userText: String, questions: List<String>): String {
        if (questions.isNotEmpty()) {
            val q = questions.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
            return "Вопросы:\n$q\n\nОтветы пользователя:\n$userText"
        }
        return "Пересказ пользователя:\n$userText"
    }

    private fun cleanJson(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("```") -> {
                val start = trimmed.indexOf('\n')
                val end = trimmed.lastIndexOf("```")
                if (start >= 0 && end > start) trimmed.substring(start + 1, end).trim()
                else trimmed
            }
            else -> trimmed
        }
    }

    /**
     * Defensive JSON parser: falls back to a manual scan of the four fields in
     * case the model wraps values differently or includes stray prose.
     */
    private fun parse(raw: String): VerifyResult {
        return try {
            val obj = JSONObject(raw)
            val taskType = obj.optString("taskType", VerifyResult.TASK_SCORE).lowercase()
            val passed = obj.optBoolean("passed", false)
            val feedback = obj.optString("feedback", "")
            val questions = obj.optJSONArray("questions")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList()
            VerifyResult(
                ok = passed,
                taskType = normalizeTaskType(taskType),
                feedback = feedback.ifBlank { if (passed) "Чтение подтверждено." else "Чтение не подтверждено." },
                questions = questions
            )
        } catch (e: Exception) {
            fallbackParse(raw)
        }
    }

    private fun normalizeTaskType(raw: String): String = when {
        raw.contains("quiz") -> VerifyResult.TASK_QUIZ
        raw.contains("retell") -> VerifyResult.TASK_RETELLING
        else -> VerifyResult.TASK_SCORE
    }

    private fun fallbackParse(raw: String): VerifyResult {
        val lower = raw.lowercase()
        val passed = !lower.contains("\"passed\":false") && !lower.contains("нет, не прочитал")
        val taskType = normalizeTaskType(lower)
        val feedback = extractQuoted(raw, "feedback")
            ?: if (passed) "Чтение подтверждено." else "Чтение не подтверждено."
        val questions = extractList(raw, "questions")
        return VerifyResult(passed, taskType, feedback, questions)
    }

    private fun extractQuoted(raw: String, key: String): String? {
        val marker = "\"$key\""
        val idx = raw.indexOf(marker)
        if (idx < 0) return null
        val start = raw.indexOf('"', idx + marker.length)
        if (start < 0) return null
        val end = raw.indexOf('"', start + 1)
        if (end < 0) return null
        return raw.substring(start + 1, end)
    }

    private fun extractList(raw: String, key: String): List<String> {
        val marker = "\"$key\""
        val idx = raw.indexOf(marker)
        if (idx < 0) return emptyList()
        val start = raw.indexOf('[', idx)
        val end = raw.indexOf(']', start)
        if (start < 0 || end < 0) return emptyList()
        val arrRaw = raw.substring(start + 1, end)
        val regex = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        return regex.findAll(arrRaw).map { it.groupValues[1] }.toList()
    }
}
