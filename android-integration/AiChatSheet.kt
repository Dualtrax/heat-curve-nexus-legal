package com.yourapp.ai

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Full-height BottomSheet that shows the AI chat.
 * Open it from any Activity with:
 *   AiChatSheet.show(supportFragmentManager)
 */
class AiChatSheet : BottomSheetDialogFragment() {

    private lateinit var memory:   MemoryManager
    private lateinit var llm:      LlmManager
    private lateinit var messages: LinearLayout
    private lateinit var scroll:   ScrollView
    private lateinit var input:    EditText
    private lateinit var sendBtn:  ImageButton
    private lateinit var status:   TextView

    private val curMessages = mutableListOf<MemoryManager.Message>()
    private val convId      = UUID.randomUUID().toString()
    private var generating  = false

    companion object {
        fun show(fm: androidx.fragment.app.FragmentManager) {
            AiChatSheet().show(fm, "ai_chat")
        }
    }

    override fun onStart() {
        super.onStart()
        // Expand to full height
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        memory = MemoryManager(requireContext())
        llm    = LlmManager.get(requireContext())

        return buildLayout()
    }

    // ── Build UI programmatically (no XML needed) ─────────────────────────────

    private fun buildLayout(): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D0D14.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(0xFF13131F.toInt())
        }
        val titleTv = TextView(requireContext()).apply {
            text      = "🤖 KI-Assistent"
            textSize  = 17f
            setTextColor(0xFFE2E2F0.toInt())
            typeface  = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        status = TextView(requireContext()).apply {
            text      = if (llm.state == LlmManager.State.READY) "● Bereit" else "● Lädt…"
            textSize  = 12f
            setTextColor(if (llm.state == LlmManager.State.READY) 0xFF22C55E.toInt() else 0xFFEAB308.toInt())
        }
        val closeBtn = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setColorFilter(0xFF8888AA.toInt())
            setOnClickListener { dismiss() }
        }
        header.addView(titleTv)
        header.addView(status)
        header.addView(closeBtn)

        // Messages scroll
        scroll = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        messages = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(messages)

        // Greeting
        if (llm.state == LlmManager.State.READY) {
            val name  = memory.userName
            val greet = if (name != "Unbekannt") "Hallo $name! 👋 Wie kann ich helfen?"
                        else "Hallo! 👋 Wie kann ich helfen?"
            appendAiBubble(greet)
        } else {
            appendSystemMsg("Modell wird noch geladen…")
            waitForReady()
        }

        // Input row
        val inputRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(16))
            setBackgroundColor(0xFF13131F.toInt())
        }
        input = EditText(requireContext()).apply {
            hint    = "Nachricht schreiben…"
            setHintTextColor(0xFF555577.toInt())
            setTextColor(0xFFE2E2F0.toInt())
            setBackgroundColor(0xFF1C1C2E.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines    = 4
            inputType   = android.text.InputType.TYPE_CLASS_TEXT or
                          android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 15f
        }
        sendBtn = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundColor(0xFF7C3AED.toInt())
            setColorFilter(android.graphics.Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isEnabled = (llm.state == LlmManager.State.READY)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).also {
                it.marginStart = dp(8)
            }
            setOnClickListener { onSend() }
        }
        input.addTextChangedListener {
            sendBtn.isEnabled = llm.state == LlmManager.State.READY && !generating
        }
        inputRow.addView(input)
        inputRow.addView(sendBtn)

        root.addView(header)
        root.addView(scroll)
        root.addView(inputRow)
        return root
    }

    // ── Wait for model ready ──────────────────────────────────────────────────

    private fun waitForReady() {
        lifecycleScope.launch {
            while (llm.state != LlmManager.State.READY && llm.state != LlmManager.State.ERROR) {
                kotlinx.coroutines.delay(500)
            }
            if (llm.state == LlmManager.State.READY) {
                status.text      = "● Bereit"
                status.setTextColor(0xFF22C55E.toInt())
                sendBtn.isEnabled = true
                appendSystemMsg("✅ Modell geladen!")
                val greet = if (memory.userName != "Unbekannt")
                    "Hallo ${memory.userName}! 👋 Wie kann ich helfen?"
                else "Hallo! 👋 Wie kann ich helfen?"
                appendAiBubble(greet)
            } else {
                status.text = "● Fehler"
                status.setTextColor(0xFFEF4444.toInt())
                appendSystemMsg("❌ Modell konnte nicht geladen werden.")
            }
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    private fun onSend() {
        if (generating || llm.state != LlmManager.State.READY) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()

        appendUserBubble(text)
        curMessages.add(MemoryManager.Message("user", text))

        generating = true
        sendBtn.isEnabled = false

        val aiBubble = appendAiBubble("", streaming = true)
        val sb       = StringBuilder()

        // Build prompt with memory context
        val systemPrompt = memory.buildSystemPrompt()
        val historyStr   = curMessages.takeLast(14).joinToString("\n") { m ->
            when (m.role) {
                "user"      -> "Nutzer: ${m.content}"
                "assistant" -> "Assistent: ${m.content}"
                else        -> m.content
            }
        }
        val fullPrompt = "<start_of_turn>system\n$systemPrompt<end_of_turn>\n" +
                         historyStr + "\n<start_of_turn>model\n"

        llm.generateAsync(fullPrompt) { token, done ->
            requireActivity().runOnUiThread {
                sb.append(token)
                aiBubble.text = sb.toString()
                scrollBot()

                if (done) {
                    generating = false
                    sendBtn.isEnabled = true
                    val reply = sb.toString()
                    curMessages.add(MemoryManager.Message("assistant", reply))
                    saveConversation()

                    // Extract facts after every 3 exchanges
                    if (curMessages.size % 6 == 0) extractFacts()
                }
            }
        }
    }

    // ── Fact extraction ───────────────────────────────────────────────────────

    private fun extractFacts() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conv = curMessages.takeLast(6).joinToString("\n") { "${it.role}: ${it.content}" }
                val prompt = "<start_of_turn>user\nExtrahiere Fakten über den Nutzer aus diesem Gespräch. " +
                             "Antworte NUR mit einem JSON-Array wie [\"Fact1\",\"Fact2\"]. " +
                             "Maximal 4 Fakten. Wenn keine: []\n\nGespräch:\n$conv<end_of_turn>\n" +
                             "<start_of_turn>model\n"

                var result = ""
                // We need a synchronous-ish call here; use a simple latch
                val latch = java.util.concurrent.CountDownLatch(1)
                llm.generateAsync(prompt) { tok, done ->
                    result += tok
                    if (done) latch.countDown()
                }
                latch.await(15, java.util.concurrent.TimeUnit.SECONDS)

                val match = Regex("\\[.*?\\]", RegexOption.DOT_MATCHES_ALL).find(result)
                if (match != null) {
                    val arr   = org.json.JSONArray(match.value)
                    val facts = (0 until arr.length()).map { arr.getString(it) }
                    memory.addFacts(facts)
                }
            } catch (_: Exception) { /* extraction not critical */ }
        }
    }

    // ── Conversation persistence ──────────────────────────────────────────────

    private fun saveConversation() {
        val conv = MemoryManager.Conversation(
            id       = convId,
            dateMs   = System.currentTimeMillis(),
            summary  = curMessages.firstOrNull { it.role == "user" }?.content
                           ?.take(40) ?: "Gespräch",
            messages = curMessages.toList()
        )
        memory.saveConversation(conv)
    }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private fun appendUserBubble(text: String): TextView {
        return appendBubble(text, isUser = true)
    }

    private fun appendAiBubble(text: String, streaming: Boolean = false): TextView {
        return appendBubble(text, isUser = false, streaming = streaming)
    }

    private fun appendBubble(text: String, isUser: Boolean, streaming: Boolean = false): TextView {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(10) }
            gravity = if (isUser) Gravity.END else Gravity.START
        }
        val tv = TextView(requireContext()).apply {
            this.text    = if (streaming) "…" else text
            textSize     = 14f
            setTextColor(if (isUser) 0xFFBFDBFE.toInt() else 0xFFE2E2F0.toInt())
            setBackgroundColor(if (isUser) 0xFF1E3A5F.toInt() else 0xFF1C1C2E.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth     = (resources.displayMetrics.widthPixels * 0.82).toInt()
        }
        row.addView(tv)
        messages.addView(row)
        scrollBot()
        return tv
    }

    private fun appendSystemMsg(text: String) {
        val tv = TextView(requireContext()).apply {
            this.text = text
            textSize  = 12f
            setTextColor(0xFF8888AA.toInt())
            gravity   = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        messages.addView(tv)
        scrollBot()
    }

    private fun scrollBot() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
