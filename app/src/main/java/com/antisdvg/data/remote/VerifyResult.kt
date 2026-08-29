package com.antisdvg.data.remote

/**
 * Outcome of an AI reading verification.
 *
 * @param taskType one of TaskType constants: RETELING, QUIZ, SCORE.
 * @param passed whether the user convincingly read the pages.
 * @param feedback AI feedback message.
 * @param questions generated questions (filled when taskType == QUIZ).
 */
data class VerifyResult(
    val ok: Boolean,
    val taskType: String,
    val feedback: String,
    val questions: List<String> = emptyList()
) {
    companion object {
        const val TASK_RETELLING = "retelling"
        const val TASK_QUIZ = "quiz"
        const val TASK_SCORE = "score"
    }
}
