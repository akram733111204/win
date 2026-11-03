package com.rukn.expiry.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** First-party Android voice service: no user-entered URLs or API keys. */
class ArabicSpeech(private val activity: Activity) {
    private var recognizer: SpeechRecognizer? = null
    private var usingOffline = false
    private val languages = listOf("ar-YE", "ar-SA", "ar-EG", "ar")
    private var languageIndex = 0
    private var onResult: ((String) -> Unit)? = null
    private var onFailure: ((String) -> Unit)? = null
    private var onStatus: ((String) -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(activity) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.forLanguageTag("ar"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    fun speak(message: String) {
        if (ttsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "rukn-feedback")
    }

    fun start(
        onText: (String) -> Unit,
        onError: (String) -> Unit,
        onProgress: (String) -> Unit
    ) {
        onResult = onText; onFailure = onError; onStatus = onProgress
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onError("فعّل إذن الميكروفون أولًا، ثم اضغط زر التسجيل مجددًا.")
            return
        }
        stop()
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
        usingOffline = onDevice
        languageIndex = 0
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(activity)) {
            onError("لا توجد خدمة تعرف على الكلام في هذا الهاتف. ثبّت خدمة Google Speech Recognition أو فعّل خدمة الصوت من إعدادات أندرويد.")
            return
        }
        createRecognizer(onDevice)
    }

    private fun createRecognizer(preferOnDevice: Boolean) {
        try {
            recognizer = if (preferOnDevice && Build.VERSION.SDK_INT >= 31)
                SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            else SpeechRecognizer.createSpeechRecognizer(activity)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onStatus?.invoke("استمع إليك الآن…") }
                override fun onBeginningOfSpeech() { onStatus?.invoke("تحدث بصورة طبيعية") }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { onStatus?.invoke("أحوّل الكلام إلى نص…") }
                override fun onPartialResults(partialResults: Bundle?) {
                    val part = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!part.isNullOrBlank()) onStatus?.invoke(part)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onResults(results: Bundle?) {
                    val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    stop()
                    if (transcript.isNullOrBlank()) onFailure?.invoke("لم أسمع كلامًا واضحًا؛ حاول مرة أخرى")
                    else onResult?.invoke(transcript)
                }
                override fun onError(error: Int) {
                    stop()
                    // If the installed offline engine cannot recognize this locale, retry the system engine.
                    val languageError = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                    if (languageError && languageIndex < languages.lastIndex) {
                        languageIndex++
                        onStatus?.invoke("أجرّب حزمة عربية أخرى…")
                        createRecognizer(usingOffline)
                    } else if (usingOffline && SpeechRecognizer.isRecognitionAvailable(activity) &&
                        (languageError || error == SpeechRecognizer.ERROR_CLIENT)) {
                        usingOffline = false
                        languageIndex = 0
                        onStatus?.invoke("أجرّب خدمة التعرف الأساسية في أندرويد…")
                        createRecognizer(false)
                    } else onFailure?.invoke(when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "لم تتضح الكلمات. جرّب نطق اسم الحقل ثم القيمة."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "انتهى وقت الاستماع دون صوت."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "خدمة الصوت تحتاج اتصالًا بالإنترنت في هذا الهاتف."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "إذن الميكروفون غير متاح."
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "حزمة التعرف على العربية غير مثبتة على الجهاز."
                        else -> "تعذر تشغيل خدمة التعرف على الكلام (رمز $error)."
                    })
                }
            })
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages[languageIndex])
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languages[languageIndex])
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
            })
        } catch (e: Exception) {
            stop()
            onFailure?.invoke("تعذر بدء التسجيل: ${e.localizedMessage ?: "خطأ في خدمة الصوت"}")
        }
    }

    fun stop() { recognizer?.cancel(); recognizer?.destroy(); recognizer = null }
    fun close() { stop(); tts?.stop(); tts?.shutdown(); tts = null }
}
