package com.antisdvg.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Thin wrapper around the system [SpeechRecognizer] so the reading screen can
 * offer a voice-to-text button instead of forcing the user to type a retelling.
 *
 * Usage:
 * ```
 * val voice = VoiceInputHelper(context, "ru-RU")
 * voice.onState / voice.onResult flows delegate to UI state + text field.
 * voice.start(); voice.stop(); voice.destroy();
 * ```
 */
class VoiceInputHelper(
    private val context: Context,
    private val languageTag: String = "ru-RU"
) {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    sealed interface State {
        data object Idle : State
        data object Ready : State
        data object Listening : State
        data class Error(val message: String) : State
    }

    private val stateChannel = Channel<State>(Channel.BUFFERED)
    private val resultChannel = Channel<String>(Channel.BUFFERED)

    /** Emits recognizer lifecycle changes (Idle/Ready/Listening/Error). */
    val onState: Flow<State> = stateChannel.receiveAsFlow()

    /** Emits the final transcribed text once decoding finishes. */
    val onResult: Flow<String> = resultChannel.receiveAsFlow()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listening = true
            stateChannel.trySend(State.Ready)
        }

        override fun onBeginningOfSpeech() {
            listening = true
            stateChannel.trySend(State.Listening)
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onError(error: Int) {
            listening = false
            stateChannel.trySend(State.Error(messageFor(error)))
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            val text = matches.firstOrNull().orEmpty()
            if (text.isNotBlank()) resultChannel.trySend(text)
            stateChannel.trySend(State.Idle)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Starts a listening session. Safe to call repeatedly. */
    fun start() {
        if (listening) return
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        if (!speechAvailable()) {
            stateChannel.trySend(State.Error("Голосовой ввод недоступен на этом устройстве."))
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        try {
            sr.startListening(intent)
        } catch (e: Exception) {
            listening = false
            stateChannel.trySend(State.Error("Не удалось запустить голосовой ввод: ${e.message}"))
        }
    }

    /** Stops listening and finalizes the current recognition result. */
    fun stop() {
        recognizer?.stopListening()
        listening = false
    }

    /** Must be called when the owning screen is destroyed to free resources. */
    fun destroy() {
        listening = false
        recognizer?.destroy()
        recognizer = null
        stateChannel.close()
        resultChannel.close()
    }

    private fun speechAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Не распознано. Попробуй ещё раз."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Тишина. Говори громче."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Нет разрешения на запись (нужно в настройках)."
        else -> "Ошибка распознавания (код $error)."
    }
}
