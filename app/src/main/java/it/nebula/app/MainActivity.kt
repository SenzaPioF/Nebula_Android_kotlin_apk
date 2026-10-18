package it.nebula.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var voice: Voice
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val prefs by lazy { getSharedPreferences("nebula", MODE_PRIVATE) }

    private fun lp() = LinearLayout.LayoutParams(0, -2, 1f)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        voice = Voice(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(0xFF0B0F1A.toInt())
        }
        fun txt(t: String) = TextView(this).apply { text = t; setTextColor(0xFFCFE8FF.toInt()) }
        fun field(h: String, v: String, pw: Boolean = false) = EditText(this).apply {
            hint = h; setText(v)
            if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF7A8AA0.toInt())
        }

        root.addView(txt("NEBULA").apply { textSize = 24f; gravity = Gravity.CENTER })

        // --- impostazioni (nascoste finché non le apri)
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val key = field("Chiave API (Groq, Gemini, OpenRouter...)", prefs.getString("key", "") ?: "", true)
        val owner = field("Il tuo nome (come ti nomina Nebula al telefono)", prefs.getString("owner", "") ?: "")
        val delay = field("Secondi di squillo prima di rispondere", prefs.getInt("bot_delay", 12).toString())
            .apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val botSw = Switch(this).apply {
            text = "Segreteria intelligente (risponde Nebula)"; setTextColor(0xFFCFE8FF.toInt())
            isChecked = prefs.getBoolean("bot_enabled", false)
        }
        val unkSw = Switch(this).apply {
            text = "Solo numeri non in rubrica"; setTextColor(0xFFCFE8FF.toInt())
            isChecked = prefs.getBoolean("bot_unknown_only", false)
        }
        settings.addView(key); settings.addView(owner); settings.addView(delay)
        settings.addView(botSw); settings.addView(unkSw)
        settings.addView(Button(this).apply {
            text = "Salva impostazioni"
            setOnClickListener {
                prefs.edit()
                    .putString("key", key.text.toString().trim())
                    .putString("owner", owner.text.toString().trim())
                    .putInt("bot_delay", delay.text.toString().toIntOrNull() ?: 12)
                    .putBoolean("bot_enabled", botSw.isChecked)
                    .putBoolean("bot_unknown_only", unkSw.isChecked).apply()
                add("Impostazioni salvate.")
            }
        })

        val row1 = LinearLayout(this)
        row1.addView(Button(this).apply { text = "Impostazioni"; setOnClickListener {
            settings.visibility = if (settings.visibility == View.GONE) View.VISIBLE else View.GONE } }, lp())
        row1.addView(Button(this).apply { text = "Registro chiamate"; setOnClickListener {
            val l = prefs.getString("calls_log", "") ?: ""
            add(if (l.isBlank()) "Nessuna chiamata gestita finora." else l) } }, lp())
        val row2 = LinearLayout(this)
        row2.addView(Button(this).apply { text = "Attiva chiamate"; setOnClickListener { startCalls() } }, lp())
        row2.addView(Button(this).apply { text = "Stop"; setOnClickListener {
            stopService(Intent(this@MainActivity, NebulaService::class.java)); add("Gestione chiamate disattivata.") } }, lp())
        root.addView(row1); root.addView(row2); root.addView(settings)

        log = txt("").apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        input = field("Scrivi a Nebula", "")
        val bar = LinearLayout(this)
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(this).apply { text = "🎤"; setOnClickListener { mic() } })
        bar.addView(Button(this).apply { text = "Invia"; setOnClickListener { send(input.text.toString()) } })
        root.addView(bar)
        setContentView(root)

        requestPermissions(arrayOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS), 1)
        add("Ciao, sono Nebula. Apri Impostazioni, inserisci la chiave API e salva.")
    }

    private fun add(s: String) { log.append(s + "\n\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }

    private fun mic() = voice.listen { r -> if (r == null) add("Non ho sentito nulla.") else send(r) }

    private fun send(text: String) {
        if (text.isBlank()) return
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) { add("Prima inserisci una chiave API (Impostazioni)."); return }
        add("Tu: $text"); input.setText("")
        thread {
            val ans = try { Llm.ask(key, text) } catch (e: Exception) { "Errore di rete: ${e.message}" }
            runOnUiThread { add("Nebula: $ans"); voice.speak(ans) }
        }
    }

    private fun startCalls() {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            add("Concedi prima i permessi Telefono, Microfono e Rubrica dalle impostazioni di Android."); return
        }
        startForegroundService(Intent(this, NebulaService::class.java))
        add(if (prefs.getBoolean("bot_enabled", false))
            "Segreteria intelligente attiva: se non rispondi, Nebula risponde dopo ${prefs.getInt("bot_delay", 12)} secondi."
        else "Gestione chiamate attiva: a ogni chiamata ti dico chi è e puoi dire «rispondi» o «rifiuta».")
    }

    override fun onDestroy() { voice.shutdown(); super.onDestroy() }
}
