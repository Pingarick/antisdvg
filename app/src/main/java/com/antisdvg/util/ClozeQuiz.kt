package com.antisdvg.util

import com.antisdvg.data.model.QuizQuestion
import kotlin.random.Random

/**
 * Lightweight offline comprehension check for the built-in reader.
 *
 * Takes sentences from the text the user actually scrolled through, masks one
 * word per sentence and offers four real words from the same book as options.
 * Someone who read the passage answers instantly; someone who didn't, guesses.
 * No network, no API key — works as the default gate for reader sessions.
 */
object ClozeQuiz {

    private const val MASK = "______"
    private val SENTENCE = Regex("[^.!?\u2026\n]{40,220}[.!\u2026]?")
    private val WORD = Regex("[A-Za-zА-Яа-яЁё][A-Za-zА-Яа-яЁё-]{4,}")

    /**
     * Builds up to [count] cloze questions from [text].
     * Returns an empty list when the text is too small to quiz honestly.
     */
    fun generate(text: String, count: Int = 3, rnd: Random = Random): List<QuizQuestion> {
        val sentences = SENTENCE.findAll(text)
            .map { it.value.trim() }
            .filter { it.split(Regex("\\s+")).size >= 8 }
            .toList()
        if (sentences.isEmpty()) return emptyList()

        val pool = WORD.findAll(text)
            .map { clean(it.value) }
            .filter { it.length in 5..16 }
            .distinct()
            .toList()
        if (pool.size < 8) return emptyList()

        val take = minOf(count, sentences.size)
        val step = sentences.size.toDouble() / take
        return (0 until take).mapNotNull { i ->
            val index = (i * step).toInt().coerceIn(0, sentences.size - 1)
            buildQuestion(sentences[index], pool, rnd)
        }
    }

    private fun buildQuestion(sentence: String, pool: List<String>, rnd: Random): QuizQuestion? {
        val words = sentence.split(Regex("\\s+"))
        val candidates = words.indices.filter { j ->
            j > 0 && j < words.lastIndex && clean(words[j]).length in 5..16
        }
        if (candidates.isEmpty()) return null
        val targetIndex = candidates[rnd.nextInt(candidates.size)]
        val target = clean(words[targetIndex])

        val sentenceWords = words.map { clean(it) }.toSet()
        val distractors = pool
            .filter { it != target && !sentenceWords.contains(it) }
            .shuffled(rnd)
            .take(3)
        if (distractors.size < 3) return null

        val options = (listOf(target) + distractors).shuffled(rnd)
        val display = words.toMutableList().also { it[targetIndex] = MASK }.joinToString(" ")
        return QuizQuestion(
            question = "Вставь пропущенное слово:\n«$display»",
            options = options,
            correctIndex = options.indexOf(target)
        )
    }

    private fun clean(word: String): String = word.trim { !it.isLetter() }
}
