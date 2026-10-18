package it.nebula.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Segreteria intelligente: si presenta, offre un menu a voce, poi raccoglie un messaggio,
 * una richiesta di richiamata oppure conversa con il chiamante tramite l'IA.
 * Tutto sul thread principale; l'IA gira in un thread a parte.
 */
@Suppress("DEPRECATION")
class CallBot(
    private val ctx: Context,
    private val voice: Voice,
    private val caller: String,
    private val hangUp: () -> Unit
) {
    private enum class Mode { MENU, MESSAGE, CALLBACK, CHAT }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("nebula", Context.MODE_PRIVATE)
    private val owner = prefs.getString("owner", "").orEmpty().ifBlank { "il titolare di questo telefono" }
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val convo = JSONArray()
    private val transcript = StringBuilder()
    private var mode = Mode.MENU
    private var tag = "Nessuna scelta"
    private var silent = 0
    private var turns = 0
    private var finished = false
    private var oldVolume = -1

    fun start() {
        prepareAudio()
        main.postDelayed({ closing("Il tempo a disposizione è terminato. La saluto, arrivederci.") }, 4 * 60 * 1000L)
        say(
            "Buongiorno, sono Nebula, l'assistente virtuale di $owner. In questo momento non può rispondere. " +
                "Può dire: uno, per lasciare un messaggio; due, per chiedere di essere richiamato; " +
                "tre, per parlare con me. Cosa preferisce?"
        ) { listenTurn() }
    }

    /** Il chiamante ha riattaccato (o la chiamata è finita): chiude e salva. */
    fun abort() {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        voice.stop()
        wrapUp()
    }

    private fun say(text: String, then: (() -> Unit)? = null) {
        if (finished) return
        transcript.append("Nebula: ").append(text).append('\n')
        voice.speak(text) { if (!finished) then?.invoke() }
    }

    private fun listenTurn() {
        if (finished) return
        voice.listen { heard -> main.post { onHeard(heard) } }
    }

    private fun onHeard(heard: String?) {
        if (finished) return
        if (heard.isNullOrBlank()) {
            silent++
            if (silent >= 2) closing("Non riesco a sentirla, la saluto. Arrivederci.")
            else say("Non l'ho sentita bene. Può ripetere?") { listenTurn() }
            return
        }
        silent = 0
        transcript.append("Chiamante: ").append(heard).append('\n')
        val t = heard.lowercase(Locale.ITALIAN)
        when (mode) {
            Mode.MENU -> when {
                Regex("\\b(uno|1)\\b|messaggio|lasci").containsMatchIn(t) -> {
                    mode = Mode.MESSAGE; tag = "Ha lasciato un messaggio"
                    say("Va bene. Dica pure il suo messaggio, la ascolto.") { listenTurn() }
                }
                Regex("\\b(due|2)\\b|richiam").containsMatchIn(t) -> {
                    mode = Mode.CALLBACK; tag = "Chiede di essere richiamato"
                    say("Certo. Mi dica il suo nome e il motivo della chiamata.") { listenTurn() }
                }
                Regex("\\b(tre|3)\\b|parlare").containsMatchIn(t) -> {
                    mode = Mode.CHAT; tag = "Conversazione"
                    say("Certo, mi dica pure.") { listenTurn() }
                }
                else -> { mode = Mode.CHAT; tag = "Conversazione"; chat(heard) }
            }
            Mode.MESSAGE -> closing("Perfetto, ho annotato il messaggio e lo riferirò a $owner. Arrivederci.")
            Mode.CALLBACK -> closing("Grazie, ho annotato la richiesta e $owner la richiamerà appena possibile. Arrivederci.")
            Mode.CHAT -> chat(heard)
        }
    }

    private fun systemPrompt() =
        "Sei Nebula, l'assistente telefonico di $owner, che al momento non può rispondere. " +
            "Stai parlando al telefono con una persona che ha chiamato. Rispondi sempre in italiano, con al massimo " +
            "due frasi brevi adatte alla voce, senza elenchi né emoji. Non inventare impegni, dati personali o " +
            "decisioni di $owner: se non sai, offri di annotare un messaggio. Se la persona ha finito o saluta, " +
            "concludi con un saluto e aggiungi [FINE] alla fine."

    private fun chat(userText: String) {
        turns++
        convo.put(JSONObject().put("role", "user").put("content", userText))
        val key = prefs.getString("key", "").orEmpty()
        thread {
            val reply = try { Llm.complete(key, systemPrompt(), convo) } catch (e: Exception) { "Errore: ${e.message}" }
            main.post {
                if (finished) return@post
                if (reply.startsWith("Errore")) {
                    closing("Mi scusi, c'è un problema tecnico. Riferirò della sua chiamata a $owner. Arrivederci.")
                    return@post
                }
                val end = reply.contains("[FINE]")
                val clean = reply.replace("[FINE]", "").trim()
                convo.put(JSONObject().put("role", "assistant").put("content", clean))
                if (end || turns >= 6) closing(clean) else say(clean) { listenTurn() }
            }
        }
    }

    private fun closing(text: String) {
        if (finished) return
        say(text) { finish() }
        main.postDelayed({ finish() }, 20000)   // rete di sicurezza se la voce non parte
    }

    private fun finish() {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        wrapUp()
        main.postDelayed({ hangUp() }, 600)
    }

    private fun wrapUp() {
        restoreAudio()
        val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date())
        val entry = "[$stamp] $caller — $tag\n$transcript\n──────\n"
        val old = prefs.getString("calls_log", "").orEmpty()
        prefs.edit().putString("calls_log", (entry + old).take(30000)).apply()
        try {
            val summary = transcript.lineSequence().filter { it.startsWith("Chiamante:") }.joinToString(" ").take(300)
            val n = Notification.Builder(ctx, "nebula_calls")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Chiamata gestita: $caller")
                .setContentText(tag)
                .setStyle(Notification.BigTextStyle().bigText(summary.ifBlank { "Nessuna risposta dal chiamante." }))
                .build()
            ctx.getSystemService(NotificationManager::class.java)
                .notify((System.currentTimeMillis() % 100000).toInt() + 10, n)
        } catch (_: Exception) {}
    }

    private fun prepareAudio() {
        try { audio.isSpeakerphoneOn = true } catch (_: Exception) {}
        try {
            oldVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.9).toInt(), 0)
        } catch (_: Exception) {}
    }

    private fun restoreAudio() {
        try { if (oldVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0) } catch (_: Exception) {}
        try { audio.isSpeakerphoneOn = false } catch (_: Exception) {}
    }
}
