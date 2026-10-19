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

    companion object {
        const val STRICTNESS_LENIENT = 0
        const val STRICTNESS_NORMAL = 1
        const val STRICTNESS_STRICT = 2

        /** The quiz UI can show any number of answers; fewer than two is not a quiz. */
        private const val MIN_QUIZ_OPTIONS = 2

        /** Longest passage sent for quizzing: ~14 screens of text. */
        private const val MAX_PASSAGE_CHARS = 12_000
    }

    /**
     * Asks DeepSeek to verify a reading session.
     *
     * @param book the book being read.
     * @param startPage first page of this session (1-based).
     * @param endPage last page of this session (1-based).
     * @param userText the user's retelling or answers (voice transcribed).
     * @param questions pre-generated questions if the caller held a quiz.
     * @param quote optional verbatim quote from the read pages as extra proof.
     * @param taskType [VerifyResult.TASK_RETELLING] for a full check,
     * [VerifyResult.TASK_SCORE] for a quick meaning check.
     * @param strictness 0 = мягко, 1 = нормально, 2 = строго.
     * @return a [VerifyResult] with the AI's verdict.
     */
    suspend fun verifyReading(
        book: Book,
        startPage: Int,
        endPage: Int,
        userText: String,
        questions: List<String> = emptyList(),
        quote: String = "",
        taskType: String = VerifyResult.TASK_SCORE,
        strictness: Int = STRICTNESS_NORMAL,
        apiKey: String = BuildConfig.DEEPSEEK_API_KEY
    ): VerifyResult {
        val prompt = buildSystemPrompt(book, startPage, endPage, taskType, strictness)
        val userMessage = buildUserMessage(userText, questions, quote)

        val response = try {
            DeepSeekClient.api.chat(
                authorization = "Bearer ${apiKey.ifBlank { BuildConfig.DEEPSEEK_API_KEY }}",
                request = ChatRequest(
                    messages = listOf(
                        ChatMessage("system", prompt),
                        ChatMessage("user", userMessage)
                    )
                )
            )
        } catch (e: Exception) {
            return VerifyResult(
                ok = false,
                taskType = VerifyResult.TASK_SCORE,
                feedback = "Ошибка сети: ${e.message ?: "не удалось связаться с сервером"}. Проверьте подключение."
            )
        }

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

    /**
     * Generates a 3-question multiple choice quiz for testing comprehension of read pages.
     */
    suspend fun generateQuiz(
        book: Book,
        startPage: Int,
        endPage: Int,
        apiKey: String = BuildConfig.DEEPSEEK_API_KEY
    ): List<com.antisdvg.data.model.QuizQuestion> {
        val prompt = buildString {
            appendLine("Ты — преподаватель. Пользователь прочитал страницы $startPage-$endPage книги «${book.title}» (${book.author}).")
            appendLine("Составь ровно 3 вопроса с 4 вариантами ответа для проверки понимания сути этих страниц.")
            appendLine("Вопросы должны проверять ключевые события, идеи и логику, без придирок к мелким деталям.")
            appendLine("Ответь ТОЛЬКО валидным JSON-массивом (без markdown разметки) в формате:")
            appendLine("[")
            appendLine("  {")
            appendLine("    \"question\": \"Текст вопроса?\",")
            appendLine("    \"options\": [\"Вариант 1\", \"Вариант 2\", \"Вариант 3\", \"Вариант 4\"],")
            appendLine("    \"correctIndex\": 0")
            appendLine("  }")
            appendLine("]")
        }

        val response = try {
            DeepSeekClient.api.chat(
                authorization = "Bearer ${apiKey.ifBlank { BuildConfig.DEEPSEEK_API_KEY }}",
                request = ChatRequest(
                    messages = listOf(
                        ChatMessage("system", prompt),
                        ChatMessage("user", "Сгенерируй квиз по страницам $startPage-$endPage книги «${book.title}».")
                    )
                )
            )
        } catch (e: Exception) {
            return emptyList()
        }

        if (!response.isSuccessful) return emptyList()
        val raw = response.body()?.choices?.firstOrNull()?.message?.content ?: return emptyList()
        return parseQuizJson(cleanJson(raw))
    }

    /**
     * Generates comprehension questions from the passage the reader actually saw.
     *
     * Deliberately different from [generateQuiz]: there is no book and no page
     * range in the prompt, only the text, so the model cannot ask about pages
     * that were never opened and cannot invent what the book says. [count] comes
     * from how much was read.
     */
    suspend fun generateQuizFromText(
        text: String,
        count: Int,
        strictness: Int = STRICTNESS_NORMAL,
        apiKey: String = BuildConfig.DEEPSEEK_API_KEY
    ): List<com.antisdvg.data.model.QuizQuestion> {
        val prompt = buildQuizFromTextPrompt(text, count, strictness)

        val response = try {
            DeepSeekClient.api.chat(
                authorization = "Bearer ${apiKey.ifBlank { BuildConfig.DEEPSEEK_API_KEY }}",
                request = ChatRequest(
                    messages = listOf(
                        ChatMessage("system", prompt),
                        ChatMessage("user", "Составь $count ${questionsWord(count)} по этому отрывку.")
                    )
                )
            )
        } catch (e: Exception) {
            return emptyList()
        }

        if (!response.isSuccessful) return emptyList()
        val raw = response.body()?.choices?.firstOrNull()?.message?.content ?: return emptyList()
        return parseQuizJson(cleanJson(raw), text).take(count)
    }

    /**
     * The whole prompt for a passage quiz, kept separate so it can be asserted on.
     *
     * @param strictness 0 = мягко, 1 = обычно, 2 = строго — the same setting the
     *   reader sees, so the test the model writes matches what was asked for.
     */
    internal fun buildQuizFromTextPrompt(
        text: String,
        count: Int,
        strictness: Int = STRICTNESS_NORMAL
    ): String = buildString {
        appendLine("Ты — преподаватель. Ниже отрывок, который человек только что прочитал.")
        appendLine("Составь ровно $count ${questionsWord(count)} с 4 вариантами ответа на понимание отрывка.")
        appendLine("Требования:")
        appendLine("- правильный ответ должен однозначно следовать из отрывка;")
        appendLine("- неверные варианты правдоподобны, но тексту противоречат;")
        appendLine("- не спрашивай про то, чего в отрывке нет;")
        appendLine("- чередуй типы: факт, причина, поступок или деталь, которая что-то говорит о человеке.")
        appendLine("- для каждого вопроса положи в поле quote дословную фразу из отрывка, на которой он держится.")
        when (strictness.coerceIn(STRICTNESS_LENIENT, STRICTNESS_STRICT)) {
            STRICTNESS_LENIENT ->
                appendLine("- уровень: мягко. Спрашивай о главном: чтобы отвечал тот, кто прочитал, а не тот, кто угадывает.")
            STRICTNESS_STRICT ->
                appendLine("- уровень: строго. Спрашивай о деталях и связях, видных только при внимательном чтении; вопрос не должен решаться по общим словам.")
            else ->
                appendLine("- уровень: обычно. Спрашивай про суть: события, поступки, мысли, связи между ними.")
        }
        appendLine("Пиши по-русски, коротко. Ответь ТОЛЬКО валидным JSON-массивом (без markdown):")
        appendLine("""[{"question":"Текст вопроса?","options":["1","2","3","4"],"correctIndex":0,"quote":"фраза из отрывка"}]""")
        appendLine()
        appendLine("ОТРЫВОК:")
        append(passageForPrompt(text))
    }

    /**
     * A long session can be many pages and the model's window is not free. Take the
     * beginning and the end rather than only the beginning, so the questions can be
     * about where the reading finished too.
     */
    private fun passageForPrompt(text: String): String {
        if (text.length <= MAX_PASSAGE_CHARS) return text
        val head = MAX_PASSAGE_CHARS * 2 / 3
        val tail = MAX_PASSAGE_CHARS - head
        return text.take(head) + "\n…\n" + text.takeLast(tail)
    }

    private fun questionsWord(count: Int): String = when {
        count % 10 == 1 && count % 100 != 11 -> "вопрос"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "вопроса"
        else -> "вопросов"
    }

    internal fun parseQuizJson(
        json: String,
        passage: String? = null
    ): List<com.antisdvg.data.model.QuizQuestion> {
        val list = mutableListOf<com.antisdvg.data.model.QuizQuestion>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val qText = obj.optString("question", "")
                val optionsArray = obj.optJSONArray("options")
                val options = mutableListOf<String>()
                if (optionsArray != null) {
                    for (j in 0 until optionsArray.length()) {
                        options.add(optionsArray.getString(j))
                    }
                }
                val correctIndex = obj.optInt("correctIndex", 0)
                // A question the reader can grade: at least two answers, and a
                // marked one among them. Anything else would be a question with
                // no right answer, which is worse than one question fewer.
                val quoted = obj.optString("quote", "")
                if (qText.isNotBlank() && options.size >= MIN_QUIZ_OPTIONS &&
                    correctIndex in options.indices &&
                    (passage == null || quoteBacked(quoted, passage))
                ) {
                    val (mixed, index) = mixOptions(options, correctIndex)
                    list.add(com.antisdvg.data.model.QuizQuestion(qText, mixed, index))
                }
            }
        } catch (e: Exception) {
            // best-effort
        }
        return list
    }

    /**
     * A question is only kept if it can point at the passage.
     *
     * The model is asked to quote the line each question rests on. A question whose
     * quote is not in the text — invented, or about another part of the book — is
     * dropped rather than asked, because a question about something the reader
     * never saw tests nothing. Matching is by words: the model re-punctuates.
     */
    internal fun quoteBacked(quote: String, passage: String): Boolean {
        val words = quote.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 }
        if (words.isEmpty()) return false
        val haystack = passage.lowercase()
        val hits = words.count { haystack.contains(it) }
        return hits * 100 >= words.size * 70
    }

    internal fun buildSystemPrompt(
        book: Book,
        startPage: Int,
        endPage: Int,
        taskType: String = VerifyResult.TASK_SCORE,
        strictness: Int = STRICTNESS_NORMAL
    ): String {
        val level = strictness.coerceIn(STRICTNESS_LENIENT, STRICTNESS_STRICT)
        val strictBlock = when (level) {
            STRICTNESS_LENIENT -> buildString {
                appendLine("Будь мягким: засчитывай, если человек уловил хотя бы общую тему страниц.")
                appendLine("Не требуй деталей, имён и последовательности событий.")
                appendLine("passed=false ставь только если ответ явно не про эту книгу.")
            }
            STRICTNESS_STRICT -> buildString {
                appendLine("Будь строгим: требуй конкретные детали страниц — кто, что сделал, чем закончилось.")
                appendLine("Расплывчатый пересказ «ни о чём» — это passed=false.")
                appendLine("Если есть цитата пользователя — сверь её смысл с пересказом: противоречие = passed=false.")
            }
            else -> buildString {
                appendLine("Проверь, понял ли он суть прочитанного: ключевые события, героев, логику истории.")
                appendLine("НЕ выпытывай имена второстепенных персонажей, точные даты, числа и мелкие детали.")
                appendLine("Если человек передаёт главное своими словами — считай это пройденным, даже если пересказ неполный.")
            }
        }
        val taskBlock = if (taskType == VerifyResult.TASK_RETELLING) {
            "Разбери пересказ по трём пунктам: герои, события, вывод. " +
                "Затем вынеси вердикт. В feedback сначала 1-2 предложения по существу, " +
                "потом коротко отметь, чего не хватило."
        } else {
            "Оцени понимание сути коротко: 1-2 предложения обратной связи на русском."
        }
        val context = buildString {
            appendLine("Ты — доброжелательный преподаватель по чтению, но не педант.")
            appendLine("Пользователь прочитал страницы $startPage-$endPage книги «${book.title}» (${book.author}).")
            appendLine()
            append(strictBlock)
            appendLine(taskBlock)
            appendLine()
            appendLine("Ответь ТОЛЬКО валидным JSON (без markdown) в формате:")
            appendLine("{\"taskType\": \"$taskType\", \"passed\": true|false, \"feedback\": \"1-2 предложения\", \"questions\": []}")
            appendLine()
            appendLine("passed=true, если понята суть. passed=false, только если ответ явно мимо темы — похоже, что человек и не читал.")
        }
        return context
    }

    internal fun buildUserMessage(userText: String, questions: List<String>, quote: String = ""): String {
        val base = if (questions.isNotEmpty()) {
            val q = questions.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
            "Вопросы:\n$q\n\nОтветы пользователя:\n$userText"
        } else {
            "Пересказ пользователя:\n$userText"
        }
        return if (quote.isNotBlank()) "$base\n\nЦитата пользователя из книги:\n$quote" else base
    }

    internal fun cleanJson(raw: String): String {
        val trimmed = raw.trim()
        val codeBlockMatch = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```").find(trimmed)
        if (codeBlockMatch != null) {
            return codeBlockMatch.groupValues[1].trim()
        }
        // A verdict comes back as an object, a quiz as an array: keep whichever
        // the model actually opened with. Taking the first `{` unconditionally
        // used to strip the brackets off a bare array and lose the whole quiz.
        val arrayStart = trimmed.indexOf('[')
        val objectStart = trimmed.indexOf('{')
        return if (arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart)) {
            val end = trimmed.lastIndexOf(']')
            if (end > arrayStart) trimmed.substring(arrayStart, end + 1).trim() else trimmed
        } else {
            val end = trimmed.lastIndexOf('}')
            if (objectStart in 0 until end) trimmed.substring(objectStart, end + 1).trim() else trimmed
        }
    }

    /**
     * Defensive JSON parser: falls back to a manual scan of the four fields in
     * case the model wraps values differently or includes stray prose.
     */
    internal fun parse(raw: String): VerifyResult {
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

    internal fun normalizeTaskType(raw: String): String = when {
        raw.contains("quiz") -> VerifyResult.TASK_QUIZ
        raw.contains("retell") -> VerifyResult.TASK_RETELLING
        else -> VerifyResult.TASK_SCORE
    }

    internal fun fallbackParse(raw: String): VerifyResult {
        val lower = raw.lowercase()
        val hasPassedFalse = Regex("\"passed\"\\s*:\\s*false").containsMatchIn(lower)
        val hasPassedTrue = Regex("\"passed\"\\s*:\\s*true").containsMatchIn(lower)
        val passed = when {
            hasPassedFalse -> false
            hasPassedTrue -> true
            lower.contains("нет, не прочитал") || lower.contains("не подтверждено") -> false
            else -> false
        }
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

/**
 * Puts the answers in a random order.
 *
 * The model puts the right answer first almost every time — on a real session,
 * all three questions came back with the correct option in slot one. A test whose
 * answer is always the first option is not a test, it is a formality.
 */
internal fun mixOptions(
    options: List<String>,
    correctIndex: Int,
    rnd: kotlin.random.Random = kotlin.random.Random
): Pair<List<String>, Int> {
    if (correctIndex !in options.indices) return options to correctIndex
    val right = options[correctIndex]
    val mixed = options.shuffled(rnd)
    return mixed to mixed.indexOf(right)
}
