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
}
