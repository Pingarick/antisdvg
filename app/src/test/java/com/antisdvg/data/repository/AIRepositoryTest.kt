package com.antisdvg.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIRepositoryTest {

    private val repo = AIRepository()

    @Test
    fun `cleanJson extracts json from code block`() {
        val raw = "Вот результат:\n```json\n{\"taskType\": \"score\", \"passed\": true}\n```"
        val cleaned = repo.cleanJson(raw)
        assertEquals("{\"taskType\": \"score\", \"passed\": true}", cleaned)
    }

    @Test
    fun `cleanJson extracts json from surrounded braces`() {
        val raw = "Ответ ИИ: {\"taskType\": \"score\", \"passed\": false} Конец."
        val cleaned = repo.cleanJson(raw)
        assertEquals("{\"taskType\": \"score\", \"passed\": false}", cleaned)
    }

    @Test
    fun `parse parses valid json correctly`() {
        val json = """{"taskType": "score", "passed": true, "feedback": "Отличный пересказ!"}"""
        val res = repo.parse(json)
        assertTrue(res.ok)
        assertEquals("score", res.taskType)
        assertEquals("Отличный пересказ!", res.feedback)
    }

    @Test
    fun `fallbackParse handles passed false with spacing`() {
        val raw = """{"taskType": "score", "passed": false, "feedback": "Пересказ не по теме."}"""
        val res = repo.fallbackParse(raw)
        assertFalse(res.ok)
        assertEquals("score", res.taskType)
        assertEquals("Пересказ не по теме.", res.feedback)
    }

    @Test
    fun `fallbackParse handles passed true with spacing`() {
        val raw = """{"taskType": "score", "passed": true, "feedback": "Хорошо"}"""
        val res = repo.fallbackParse(raw)
        assertTrue(res.ok)
        assertEquals("score", res.taskType)
    }

    @Test
    fun `systemPrompt echoes retelling taskType`() {
        val book = com.antisdvg.data.model.Book(title = "Тест", author = "Автор")
        val prompt = repo.buildSystemPrompt(book, 1, 10, "retelling", 1)
        assertTrue(prompt.contains("\"taskType\": \"retelling\""))
    }

    @Test
    fun `systemPrompt strictness changes wording`() {
        val book = com.antisdvg.data.model.Book(title = "Тест", author = "Автор")
        val lenient = repo.buildSystemPrompt(book, 1, 10, "score", 0)
        val strict = repo.buildSystemPrompt(book, 1, 10, "score", 2)
        assertTrue(lenient.contains("мягким"))
        assertTrue(strict.contains("строгим"))
    }

    @Test
    fun `userMessage includes quote when provided`() {
        val msg = repo.buildUserMessage("пересказ", emptyList(), "цитата из книги")
        assertTrue(msg.contains("пересказ"))
        assertTrue(msg.contains("цитата из книги"))
    }

    @Test
    fun `userMessage omits quote section when blank`() {
        val msg = repo.buildUserMessage("пересказ", emptyList(), "")
        assertTrue(msg.contains("пересказ"))
        assertFalse(msg.contains("Цитата"))
    }

    @Test
    fun `cleanJson keeps a bare array intact`() {
        // Regression: taking the first `{` unconditionally used to strip the
        // brackets off a quiz that came back without a code fence.
        val raw = """Вот вопросы: [{"question":"Кто?","options":["а","б","в","г"],"correctIndex":1}] Готово."""
        assertEquals(
            """[{"question":"Кто?","options":["а","б","в","г"],"correctIndex":1}]""",
            repo.cleanJson(raw)
        )
    }

    @Test
    fun `cleanJson still takes the object when a verdict comes first`() {
        assertEquals("""{"passed": true}""", repo.cleanJson("""Смотри: {"passed": true} и всё."""))
    }

    @Test
    fun `parseQuizJson keeps only questions that can be graded`() {
        val json = """[
            {"question":"Хороший?","options":["а","б","в","г"],"correctIndex":2},
            {"question":"Три варианта — тоже вопрос?","options":["а","б","в"],"correctIndex":1},
            {"question":"Индекс вне?","options":["а","б","в","г"],"correctIndex":7},
            {"question":"Один вариант?","options":["а"],"correctIndex":0},
            {"question":"","options":["а","б","в","г"],"correctIndex":0}
        ]"""

        val quiz = repo.parseQuizJson(json)

        assertEquals(2, quiz.size)
        assertEquals("Хороший?", quiz[0].question)
        // The order is mixed before a question is shown, so the answer is checked
        // by what it says rather than by the slot it sits in.
        assertEquals("в", quiz[0].options[quiz[0].correctIndex])
        assertEquals("Три варианта — тоже вопрос?", quiz[1].question)
        assertEquals("б", quiz[1].options[quiz[1].correctIndex])
    }

    @Test
    fun `passage quiz prompt carries the passage and the count`() {
        val prompt = repo.buildQuizFromTextPrompt("Мартин читал книги каждый вечер в библиотеке.", 3)

        assertTrue(prompt.contains("Мартин читал книги"))
        assertTrue(prompt.contains("ровно 3 вопрос"))
        assertTrue(prompt.contains("ОТРЫВОК:"))
    }

    @Test
    fun `the strictness reaches the prompt and the levels differ`() {
        val lenient = repo.buildQuizFromTextPrompt("Текст отрывка.", 2, 0)
        val normal = repo.buildQuizFromTextPrompt("Текст отрывка.", 2, 1)
        val strict = repo.buildQuizFromTextPrompt("Текст отрывка.", 2, 2)

        assertTrue(lenient.contains("мягко") && !lenient.contains("строго"))
        assertTrue(normal.contains("обычно"))
        assertTrue(strict.contains("строго") && !strict.contains("мягко"))
    }

    @Test
    fun `a long passage keeps its beginning and its end`() {
        val text = "НАЧАЛО " + "слово ".repeat(3_000) + "КОНЕЦ"

        val prompt = repo.buildQuizFromTextPrompt(text, 3)

        assertTrue(prompt.contains("НАЧАЛО"))
        assertTrue(prompt.contains("КОНЕЦ"))
        // The prompt stays within the model budget instead of the book's length.
        assertTrue(prompt.length < 13_000)
    }

    @Test
    fun `a question whose quote is not in the passage is dropped`() {
        val passage = "Майкл остановился у причала и закурил сигарету."
        val json = """[
            {"question":"Что сделал Майкл у причала?","options":["закурил","уснул","закричал","уехал"],"correctIndex":0,"quote":"Майкл остановился у причала и закурил сигарету"},
            {"question":"Куда Майкл поехал потом?","options":["в Рим","в Палермо","в США","в Милан"],"correctIndex":0,"quote":"затем он уехал в Рим на ночном поезде"}
        ]"""

        val quiz = repo.parseQuizJson(json, passage)

        assertEquals(1, quiz.size)
        assertEquals("Что сделал Майкл у причала?", quiz[0].question)
    }

    @Test
    fun `passage quiz prompt counts questions in Russian`() {
        assertTrue(repo.buildQuizFromTextPrompt("текст", 1).contains("ровно 1 вопрос "))
        assertTrue(repo.buildQuizFromTextPrompt("текст", 2).contains("ровно 2 вопроса"))
        assertTrue(repo.buildQuizFromTextPrompt("текст", 5).contains("ровно 5 вопросов"))
    }
}
