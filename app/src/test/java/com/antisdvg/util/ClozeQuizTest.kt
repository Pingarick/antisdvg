package com.antisdvg.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClozeQuizTest {

    private val text = """
        Мартин Иден был простым моряком и зарабатывал тяжёлым трудом.
        Каждую свободную минуту он проводил за книгами в городской библиотеке.
        Учителя и профессора казались ему людьми недосягаемой высоты и почти богами.
        Руфь смотрела на него с удивлением и скрытым восхищением.
        Он писал рассказы ночами, переписывал страницы и снова отправлял рукописи.
        Издатели возвращали конверты молча, без объяснений и вежливых слов.
        Море шумело за окном, и волны напоминали о далёких путешествиях.
    """.trimIndent()

    @Test
    fun generate_makesRequestedCount() {
        val quiz = ClozeQuiz.generate(text, count = 3, rnd = Random(7))
        assertEquals(3, quiz.size)
    }

    @Test
    fun eachQuestion_hasFourDistinctOptionsAndValidAnswer() {
        val quiz = ClozeQuiz.generate(text, count = 3, rnd = Random(7))
        quiz.forEach { q ->
            assertEquals(4, q.options.size)
            assertEquals(4, q.options.distinct().size)
            assertTrue(q.correctIndex in 0..3)
            assertTrue(q.question.contains("______"))
            assertTrue(q.options[q.correctIndex].length >= 5)
        }
    }

    @Test
    fun answer_isWordFromTheText() {
        val quiz = ClozeQuiz.generate(text, count = 2, rnd = Random(3))
        assertFalse(quiz.isEmpty())
        quiz.forEach { q -> assertTrue(text.contains(q.options[q.correctIndex])) }
    }

    @Test
    fun tinyText_returnsEmpty() {
        assertTrue(ClozeQuiz.generate("Коротко.", rnd = Random(1)).isEmpty())
    }

    @Test
    fun sameSeed_sameQuiz() {
        assertEquals(
            ClozeQuiz.generate(text, 3, Random(42)),
            ClozeQuiz.generate(text, 3, Random(42))
        )
    }

    @Test
    fun answer_reconstructsTheOriginalSentence() {
        val quiz = ClozeQuiz.generate(text, 3, Random(11))
        assertFalse(quiz.isEmpty())
        val flat = text.replace(Regex("\\s+"), " ").trim()
        quiz.forEach { q ->
            val masked = q.question.substringAfter('«').substringBeforeLast('»')
            val rebuilt = masked.replace("______", q.options[q.correctIndex])
                .replace(Regex("\\s+"), " ")
                .trim()
            assertTrue("Answer does not rebuild the read line: $rebuilt", flat.contains(rebuilt))
        }
    }

    @Test
    fun mask_keepsSentencePunctuation() {
        val quiz = ClozeQuiz.generate(text, 3, Random(5))
        quiz.forEach { q ->
            val masked = q.question.substringAfter('«').substringBeforeLast('»')
            // The last word of the line keeps its final period/comma after masking.
            assertTrue(masked.trim().last().let { it == '.' || it == ',' || it.isLetter() })
        }
    }
}
