package com.antisdvg.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.antisdvg.AntiSdvgApp
import com.antisdvg.R
import com.antisdvg.databinding.ActivityReaderBinding
import com.antisdvg.ui.AppViewModelFactory
import kotlinx.coroutines.launch

/**
 * The built-in reader.
 *
 * Honest counting: the timer only runs while this screen is resumed, the device
 * is awake and the reader is not idle (no scroll or touch for a few minutes).
 * Progress is taken from the real scroll position, so pages cannot be claimed
 * without moving through the text. On finish the user passes a light offline
 * test built from the passage just read, and only then minutes are awarded.
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private val repo by lazy { AppViewModelFactory.repository(application as AntiSdvgApp) }
    private val viewModel: ReaderViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(repo) as T
        }
    }

    private val uiPrefs by lazy { getSharedPreferences("reader_ui", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var lastActivityMs = 0L
    private var htmlInjected = false
    private var lastTheme = ReaderTheme.PAPER
    private var lastFont = 100

    private val ticker = object : Runnable {
        override fun run() {
            val active = SystemClock.elapsedRealtime() - lastActivityMs < IDLE_TIMEOUT_MS
            if (active) {
                viewModel.onTick()
                renderTimer()
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        viewModel.load(
            bookId = bookId,
            theme = ReaderTheme.entries.getOrElse(uiPrefs.getInt(KEY_THEME, 0)) { ReaderTheme.PAPER },
            fontPercent = uiPrefs.getInt(KEY_FONT, 100)
        )

        binding.readerWeb.settings.javaScriptEnabled = true
        binding.readerWeb.settings.textZoom = 100
        binding.readerWeb.addJavascriptInterface(Bridge(), "ReaderBridge")
        binding.readerWeb.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                restorePosition()
            }
        }
        binding.readerWeb.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) lastActivityMs = SystemClock.elapsedRealtime()
            v.onTouchEvent(event)
            false
        }

        binding.readerClose.setOnClickListener { confirmExit() }
        binding.readerFinish.setOnClickListener { onFinishClicked() }
        binding.readerTheme.setOnClickListener { viewModel.cycleTheme() }
        binding.readerFontMinus.setOnClickListener { viewModel.adjustFont(-10) }
        binding.readerFontPlus.setOnClickListener { viewModel.adjustFont(10) }

        binding.readerTimer.visibility = View.INVISIBLE

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
        lastActivityMs = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        lastActivityMs = SystemClock.elapsedRealtime()
        handler.postDelayed(ticker, 1_000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        viewModel.persistProgress()
    }

    override fun onStop() {
        super.onStop()
        viewModel.persistProgress()
    }

    // ---- Rendering ----

    private fun render(state: ReaderUiState) {
        state.book?.let { binding.readerTitle.text = it.title }
        binding.readerStatus.visibility = if (state.loading || state.error != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.readerStatus.text = state.error ?: getString(R.string.reader_opening)

        if (state.html.isNotEmpty()) {
            if (!htmlInjected) {
                htmlInjected = true
                lastTheme = state.theme
                lastFont = state.fontPercent
                binding.readerWeb.loadDataWithBaseURL(
                    null,
                    buildHtml(state.html, state.theme, state.fontPercent),
                    "text/html",
                    "utf-8",
                    null
                )
            } else if (state.theme != lastTheme || state.fontPercent != lastFont) {
                lastTheme = state.theme
                lastFont = state.fontPercent
                applyTypography(state.theme, state.fontPercent)
            }
        }

        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
        binding.readerProgress.progress = percent
        binding.readerPercent.text = getString(
            R.string.reader_position,
            percent,
            viewModel.pageAt(state.progress),
            viewModel.totalPages()
        )
        uiPrefs.edit()
            .putInt(KEY_THEME, state.theme.ordinal)
            .putInt(KEY_FONT, state.fontPercent)
            .apply()
    }

    private fun renderTimer() {
        val seconds = viewModel.state.value.activeSeconds
        binding.readerTimer.visibility = View.VISIBLE
        binding.readerTimer.text = String.format("%d:%02d", seconds / 60, seconds % 60)
    }

    private fun restorePosition() {
        val ratio = viewModel.state.value.progress.coerceIn(0.0, 1.0)
        binding.readerWeb.evaluateJavascript(
            "(function(){var m=document.documentElement.scrollHeight-window.innerHeight;" +
                "if(m>0){window.scrollTo(0,m*$ratio);}})();",
            null
        )
    }

    private fun applyTypography(theme: ReaderTheme, fontPercent: Int) {
        val palette = paletteOf(theme)
        val size = 16 * fontPercent / 100
        binding.readerWeb.evaluateJavascript(
            "(function(){var b=document.body;b.style.background='${palette.bg}';" +
                "b.style.color='${palette.fg}';b.style.fontSize='${size}px';})();",
            null
        )
    }

    // ---- HTML / CSS ----

    private data class Palette(val bg: String, val fg: String, val muted: String)

    private fun paletteOf(theme: ReaderTheme): Palette = when (theme) {
        ReaderTheme.PAPER -> Palette("#F7F3EA", "#22303A", "#7A8790")
        ReaderTheme.SEPIA -> Palette("#F0E2C4", "#3B2F1E", "#8A7A5C")
        ReaderTheme.NIGHT -> Palette("#14181C", "#C9D1D6", "#6E7A82")
    }

    private fun buildHtml(body: String, theme: ReaderTheme, fontPercent: Int): String {
        val palette = paletteOf(theme)
        val size = 16 * fontPercent / 100
        return """
            <!DOCTYPE html><html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
              html{-webkit-text-size-adjust:100%;}
              body{margin:0;padding:18px 18px 64px;line-height:1.55;
                   font-family:Georgia,'Times New Roman',serif;
                   background:${palette.bg};color:${palette.fg};font-size:${size}px;}
              h2{font-size:1.25em;margin:1.4em 0 0.6em;line-height:1.3;}
              h3{font-size:1.1em;margin:1.2em 0 0.5em;}
              p{margin:0 0 0.9em;text-align:justify;}
              .gap{height:0.7em;}
              a{color:${palette.muted};}
            </style></head>
            <body>$body
            <script>
            (function(){
              var last=0, ticking=false;
              function ratio(){
                var max=document.documentElement.scrollHeight-window.innerHeight;
                if(max<=0)return 1;
                return Math.min(1,Math.max(0,(window.scrollY||0)/max));
              }
              function report(){
                var r=ratio();
                if(r>last+0.0005){last=r;ReaderBridge.onProgress(r);}
                ReaderBridge.onActivity();
              }
              window.addEventListener('scroll',function(){
                if(ticking)return;ticking=true;
                setTimeout(function(){ticking=false;report();},200);
              },{passive:true});
              window.addEventListener('touchstart',function(){ReaderBridge.onActivity();},{passive:true});
            })();
            </script></body></html>
        """.trimIndent()
    }

    /** JS -> Kotlin: scroll position and any sign of life. */
    private inner class Bridge {
        @JavascriptInterface
        fun onProgress(ratio: Double) {
            runOnUiThread { viewModel.noteProgress(ratio) }
        }

        @JavascriptInterface
        fun onActivity() {
            lastActivityMs = SystemClock.elapsedRealtime()
        }
    }

    // ---- Session end ----

    private fun confirmExit() {
        viewModel.persistProgress()
        if (viewModel.isTooShort()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_exit_title)
            .setMessage(R.string.reader_exit_message)
            .setPositiveButton(R.string.reader_finish) { _, _ -> onFinishClicked() }
            .setNegativeButton(R.string.reader_stay, null)
            .show()
    }

    private fun onFinishClicked() {
        viewModel.persistProgress()
        val summary = viewModel.summary()
        if (viewModel.isTooShort()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.reader_too_short_title)
                .setMessage(R.string.reader_too_short)
                .setPositiveButton(R.string.reader_stay, null)
                .setNegativeButton(R.string.reader_exit) { _, _ -> finish() }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_summary_title)
            .setMessage(
                getString(R.string.reader_summary, summary.pagesRead, summary.minutesRead, viewModel.totalPages())
            )
            .setPositiveButton(R.string.reader_take_test) { _, _ -> startQuiz() }
            .setNegativeButton(R.string.reader_stay, null)
            .show()
    }

    private fun startQuiz() {
        val questions = viewModel.buildQuiz()
        if (questions.isEmpty()) {
            // Text too small to build an honest test: gate on real reading time only.
            lifecycleScope.launch { award(viewModel.grantWithoutQuiz(), 0) }
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (resources.displayMetrics.density * 20).toInt()
            setPadding(pad, pad, pad, pad)
        }
        questions.forEachIndexed { index, question ->
            container.addView(TextView(this).apply {
                text = question.question
                textSize = 15f
                setPadding(0, 0, 0, 8)
            })
            val group = RadioGroup(this).apply {
                tag = "reader_rg_$index"
                orientation = RadioGroup.VERTICAL
            }
            question.options.forEachIndexed { optionIndex, option ->
                group.addView(RadioButton(this).apply {
                    id = View.generateViewId()
                    text = option
                    tag = optionIndex
                    textSize = 14f
                })
            }
            container.addView(group)
        }
        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_quiz_title)
            .setView(scroll)
            .setPositiveButton(R.string.reader_check, null)
            .setNegativeButton(R.string.reader_stay, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val answers = questions.indices.map { index ->
                    val group = container.findViewWithTag<RadioGroup>("reader_rg_$index")
                    val checkedId = group?.checkedRadioButtonId ?: -1
                    val checked = if (checkedId != -1) group?.findViewById<RadioButton>(checkedId) else null
                    (checked?.tag as? Int) ?: -1
                }
                dialog.dismiss()
                lifecycleScope.launch { award(viewModel.gradeAndGrant(answers), questions.size) }
            }
        }
        dialog.show()
    }

    private fun award(outcome: QuizOutcome, questionCount: Int) {
        if (outcome.passed) {
            Toast.makeText(
                this,
                getString(R.string.reader_quiz_pass, outcome.correct, outcome.total, outcome.grantedMinutes),
                Toast.LENGTH_LONG
            ).show()
            finish()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.reader_quiz_title)
                .setMessage(getString(R.string.reader_quiz_fail, outcome.correct, questionCount))
                .setPositiveButton(R.string.reader_stay, null)
                .setNegativeButton(R.string.reader_exit) { _, _ -> finish() }
                .show()
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val KEY_THEME = "theme"
        private const val KEY_FONT = "font"

        /** Reading a full screen without touching it must not pause the timer. */
        private const val IDLE_TIMEOUT_MS = 180_000L
    }
}
