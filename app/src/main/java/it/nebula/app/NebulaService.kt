package it.nebula.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager

/**
 * Servizio in primo piano. Due modalità:
 *  - segreteria intelligente (impostazione): risponde dopo N secondi e fa parlare CallBot;
 *  - altrimenti: annuncia chi chiama e risponde/rifiuta con un comando vocale.
 */
@Suppress("DEPRECATION", "MissingPermission")
class NebulaService : Service() {
    private lateinit var voice: Voice
    private lateinit var tm: TelephonyManager
    private lateinit var telecom: TelecomManager
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("nebula", MODE_PRIVATE) }
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var busy = false
    private var botAnswered = false
    private var bot: CallBot? = null
    private var ringNumber: String? = null
    private val autoAnswer = Runnable { answerForBot() }

    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, number: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                        if (!number.isNullOrBlank()) ringNumber = number
                    } else if (!busy) {
                        busy = true
                        ringNumber = number?.takeIf { it.isNotBlank() }
                        onIncoming()
                    }
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> if (botAnswered && bot == null) startBot()
                TelephonyManager.CALL_STATE_IDLE -> endOfCall()
            }
            lastState = state
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nebula", "Nebula", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("nebula_calls", "Chiamate gestite", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(this, "nebula")
            .setContentTitle("Nebula in ascolto")
            .setContentText("Gestisco le chiamate in arrivo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        startForeground(1, n)
        voice = Voice(this)
        tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telecom = getSystemService(TELECOM_SERVICE) as TelecomManager
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun lookup(number: String?): String? {
        if (number.isNullOrBlank()) return null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun nameOf(number: String?): String =
        lookup(number) ?: number?.takeIf { it.isNotBlank() }?.toCharArray()?.joinToString(" ") ?: "un numero sconosciuto"

    private fun onIncoming() {
        if (prefs.getBoolean("bot_enabled", false)) {
            val delay = prefs.getInt("bot_delay", 12).coerceIn(3, 60)
            main.postDelayed(autoAnswer, delay * 1000L)
        } else {
            main.post {
                voice.speak("Chiamata da ${nameOf(ringNumber)}. Rispondo o rifiuto?") {
                    voice.listen { heard -> main.post { handle(heard) } }
                }
            }
        }
    }

    private fun answerForBot() {
        if (lastState != TelephonyManager.CALL_STATE_RINGING) return
        if (prefs.getBoolean("bot_unknown_only", false) && lookup(ringNumber) != null) return
        try {
            botAnswered = true
            telecom.acceptRingingCall()
        } catch (e: SecurityException) {
            botAnswered = false
        }
    }

    private fun startBot() {
        val who = lookup(ringNumber) ?: ringNumber ?: "numero sconosciuto"
        main.postDelayed({
            if (lastState == TelephonyManager.CALL_STATE_OFFHOOK && bot == null) {
                bot = CallBot(this, voice, who) { hangUp() }.also { it.start() }
            }
        }, 1200)
    }

    private fun hangUp() {
        try { if (Build.VERSION.SDK_INT >= 28) telecom.endCall() } catch (_: Exception) {}
    }

    private fun endOfCall() {
        main.removeCallbacks(autoAnswer)
        bot?.abort(); bot = null
        botAnswered = false; busy = false; ringNumber = null
        voice.stop()
    }

    private fun handle(heard: String?) {
        val t = heard?.lowercase() ?: ""
        try {
            when {
                Regex("rispond|passa|accett|sì|si ").containsMatchIn("$t ") -> telecom.acceptRingingCall()
                Regex("rifiut|chiudi|no |non").containsMatchIn("$t ") -> if (Build.VERSION.SDK_INT >= 28) telecom.endCall()
                else -> voice.speak("Non ho capito, lascio squillare.")
            }
        } catch (e: SecurityException) {
            voice.speak("Mi manca il permesso per gestire le chiamate.")
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(autoAnswer)
        bot?.abort()
        tm.listen(listener, PhoneStateListener.LISTEN_NONE)
        voice.shutdown()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
