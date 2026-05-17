package com.yourapp.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists user profile + conversation history in SharedPreferences.
 * The profile is injected into every LLM system prompt so the AI
 * remembers the user across sessions.
 */
class MemoryManager(context: Context) {

    private val prefs = context.getSharedPreferences("ai_memory", Context.MODE_PRIVATE)

    // ── Profile ──────────────────────────────────────────────────────────────

    var userName: String
        get() = prefs.getString("user_name", "Unbekannt") ?: "Unbekannt"
        set(v) { prefs.edit().putString("user_name", v).apply() }

    fun getFacts(): List<String> {
        val raw = prefs.getString("facts", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun addFacts(newFacts: List<String>) {
        val existing = getFacts().toMutableList()
        newFacts.forEach { f ->
            val trimmed = f.trim()
            if (trimmed.isNotEmpty() && !existing.contains(trimmed)) existing.add(trimmed)
        }
        val capped = existing.takeLast(40)
        val arr    = JSONArray().also { capped.forEach(it::put) }
        prefs.edit().putString("facts", arr.toString()).apply()

        // Try to extract user name from facts
        if (userName == "Unbekannt") {
            capped.firstOrNull { it.contains(Regex("hei(ß|ss)t\\s+\\w+", RegexOption.IGNORE_CASE)) }
                ?.let { fact ->
                    Regex("hei(ß|ss)t\\s+(\\w+)", RegexOption.IGNORE_CASE)
                        .find(fact)?.groupValues?.get(2)?.let { userName = it }
                }
        }
    }

    fun deleteFact(index: Int) {
        val list = getFacts().toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            val arr = JSONArray().also { list.forEach(it::put) }
            prefs.edit().putString("facts", arr.toString()).apply()
        }
    }

    // ── Conversation history ──────────────────────────────────────────────────

    fun getConversations(): List<Conversation> {
        val raw = prefs.getString("convs", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Conversation.fromJson(arr.getJSONObject(it)) }
    }

    fun saveConversation(conv: Conversation) {
        val list   = getConversations().toMutableList()
        val idx    = list.indexOfFirst { it.id == conv.id }
        val capped = conv.copy(messages = conv.messages.takeLast(40))
        if (idx >= 0) list[idx] = capped else list.add(0, capped)
        val kept = list.takeLast(30)
        val arr  = JSONArray().also { kept.forEach { c -> it.put(c.toJson()) } }
        prefs.edit().putString("convs", arr.toString()).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // ── System prompt builder ─────────────────────────────────────────────────

    fun buildSystemPrompt(): String {
        val facts   = getFacts()
        val name    = userName
        val factStr = if (facts.isNotEmpty())
            "\n\nWas du über den Nutzer weißt:\n" + facts.joinToString("\n") { "- $it" }
        else ""

        return """Du bist ein freundlicher, hilfreicher KI-Assistent und antwortest immer auf Deutsch.
Du läufst vollständig lokal auf dem Gerät des Nutzers – keine Daten werden gesendet.
Du erinnerst dich an vergangene Gespräche und lernst den Nutzer immer besser kennen.
Wenn du neue Infos über ${if (name != "Unbekannt") name else "den Nutzer"} erfährst, merke sie dir.
Antworte natürlich und hilfreich.$factStr"""
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    data class Conversation(
        val id:       String,
        val dateMs:   Long,
        val summary:  String,
        val messages: List<Message>
    ) {
        fun toJson() = JSONObject().apply {
            put("id",      id)
            put("dateMs",  dateMs)
            put("summary", summary)
            put("messages", JSONArray().also { arr ->
                messages.forEach { m -> arr.put(JSONObject().apply {
                    put("role",    m.role)
                    put("content", m.content)
                }) }
            })
        }

        companion object {
            fun fromJson(o: JSONObject): Conversation {
                val msgsArr = o.getJSONArray("messages")
                val msgs    = (0 until msgsArr.length()).map {
                    val m = msgsArr.getJSONObject(it)
                    Message(m.getString("role"), m.getString("content"))
                }
                return Conversation(
                    id      = o.getString("id"),
                    dateMs  = o.getLong("dateMs"),
                    summary = o.getString("summary"),
                    messages = msgs
                )
            }
        }
    }

    data class Message(val role: String, val content: String)
}
