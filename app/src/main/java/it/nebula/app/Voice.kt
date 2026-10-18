package it.nebula.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Voce (TTS) e ascolto (riconoscimento vocale). Va usato dal thread principale. */
class Voice(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var rec: SpeechRecognizer? = null
    private val pending = ArrayList<Pair<String, (() -> Unit)?>>()

    init {
        tts = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ITALIAN; ready = true
                pending.forEach { speak(it.first, it.second) }; pending.clear()
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready) { pending.add(text to onDone); return }
        val id = System.nanoTime().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onError(u: String?) { android.os.Handler(ctx.mainLooper).post { onDone?.invoke() } }
            override fun onDone(u: String?) { android.os.Handler(ctx.mainLooper).post { onDone?.invoke() } }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun listen(onResult: (String?) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onResult(null); return }
        rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(r: Bundle?) {
                onResult(r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
            override fun onError(e: Int) { onResult(null) }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        rec?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
        })
    }

    fun stop() { rec?.destroy(); rec = null; tts?.stop() }
    fun shutdown() { stop(); tts?.shutdown() }
}
