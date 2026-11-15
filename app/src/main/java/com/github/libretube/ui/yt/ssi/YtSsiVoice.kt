package com.github.libretube.ui.yt.ssi

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/** What the voice input is doing. */
sealed interface YtSsiVoiceState {
    data object Idle : YtSsiVoiceState
    data class Listening(val partial: String, val onDevice: Boolean) : YtSsiVoiceState
    data class Failed(val code: Int) : YtSsiVoiceState
}

/**
 * YT: SSI's voice input through Android's SpeechRecognizer: the on-device recognizer on
 * Android 12+ where the phone has one (nothing leaves the phone), else the system's recognizer
 * service. The caller asks for RECORD_AUDIO first, only when the microphone is tapped. Main
 * thread only; [stop] or [destroy] ends it.
 */
class YtSsiVoice(private val context: Context, private val onState: (YtSsiVoiceState) -> Unit, private val onText: (String) -> Unit) {
    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable()

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    fun start() {
        destroy()
        val onDevice = onDeviceAvailable()
        val created = runCatching {
            if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        if (created == null) {
            onState(YtSsiVoiceState.Failed(SpeechRecognizer.ERROR_CLIENT))
            return
        }
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onState(YtSsiVoiceState.Listening("", onDevice))
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                Log.i(TAG, "recognizer error $error")
                onState(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) YtSsiVoiceState.Idle else YtSsiVoiceState.Failed(error))
                destroy()
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                onState(YtSsiVoiceState.Idle)
                if (!text.isNullOrBlank()) onText(text)
                destroy()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                onState(YtSsiVoiceState.Listening(text, onDevice))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { created.startListening(intent) }.onFailure {
            onState(YtSsiVoiceState.Failed(SpeechRecognizer.ERROR_CLIENT))
            destroy()
        }
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private companion object {
        const val TAG = "YtSsiVoice"
    }
}
