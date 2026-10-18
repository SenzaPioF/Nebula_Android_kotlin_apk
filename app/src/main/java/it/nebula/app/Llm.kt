package it.nebula.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Client per qualsiasi servizio compatibile con "chat/completions" (Groq, Gemini, OpenRouter, Mistral...). */
object Llm {
    val PRESETS = mapOf(
        "groq" to Pair("https://api.groq.com/openai/v1", "openai/gpt-oss-20b"),
        "gemini" to Pair("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
        "openrouter" to Pair("https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free"),
        "mistral" to Pair("https://api.mistral.ai/v1", "mistral-small-latest"),
        "cerebras" to Pair("https://api.cerebras.ai/v1", "gpt-oss-120b")
    )
    private val history = JSONArray()
    const val SYSTEM = "Sei Nebula, un assistente vocale personale. Rispondi in italiano, in modo breve e naturale."

    fun detect(key: String): String = when {
        key.startsWith("gsk_") -> "groq"
        key.startsWith("AIza") -> "gemini"
        key.startsWith("sk-or-") -> "openrouter"
        key.startsWith("csk-") -> "cerebras"
        else -> "mistral"
    }

    /** Conversazione della chat principale (con memoria degli ultimi messaggi). */
    fun ask(key: String, text: String): String {
        history.put(JSONObject().put("role", "user").put("content", text))
        while (history.length() > 20) history.remove(0)
        val out = complete(key, SYSTEM, history)
        if (out.startsWith("Errore")) history.remove(history.length() - 1)
        else history.put(JSONObject().put("role", "assistant").put("content", out))
        return out
    }

    /** Chiamata senza stato: system prompt + turni passati dal chiamante. */
    fun complete(key: String, system: String, turns: JSONArray): String {
        val (base, model) = PRESETS[detect(key)]!!
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until turns.length()) msgs.put(turns.get(i))
        val body = JSONObject().put("model", model).put("messages", msgs)
        val c = URL("$base/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) return "Errore $code: ${raw.take(200)}"
        return JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
    }
}
