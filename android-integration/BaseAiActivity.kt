package com.yourapp.ai

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Extend this instead of AppCompatActivity in every Activity where
 * the AI floating button should appear.
 *
 * Example:
 *   class MainActivity : BaseAiActivity() { ... }
 *
 * The FAB appears bottom-right and opens AiChatSheet on tap.
 * Model download runs once on first launch (with a progress dialog).
 */
abstract class BaseAiActivity : AppCompatActivity() {

    private var fab: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureModelReady()
    }

    override fun onResume() {
        super.onResume()
        attachFab()
    }

    override fun onPause() {
        super.onPause()
        removeFab()
    }

    // ── Floating button ───────────────────────────────────────────────────────

    private fun attachFab() {
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (fab?.parent != null) return  // already attached

        fab = buildFab().also { root.addView(it) }
    }

    private fun removeFab() {
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        fab?.let { root?.removeView(it) }
    }

    private fun buildFab(): View {
        val size   = dp(56)
        val margin = dp(20)

        val btn = TextView(this).apply {
            text      = "🤖"
            textSize  = 24f
            gravity   = Gravity.CENTER
            width     = size; height = size
            setBackgroundColor(0xFF7C3AED.toInt())
            elevation = dp(6).toFloat()

            // Round shape
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF7C3AED.toInt())
            }

            setOnClickListener {
                AiChatSheet.show(supportFragmentManager)
            }
        }

        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity      = Gravity.BOTTOM or Gravity.END
            bottomMargin = margin
            rightMargin  = margin
        }
        btn.layoutParams = params
        return btn
    }

    // ── Model download / init ─────────────────────────────────────────────────

    private fun ensureModelReady() {
        val llm = LlmManager.get(this)
        when {
            llm.state == LlmManager.State.READY -> return
            !llm.isDownloaded                    -> promptDownload()
            else                                  -> initModel()
        }
    }

    private fun promptDownload() {
        AlertDialog.Builder(this)
            .setTitle("🤖 KI-Assistent einrichten")
            .setMessage(
                "Für den lokalen KI-Assistenten wird einmalig ein Modell heruntergeladen (~1.5 GB).\n\n" +
                "Das Modell läuft danach komplett auf deinem Gerät – keine Daten werden gesendet."
            )
            .setPositiveButton("Herunterladen") { _, _ -> downloadModel() }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun downloadModel() {
        val llm = LlmManager.get(this)

        val progress = android.app.ProgressDialog(this).apply {
            setTitle("Modell wird heruntergeladen…")
            setMessage("0%")
            isIndeterminate = false
            max             = 100
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            val result = llm.downloadModel { pct ->
                runOnUiThread {
                    progress.progress = pct
                    progress.setMessage("$pct%")
                }
            }
            progress.dismiss()
            if (result.isSuccess) {
                initModel()
            } else {
                AlertDialog.Builder(this@BaseAiActivity)
                    .setTitle("Download fehlgeschlagen")
                    .setMessage("Bitte prüfe deine Internetverbindung und versuche es erneut.\n\n${result.exceptionOrNull()?.message}")
                    .setPositiveButton("Erneut versuchen") { _, _ -> downloadModel() }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
    }

    private fun initModel() {
        val llm = LlmManager.get(this)
        lifecycleScope.launch {
            llm.initialize()
            // FAB is already visible – model is now ready for chat
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
