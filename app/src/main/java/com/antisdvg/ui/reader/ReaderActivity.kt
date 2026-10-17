package com.antisdvg.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.antisdvg.AntiSdvgApp
import com.antisdvg.R
import com.antisdvg.data.model.Bookmark
import com.antisdvg.data.model.QuizQuestion
import com.antisdvg.databinding.ActivityReaderBinding
import com.antisdvg.ui.AppViewModelFactory
import com.antisdvg.util.ReaderProgress
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The built-in reader.
 *
 * Two ways to read: [ReaderMode.PAGED] turns the text sideways a screen at a
 * time (columns + swipe/tap zones), [ReaderMode.SCROLL] is the endless ribbon.
 *
 * Honest counting: the timer only runs while this screen is resumed, the device
 * is awake and the reader is not idle (no scroll, flip or touch for a while).
 * Progress comes from the real position in the text, so pages cannot be claimed
 * without moving through it. On finish the user passes a light offline test
 * built from the passage just read, and only then minutes are awarded.
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

    /** Touched from the WebView JS-bridge thread, read from the main thread. */
    @Volatile
    private var lastActivityMs = 0L

    private var htmlInjected = false
    private var lastTheme: ReaderTheme? = null
    private var lastFont: ReaderFont? = null
    private var lastFontPercent = 0
    private var lastMode: ReaderMode? = null
    private var savedPrefsKey = ""
    private var attempts = 0

    /**
     * Test prepared the moment the session ends, so the summary announces the
     * real number of questions. The first attempt takes exactly this list; a
     * retry generates fresh questions.
     */
    private var preparedQuestions: List<QuizQuestion>? = null

    /** Postponed sessions: what an exit without taking the test leaves behind. */
    private val pendingStore by lazy { PendingSessionStore(this) }

    /** True while the test being graded belongs to a postponed session. */
    private var claiming = false

    /** True while a dialog owns the screen, so its seconds are not reading time. */
    private var modalOpen = false

    /** True while the progress slider is being dragged, so it is not fought over. */
    private var seeking = false

    /** True when the bars are hidden and only the text is on screen. */
    private var barsHidden = false

    /** Colors of the theme currently drawn, so the timer can dim itself when idle. */
    private var palette = paletteOf(ReaderTheme.PAPER)

    /** Active seconds since the last bookmark save; see [AUTOSAVE_SECONDS]. */
    private var secondsSinceSave = 0

    private val ticker = object : Runnable {
        override fun run() {
            // A dialog is not reading: while one owns the screen, the clock stops.
            val active = !modalOpen &&
                SystemClock.elapsedRealtime() - lastActivityMs < IDLE_TIMEOUT_MS
            if (active) {
                viewModel.onTick()
                // Autosave: a killed app must not cost the reader their place.
                if (++secondsSinceSave >= AUTOSAVE_SECONDS) {
                    secondsSinceSave = 0
                    viewModel.persistProgress()
                }
            }
            renderTimer(active)
            handler.postDelayed(this, 1_000L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Reading should not be interrupted by the screen timeout.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        viewModel.load(
            bookId = bookId,
            theme = prefTheme(),
            fontPercent = uiPrefs.getInt(KEY_FONT_SIZE, 100),
            font = prefFont(),
            mode = prefMode()
        )

        binding.readerWeb.settings.javaScriptEnabled = true
        binding.readerWeb.settings.textZoom = 100
        binding.readerWeb.addJavascriptInterface(Bridge(), "ReaderBridge")
        binding.readerWeb.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                restorePositionSoon()
            }
        }
        binding.readerWeb.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) lastActivityMs = SystemClock.elapsedRealtime()
            false
        }

        binding.readerClose.setOnClickListener { confirmExit() }
        binding.readerFinish.setOnClickListener { onFinishClicked() }
        binding.readerMenu.setOnClickListener { showReaderMenu() }
        binding.readerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(bar: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                // Jump on release only: dragging across 900 pages would otherwise
                // re-lay the book out hundreds of times.
                seeking = false
                lastActivityMs = SystemClock.elapsedRealtime()
                evaluate("Reader.goToRatio(${(bar?.progress ?: 0) / 1000.0})")
            }
        })
        binding.readerTheme.setOnClickListener {
            viewModel.cycleTheme()
            toast(themeLabel(viewModel.state.value.theme))
        }
        binding.readerFont.setOnClickListener {
            viewModel.cycleFont()
            toast(fontLabel(viewModel.state.value.font))
        }
        binding.readerMode.setOnClickListener {
            viewModel.cycleMode()
            toast(modeLabel(viewModel.state.value.mode))
        }
        binding.readerFontMinus.setOnClickListener { viewModel.adjustFont(-10) }
        binding.readerFontPlus.setOnClickListener { viewModel.adjustFont(10) }

        binding.readerTimer.visibility = View.INVISIBLE
        applyBrightness()

        // The system back gesture means the same as ✕: keep the position, offer
        // to close the session properly instead of silently dropping it.
        onBackPressedDispatcher.addCallback(this) { confirmExit() }

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

    // ---- Preferences ----

    private fun prefTheme(): ReaderTheme =
        ReaderTheme.entries.getOrElse(uiPrefs.getInt(KEY_THEME, 0)) { ReaderTheme.PAPER }

    private fun prefFont(): ReaderFont =
        ReaderFont.entries.getOrElse(uiPrefs.getInt(KEY_FONT_FAMILY, 0)) { ReaderFont.SERIF }

    private fun prefMode(): ReaderMode =
        ReaderMode.entries.getOrElse(uiPrefs.getInt(KEY_MODE, 0)) { ReaderMode.PAGED }

    // ---- Rendering ----

    private fun render(state: ReaderUiState) {
        state.book?.let { binding.readerTitle.text = it.title }
        binding.readerStatus.visibility = if (state.loading || state.error != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.readerStatus.text = state.error ?: getString(R.string.reader_opening)
        binding.readerFinish.isEnabled = state.error == null && !state.loading

        if (state.html.isNotEmpty()) {
            if (!htmlInjected) {
                htmlInjected = true
                rememberStyle(state)
                applyChrome(state.theme)
                binding.readerWeb.loadDataWithBaseURL(
                    null,
                    buildHtml(state),
                    "text/html",
                    "utf-8",
                    null
                )
                showPagingHintOnce(state.mode)
                maybeOfferPending()
            } else {
                applyStyleChanges(state)
            }
        }

        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
        if (!seeking) binding.readerSeek.progress = percent * 10
        binding.readerPercent.text = if (state.chapter.isBlank()) {
            getString(
                R.string.reader_position,
                percent,
                viewModel.currentPage(),
                viewModel.totalPages()
            )
        } else {
            getString(
                R.string.reader_position_chapter,
                state.chapter,
                percent,
                viewModel.currentPage(),
                viewModel.totalPages()
            )
        }

        // Preferences change rarely — never rewrite them once per timer tick.
        val key = "${state.theme.ordinal}|${state.fontPercent}|${state.font.ordinal}|${state.mode.ordinal}"
        if (key != savedPrefsKey) {
            savedPrefsKey = key
            uiPrefs.edit()
                .putInt(KEY_THEME, state.theme.ordinal)
                .putInt(KEY_FONT_SIZE, state.fontPercent)
                .putInt(KEY_FONT_FAMILY, state.font.ordinal)
                .putInt(KEY_MODE, state.mode.ordinal)
                .apply()
        }
    }

    private fun rememberStyle(state: ReaderUiState) {
        lastTheme = state.theme
        lastFont = state.font
        lastFontPercent = state.fontPercent
        lastMode = state.mode
    }

    /** Pushes theme/font/mode changes into the live page without reloading it. */
    private fun applyStyleChanges(state: ReaderUiState) {
        val themeChanged = state.theme != lastTheme
        val fontChanged = state.font != lastFont || state.fontPercent != lastFontPercent
        val modeChanged = state.mode != lastMode
        if (!themeChanged && !fontChanged && !modeChanged) return

        rememberStyle(state)
        if (themeChanged) {
            applyChrome(state.theme)
            val palette = paletteOf(state.theme)
            evaluate("Reader.setTheme('${palette.bg}','${palette.fg}')")
        }
        if (fontChanged) {
            evaluate("Reader.setTypography('${state.font.name}', ${16 * state.fontPercent / 100})")
        }
        if (modeChanged) {
            evaluate("Reader.setMode('${state.mode.name}')")
        }
        // Reflowing can push the text around: put the bookmark back.
        handler.postDelayed({ restorePosition() }, 80L)
    }

    private fun evaluate(js: String) {
        binding.readerWeb.evaluateJavascript(js, null)
    }

    /**
     * Tap zones are invisible, so the first paged session says it once. Nobody
     * discovers "tap the right edge" by staring at a book.
     */
    private fun showPagingHintOnce(mode: ReaderMode) {
        if (mode != ReaderMode.PAGED) return
        if (uiPrefs.getBoolean(KEY_PAGING_HINT, false)) return
        uiPrefs.edit().putBoolean(KEY_PAGING_HINT, true).apply()
        handler.postDelayed({ toast(getString(R.string.reader_hint_paged)) }, 1_500L)
    }

    /**
     * The timer is the whole point of the screen, so it must be obvious when it
     * is counting and when it has stopped: a frozen clock looks like a broken
     * counter, and reading on a dense page for three minutes without touching
     * the screen does stop it.
     */
    private fun renderTimer(active: Boolean) {
        val seconds = viewModel.state.value.activeSeconds
        val clock = String.format("%d:%02d", seconds / 60, seconds % 60)
        binding.readerTimer.visibility = View.VISIBLE
        binding.readerTimer.text =
            if (active) clock else getString(R.string.reader_timer_paused, clock)
        binding.readerTimer.setTextColor(
            if (active) ContextCompat.getColor(this, R.color.accent_primary_dark)
            else Color.parseColor(palette.muted)
        )
    }

    /**
     * The bookmark is restored twice: at load time and a moment later, because
     * late layout (long chapters, web fonts) can still shift the text.
     */
    private fun restorePositionSoon() {
        restorePosition()
        handler.postDelayed({ restorePosition() }, 350L)
        handler.postDelayed({ restorePosition() }, 900L)
    }

    private fun restorePosition() {
        if (viewModel.state.value.mode == ReaderMode.PAGED) {
            evaluate("Reader.restore()")
        } else {
            val ratio = viewModel.state.value.progress.coerceIn(0.0, 1.0)
            evaluate(
                "(function(){var m=document.documentElement.scrollHeight-window.innerHeight;" +
                    "if(m>0){window.scrollTo(0,m*$ratio);}})();"
            )
        }
    }

    /**
     * The reader is the only screen the user stares at for an hour: in the dark
     * themes the surrounding chrome must go dark with the page, not stay white.
     */
    private fun applyChrome(theme: ReaderTheme) {
        palette = paletteOf(theme)
        val bg = Color.parseColor(palette.bg)
        val fg = Color.parseColor(palette.fg)
        val muted = Color.parseColor(palette.muted)
        binding.readerRoot.setBackgroundColor(bg)
        binding.readerBar.setBackgroundColor(bg)
        binding.readerBottom.setBackgroundColor(bg)
        binding.readerWeb.setBackgroundColor(bg)
        binding.readerTitle.setTextColor(fg)
        binding.readerPercent.setTextColor(muted)
        binding.readerTheme.setTextColor(muted)
        binding.readerFont.setTextColor(muted)
        binding.readerMode.setTextColor(muted)
        binding.readerFontMinus.setTextColor(muted)
        binding.readerFontPlus.setTextColor(muted)
        binding.readerClose.setTextColor(muted)
        binding.readerStatus.setTextColor(muted)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val light = theme == ReaderTheme.PAPER || theme == ReaderTheme.SEPIA
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun themeLabel(theme: ReaderTheme): String = getString(
        when (theme) {
            ReaderTheme.PAPER -> R.string.reader_theme_paper
            ReaderTheme.SEPIA -> R.string.reader_theme_sepia
            ReaderTheme.NIGHT -> R.string.reader_theme_night
            ReaderTheme.BLACK -> R.string.reader_theme_black
        }
    )

    private fun fontLabel(font: ReaderFont): String = getString(
        when (font) {
            ReaderFont.SERIF -> R.string.reader_font_serif
            ReaderFont.SANS -> R.string.reader_font_sans
            ReaderFont.MONO -> R.string.reader_font_mono
        }
    )

    private fun modeLabel(mode: ReaderMode): String = getString(
        when (mode) {
            ReaderMode.PAGED -> R.string.reader_mode_paged
            ReaderMode.SCROLL -> R.string.reader_mode_scroll
        }
    )

    // ---- Page: styles, paging script ----

    private data class Palette(val bg: String, val fg: String, val muted: String)

    private fun paletteOf(theme: ReaderTheme): Palette = when (theme) {
        ReaderTheme.PAPER -> Palette("#F7F3EA", "#22303A", "#7A8790")
        ReaderTheme.SEPIA -> Palette("#F0E2C4", "#3B2F1E", "#8A7A5C")
        ReaderTheme.NIGHT -> Palette("#14181C", "#C9D1D6", "#6E7A82")
        ReaderTheme.BLACK -> Palette("#000000", "#E8E8E8", "#8A8A8A")
    }

    /**
     * One page for both reading modes.
     *
     * Paged mode lays the text out as screen-wide columns and moves them with a
     * transform — Chromium's own scrolling is switched off so a swipe always
     * means "next page" and never a half-scrolled screen.
     */
    private fun buildHtml(state: ReaderUiState): String {
        val palette = paletteOf(state.theme)
        val size = 16 * state.fontPercent / 100
        val startClass = if (state.mode == ReaderMode.PAGED) "paged" else "scroll"
        val initial = state.progress.coerceIn(0.0, 1.0)
        return """
            <!DOCTYPE html><html lang="ru"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
              html,body{margin:0;padding:0;background:${palette.bg};}
              body{color:${palette.fg};font-family:${state.font.css};font-size:${size}px;
                   line-height:1.55;-webkit-text-size-adjust:100%;word-wrap:break-word;}
              #vp{padding:18px 18px 30px;}
              body.paged{overflow:hidden;}
              /* Margin, not padding: the clip must end exactly where the page does,
                 or the next column peeks in through the padding. */
              body.paged #vp{position:fixed;left:18px;top:18px;right:18px;bottom:30px;
                             padding:0;overflow:hidden;}
              body.paged #content{column-gap:0;column-fill:auto;will-change:transform;}
              h2{font-size:1.3em;margin:1.4em 0 0.6em;line-height:1.25;letter-spacing:-0.01em;}
              h3{font-size:1.12em;margin:1.2em 0 0.5em;}
              h2:first-child,h3:first-child{margin-top:0;}
              body.paged h2,body.paged h3{break-after:avoid;}
              /* Justified Russian needs hyphenation, or the word gaps turn into rivers. */
              p{margin:0 0 0.9em;text-align:justify;orphans:2;widows:2;
                -webkit-hyphens:auto;hyphens:auto;}
              .gap{height:0.7em;}
              /* Search hits: highlighted through the CSS highlight registry, so the
                 DOM (and therefore the page numbers) never move. */
              ::highlight(search){background-color:rgba(255,198,40,0.45);}
            </style></head>
            <body class="$startClass">
            <div id="vp"><div id="content">${state.html}</div></div>
            <script>
            (function () {
              var FONTS = {
                SERIF: "${ReaderFont.SERIF.css}",
                SANS: "${ReaderFont.SANS.css}",
                MONO: "${ReaderFont.MONO.css}"
              };
              var INITIAL = $initial;
              var vp = document.getElementById('vp');
              var c = document.getElementById('content');
              var paged = document.body.className.indexOf('paged') >= 0;
              var pageW = 1, total = 1, cur = 0;

              function clamp(v, lo, hi) { return Math.min(hi, Math.max(lo, v)); }
              // First page is 0%, last page is 100%: opening a book must never
              // look like a chunk of it has already been read.
              function ratio() { return total <= 1 ? 1 : clamp(cur / (total - 1), 0, 1); }
              function pageFor(r) { return clamp(Math.round(r * (total - 1)), 0, total - 1); }
              function scrollRatio() {
                var m = document.documentElement.scrollHeight - window.innerHeight;
                return m > 0 ? clamp((window.scrollY || 0) / m, 0, 1) : 0;
              }

              // Right edge of the last laid-out block tells how many columns the
              // text really fills, whatever the engine reports for scrollWidth.
              function usedWidth() {
                var base = c.getBoundingClientRect().left;
                var kids = c.children;
                for (var i = kids.length - 1; i >= 0; i--) {
                  var r = kids[i].getBoundingClientRect();
                  if (r.width > 0) { return r.right - base; }
                }
                return c.scrollWidth;
              }

              function relax() {
                var w = Math.max(80, vp.clientWidth);
                var h = Math.max(80, vp.clientHeight);
                pageW = w;
                c.style.columnWidth = w + 'px';
                c.style.height = h + 'px';
                c.style.width = w + 'px';
                var used = usedWidth();
                if (used <= w * 1.05 && c.scrollHeight > h + 8) {
                  // Some engines keep everything in one column and clip it
                  // vertically: then give the container room for every column
                  // the text needs and measure again.
                  var need = Math.ceil(c.scrollHeight / h) + 2;
                  c.style.width = (w * need) + 'px';
                  used = usedWidth();
                }
                total = Math.max(1, Math.ceil(used / w));
                buildToc();
                ReaderBridge.debug('pageW=' + w + ' pageH=' + h + ' used=' + Math.round(used) +
                  ' total=' + total + ' inner=' + window.innerWidth + 'x' + window.innerHeight +
                  ' dpr=' + window.devicePixelRatio);
              }

              function place(animate) {
                c.style.transition = animate ? 'transform 0.18s ease-out' : 'none';
                c.style.transform = 'translateX(' + (-cur * pageW) + 'px)';
              }

              // The honest record of the session: which screens were really put
              // in front of the reader. A set, not a counter — flipping back and
              // forth must never count one screen twice.
              var visited = {}, pMin = INITIAL, pMax = INITIAL;

              function markVisited() {
                if (!paged) { return; }
                visited[cur] = true;
                var r = ratio();
                if (r < pMin) { pMin = r; }
                if (r > pMax) { pMax = r; }
              }

              // Changing font, size or mode re-lays the text out, so the old page
              // numbers mean nothing. What survives is the span of progress seen
              // so far; every page inside it counts as read on the new layout.
              function resyncVisited() {
                visited = {};
                var a = pageFor(pMin), b = pageFor(pMax);
                for (var i = a; i <= b; i++) { visited[i] = true; }
                markVisited();
              }

              // Text of the visited screens, in reading order. A block starting
              // inside page N belongs to it, so a paragraph spanning a page break
              // is read once. Blocks are joined with a blank line, which keeps a
              // sentence from ever being stitched together across two pages.
              function excerptText() {
                var base = c.getBoundingClientRect().left;
                var out = [];
                var kids = c.children;
                for (var i = 0; i < kids.length; i++) {
                  var k = kids[i];
                  var r = k.getBoundingClientRect();
                  if (r.width <= 0 && r.height <= 0) { continue; }
                  var col = clamp(Math.floor((r.left - base) / pageW + 0.001), 0, total - 1);
                  if (visited[col]) {
                    var t = (k.textContent || '').replace(/\s+/g, ' ').trim();
                    if (t) { out.push(t); }
                  }
                }
                return out.join('\n\n');
              }

              function report() {
                markVisited();
                ReaderBridge.onProgress(ratio());
                ReaderBridge.onPage(cur + 1, total);
                ReaderBridge.onChapter(chapterName());
              }

              // ---- Navigation ----

              // Chapters as the markup describes them: FB2 titles become h2/h3,
              // plain text gets them guessed at import. One pass over the blocks,
              // so the table of contents costs nothing later.
              var tocEls = [], tocItems = [];
              function buildToc() {
                tocEls = [];
                tocItems = [];
                var base = c.getBoundingClientRect().left;
                var kids = c.children;
                for (var i = 0; i < kids.length; i++) {
                  var k = kids[i];
                  var tag = (k.tagName || '').toUpperCase();
                  if (tag !== 'H1' && tag !== 'H2' && tag !== 'H3') { continue; }
                  var title = (k.textContent || '').replace(/\s+/g, ' ').trim();
                  if (!title) { continue; }
                  var r = k.getBoundingClientRect();
                  var col = pageW > 0 ? Math.floor((r.left - base) / pageW + 0.001) : 0;
                  tocEls.push(k);
                  tocItems.push({ title: title, page: clamp(col, 0, total - 1) + 1,
                                  level: tag === 'H1' ? 1 : (tag === 'H2' ? 2 : 3) });
                }
              }

              // Where in the book the reader currently is: the last heading above
              // the current screen. Empty in ribbon mode, which has no columns.
              function chapterName() {
                if (!paged || !tocItems.length) { return ''; }
                var name = '';
                for (var i = 0; i < tocItems.length; i++) {
                  if (tocItems[i].page - 1 <= cur) { name = tocItems[i].title; } else { break; }
                }
                return name;
              }

              function goToPage(page) {
                if (!paged) { return; }
                cur = clamp(Math.round(page) - 1, 0, total - 1);
                place(true);
                report();
              }

              function goToHeading(index) {
                var el = tocEls[index];
                if (!el) { return; }
                if (paged) {
                  goToPage(tocItems[index].page);
                } else {
                  el.scrollIntoView();
                }
              }

              function step(dir) {
                if (paged) { go(cur + dir); } else { window.scrollBy(0, dir * window.innerHeight * 0.9); }
              }

              // ---- Search ----

              // One pass over the blocks, on demand: a big book is millions of
              // characters, but the blocks are already in memory.
              function searchText(query, limit) {
                var needle = (query || '').trim().toLowerCase();
                if (needle.length < 2) { return []; }
                var base = c.getBoundingClientRect().left;
                var out = [];
                var kids = c.children;
                for (var i = 0; i < kids.length && out.length < limit; i++) {
                  var k = kids[i];
                  var raw = (k.textContent || '').replace(/\s+/g, ' ').trim();
                  if (!raw) { continue; }
                  var at = raw.toLowerCase().indexOf(needle);
                  if (at < 0) { continue; }
                  var r = k.getBoundingClientRect();
                  var col = pageW > 0 ? Math.floor((r.left - base) / pageW + 0.001) : 0;
                  var from = Math.max(0, at - 40);
                  var to = Math.min(raw.length, at + needle.length + 40);
                  out.push({
                    page: clamp(col, 0, total - 1) + 1,
                    index: i,
                    snippet: (from > 0 ? '… ' : '') + raw.substring(from, to).trim() +
                             (to < raw.length ? ' …' : '')
                  });
                }
                return out;
              }

              // Marking the hit without touching the DOM: a <mark> would re-flow
              // the columns and move every page number in the app.
              function highlightBlock(index) {
                if (!window.CSS || !CSS.highlights || !window.Highlight) { return; }
                var k = c.children[index];
                if (!k) { return; }
                try {
                  var range = document.createRange();
                  range.selectNodeContents(k);
                  CSS.highlights.clear();
                  CSS.highlights.set('search', new Highlight(range));
                } catch (e) { /* older engine: the jump still works */ }
              }

              function goToHit(page, index) {
                if (paged) { goToPage(page); } else { var k = c.children[index]; if (k) { k.scrollIntoView(); } }
                highlightBlock(index);
              }

              function goToRatio(r) {
                var value = clamp(r, 0, 1);
                if (paged) {
                  goToPage(pageFor(value) + 1);
                } else {
                  var m = document.documentElement.scrollHeight - window.innerHeight;
                  window.scrollTo(0, m * value);
                }
              }

              function go(page) {
                if (!paged) { return; }
                cur = clamp(page, 0, total - 1);
                place(true);
                report();
              }

              // Re-measure the columns; keepRatio=false means "start where the
              // book was opened", which is only right on the very first layout.
              function reflow(keepRatio) {
                if (!paged) { return; }
                var r = keepRatio ? ratio() : INITIAL;
                relax();
                cur = pageFor(r);
                place(false);
                resyncVisited();
                report();
              }

              // Swipe sideways and tap the edges, the way a paper book turns.
              var sx = 0, sy = 0, st = 0;
              vp.addEventListener('touchstart', function (e) {
                if (!paged) { return; }
                var t = e.changedTouches[0];
                sx = t.clientX; sy = t.clientY; st = Date.now();
              }, { passive: true });
              vp.addEventListener('touchend', function (e) {
                if (!paged) { return; }
                var t = e.changedTouches[0];
                var dx = t.clientX - sx, dy = t.clientY - sy;
                if (Math.abs(dx) > 36 && Math.abs(dx) > Math.abs(dy)) {
                  go(dx < 0 ? cur + 1 : cur - 1);
                } else if (Math.abs(dx) < 14 && Math.abs(dy) < 14 && Date.now() - st < 500) {
                  var x = t.clientX, w = vp.clientWidth;
                  if (x < w * 0.3) { go(cur - 1); }
                  else if (x > w * 0.7) { go(cur + 1); }
                  // The middle of the page is not a page turn: it shows or hides
                  // the panels, the way a tap in the middle of a reader should.
                  else { ReaderBridge.onToggleBars(); }
                }
              }, { passive: true });

              // Progress while scrolling the endless ribbon.
              var ticking = false, last = 0;
              window.addEventListener('scroll', function () {
                if (paged || ticking) { return; }
                ticking = true;
                setTimeout(function () {
                  ticking = false;
                  var r = scrollRatio();
                  if (r > last + 0.0005) { last = r; ReaderBridge.onProgress(r); }
                }, 200);
              }, { passive: true });

              window.addEventListener('resize', function () { if (paged) { reflow(true); } });

              window.Reader = {
                setTheme: function (bg, fg) {
                  document.documentElement.style.background = bg;
                  vp.style.background = bg;
                  c.style.background = bg;
                  vp.style.color = fg;
                  c.style.color = fg;
                },
                setTypography: function (name, sizePx) {
                  var css = FONTS[name] || FONTS.SERIF;
                  var was = paged ? ratio() : scrollRatio();
                  vp.style.fontFamily = css;
                  c.style.fontFamily = css;
                  vp.style.fontSize = sizePx + 'px';
                  c.style.fontSize = sizePx + 'px';
                  if (paged) {
                    reflow(true);
                  } else {
                    // The ribbon re-flows too: stay on the same line of the book.
                    requestAnimationFrame(function () {
                      var m = document.documentElement.scrollHeight - window.innerHeight;
                      if (m > 0) { window.scrollTo(0, m * was); }
                    });
                  }
                },
                setMode: function (name) {
                  var was = paged ? ratio() : scrollRatio();
                  paged = (name === 'PAGED');
                  document.body.className = paged ? 'paged' : 'scroll';
                  if (paged) {
                    relax();
                    cur = pageFor(was);
                    place(false);
                    resyncVisited();
                    report();
                  } else {
                    c.style.transition = 'none';
                    c.style.transform = 'none';
                    c.style.height = '';
                    c.style.width = '';
                    c.style.columnWidth = '';
                    cur = 0;
                    // The ribbon has no columns, but still has headings to jump to.
                    buildToc();
                    // The ribbon has no pages of its own: fall back to the
                    // character estimate for the page label.
                    ReaderBridge.onPage(0, 0);
                    requestAnimationFrame(function () {
                      var m = document.documentElement.scrollHeight - window.innerHeight;
                      if (m > 0) { window.scrollTo(0, m * was); }
                    });
                  }
                },
                restore: function () { if (paged) { reflow(true); } },
                // Navigation for the app's own panels: chapter list, page jump,
                // slider, volume keys.
                toc: function () { return tocItems; },
                goToPage: goToPage,
                goToHeading: goToHeading,
                goToRatio: goToRatio,
                step: step,
                search: function (query) { return searchText(query, 50); },
                goToHit: goToHit,
                // What the session should be judged on: the screens really shown
                // and their text. Null in ribbon mode, where the reader has no
                // pages of its own and Kotlin falls back to the progress range.
                excerpt: function () {
                  if (!paged) { return null; }
                  var lo = total, hi = 0, n = 0;
                  for (var k in visited) {
                    if (visited[k]) {
                      var i = parseInt(k, 10);
                      n++;
                      if (i < lo) { lo = i; }
                      if (i > hi) { hi = i; }
                    }
                  }
                  if (n === 0) { return null; }
                  return { text: excerptText(), fromPage: lo + 1, toPage: hi + 1, pages: n };
                },
                state: function () {
                  return { paged: paged, total: total, cur: cur, pageW: pageW, ratio: ratio() };
                }
              };

              if (paged) {
                reflow(false);
              } else {
                // Headings still exist in the ribbon: build the table of contents.
                buildToc();
                requestAnimationFrame(function () {
                  var m = document.documentElement.scrollHeight - window.innerHeight;
                  if (m > 0) { window.scrollTo(0, m * INITIAL); }
                });
              }
            })();
            </script></body></html>
        """.trimIndent()
    }

    /** JS -> Kotlin: position, real page numbers and any sign of life. */
    private inner class Bridge {
        @JavascriptInterface
        fun onProgress(ratio: Double) {
            runOnUiThread { viewModel.noteProgress(ratio) }
        }

        @JavascriptInterface
        fun onPage(page: Int, pages: Int) {
            runOnUiThread { viewModel.notePage(page, pages) }
        }

        @JavascriptInterface
        fun onChapter(name: String) {
            runOnUiThread { viewModel.noteChapter(name) }
        }

        @JavascriptInterface
        fun onToggleBars() {
            runOnUiThread { toggleBars() }
        }

        @JavascriptInterface
        fun onActivity() {
            lastActivityMs = SystemClock.elapsedRealtime()
        }

        /** Layout numbers, so a bad page split can be diagnosed from the log. */
        @JavascriptInterface
        fun debug(message: String) {
            Log.d(TAG, message)
        }
    }

    // ---- Navigation, bookmarks, comfort ----

    /**
     * Everything that is not reading, in one panel: how bright the page is, where
     * to jump, what to remember. Splitting these into separate buttons would not
     * fit the top bar, and none of them is used often enough to deserve one.
     */
    private fun showReaderMenu() {
        val density = resources.displayMetrics.density
        val pad = (density * 20).toInt()
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        val current = brightness()
        val brightnessLabel = TextView(this).apply {
            text = brightnessLabel(current.takeIf { it > 0f } ?: systemBrightness())
            textSize = 14f
        }
        val slider = SeekBar(this).apply {
            max = 100
            progress = ((current.takeIf { it > 0f } ?: systemBrightness()) * 100).toInt().coerceIn(5, 100)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                setBrightness(value / 100f)
                brightnessLabel.text = brightnessLabel(value / 100f)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        rows.addView(brightnessLabel)
        rows.addView(slider)
        rows.addView(menuRow(R.string.reader_contents) { showContents() })
        rows.addView(menuRow(R.string.reader_search) { showSearch() })
        rows.addView(menuRow(R.string.reader_jump) { showJumpDialog() })
        rows.addView(menuRow(R.string.reader_bookmark_add) { addBookmark() })
        rows.addView(menuRow(R.string.reader_bookmarks) { showBookmarks() })
        rows.addView(menuRow(R.string.reader_clean_screen) { toggleBars() })

        AlertDialog.Builder(this)
            .setTitle(R.string.reader_menu)
            .setView(rows)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun menuRow(textRes: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = getString(textRes)
        textSize = 16f
        val pad = (resources.displayMetrics.density * 12).toInt()
        setPadding(0, pad, 0, pad)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** The table of contents the page itself built from the book's headings. */
    private fun showContents() {
        binding.readerWeb.evaluateJavascript("Reader.toc()") { raw ->
            val items = parseToc(raw)
            if (items.isEmpty()) {
                infoDialog(getString(R.string.reader_contents), getString(R.string.reader_contents_empty))
                return@evaluateJavascript
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.reader_contents)
                .setItems(items.map { it.title }.toTypedArray()) { _, which -> jumpToItem(items[which]) }
                .setNegativeButton(R.string.reader_stay, null)
                .show()
        }
    }

    private data class TocItem(val title: String, val page: Int, val index: Int)

    private fun parseToc(raw: String?): List<TocItem> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        val json = runCatching {
            val text = if (raw.startsWith("\"")) JSONTokener(raw).nextValue() as String else raw
            JSONArray(text)
        }.getOrNull() ?: return emptyList()
        return (0 until json.length()).mapNotNull { i ->
            val item = json.optJSONObject(i) ?: return@mapNotNull null
            val title = item.optString("title")
            if (title.isBlank()) null else TocItem(title, item.optInt("page", 1), i)
        }
    }

    private fun jumpToItem(item: TocItem) {
        // Paged mode jumps by page number, the ribbon by scrolling the heading in.
        if (viewModel.state.value.mode == ReaderMode.PAGED) {
            evaluate("Reader.goToPage(${item.page})")
        } else {
            evaluate("Reader.goToHeading(${item.index})")
        }
        lastActivityMs = SystemClock.elapsedRealtime()
    }

    /** Finds a phrase in the book and takes the reader to it. */
    private fun showSearch() {
        val input = EditText(this).apply {
            hint = getString(R.string.reader_search_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val wrapper = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_search)
            .setView(wrapper)
            .setPositiveButton(R.string.reader_search_go) { _, _ -> runSearch(input.text.toString()) }
            .setNegativeButton(R.string.reader_stay, null)
            .show()
    }

    private fun runSearch(query: String) {
        if (query.trim().length < 2) return
        binding.readerWeb.evaluateJavascript(
            "JSON.stringify(Reader.search(${JSONObject.quote(query)}))"
        ) { raw ->
            val hits = parseHits(raw)
            if (hits.isEmpty()) {
                infoDialog(
                    getString(R.string.reader_search),
                    getString(R.string.reader_search_none, query.trim())
                )
                return@evaluateJavascript
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.reader_search_results, hits.size))
                .setItems(hits.map { getString(R.string.reader_search_hit, it.snippet, it.page) }.toTypedArray()) { _, which ->
                    val hit = hits[which]
                    evaluate("Reader.goToHit(${hit.page}, ${hit.index})")
                    lastActivityMs = SystemClock.elapsedRealtime()
                }
                .setNegativeButton(R.string.reader_stay, null)
                .show()
        }
    }

    private data class SearchHit(val page: Int, val snippet: String, val index: Int)

    private fun parseHits(raw: String?): List<SearchHit> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        val json = runCatching {
            val text = if (raw.startsWith("\"")) JSONTokener(raw).nextValue() as String else raw
            JSONArray(text)
        }.getOrNull() ?: return emptyList()
        return (0 until json.length()).mapNotNull { i ->
            val item = json.optJSONObject(i) ?: return@mapNotNull null
            val snippet = item.optString("snippet")
            if (snippet.isBlank()) null else SearchHit(item.optInt("page", 1), snippet, item.optInt("index", -1))
        }
    }

    private fun showJumpDialog() {
        val total = viewModel.totalPages()
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.reader_jump_title, total)
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val wrapper = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_jump)
            .setView(wrapper)
            .setPositiveButton(R.string.reader_jump_go) { _, _ ->
                val page = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                if (viewModel.state.value.mode == ReaderMode.PAGED) {
                    evaluate("Reader.goToPage(${page.coerceIn(1, total)})")
                } else {
                    val ratio = (page - 1).coerceAtLeast(0).toDouble() / (total - 1).coerceAtLeast(1)
                    evaluate("Reader.goToRatio(${ratio.coerceIn(0.0, 1.0)})")
                }
                lastActivityMs = SystemClock.elapsedRealtime()
            }
            .setNegativeButton(R.string.reader_stay, null)
            .show()
    }

    /** Remembers this place. The page number is for the label, progress for the jump. */
    private fun addBookmark() {
        val state = viewModel.state.value
        val book = state.book ?: return
        val page = viewModel.currentPage()
        lifecycleScope.launch {
            repo.addBookmark(book.id, state.progress, page)
            toast(getString(R.string.reader_bookmark_added, page))
        }
    }

    private fun showBookmarks() {
        val book = viewModel.state.value.book ?: return
        lifecycleScope.launch {
            val marks = repo.bookmarks(book.id)
            if (marks.isEmpty()) {
                infoDialog(getString(R.string.reader_bookmarks), getString(R.string.reader_bookmarks_empty))
                return@launch
            }
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle(R.string.reader_bookmarks)
                .setItems(marks.map { getString(R.string.reader_bookmark_goto, it.page) }.toTypedArray()) { _, which ->
                    val mark = marks[which]
                    evaluate("Reader.goToRatio(${mark.progress})")
                    lastActivityMs = SystemClock.elapsedRealtime()
                }
                .setNeutralButton(R.string.reader_bookmark_delete) { _, _ -> showDeleteBookmark(marks) }
                .setNegativeButton(R.string.reader_stay, null)
                .show()
        }
    }

    private fun showDeleteBookmark(marks: List<Bookmark>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_bookmark_delete)
            .setItems(marks.map { getString(R.string.reader_bookmark_goto, it.page) }.toTypedArray()) { _, which ->
                val mark = marks[which]
                lifecycleScope.launch {
                    repo.deleteBookmark(mark.id)
                    toast(getString(R.string.reader_bookmark_deleted, mark.page))
                }
            }
            .setNegativeButton(R.string.reader_stay, null)
            .show()
    }

    private fun infoDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun brightness(): Float = uiPrefs.getFloat(KEY_BRIGHTNESS, -1f)

    private fun systemBrightness(): Float =
        android.provider.Settings.System
            .getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f

    private fun setBrightness(value: Float) {
        val level = value.coerceIn(0.05f, 1f)
        uiPrefs.edit().putFloat(KEY_BRIGHTNESS, level).apply()
        window.attributes = window.attributes.apply { screenBrightness = level }
    }

    /** Puts back the brightness the reader was left at; system one stays system. */
    private fun applyBrightness() {
        val level = brightness()
        if (level > 0f) window.attributes = window.attributes.apply { screenBrightness = level }
    }

    private fun brightnessLabel(value: Float): String =
        if (value <= 0f) getString(R.string.reader_brightness_system)
        else getString(R.string.reader_brightness_label, (value * 100).toInt())

    /** Text and nothing else: the panels come back on the next tap in the middle. */
    private fun toggleBars() {
        barsHidden = !barsHidden
        val visibility = if (barsHidden) View.GONE else View.VISIBLE
        binding.readerBar.visibility = visibility
        binding.readerSeek.visibility = visibility
        binding.readerBottom.visibility = visibility
    }

    /**
     * Volume keys turn pages, the way every e-reader does: one hand, phone in a
     * fist, thumb on the volume rocker. Down is forward, up is back.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !modalOpen) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    evaluate("Reader.step(1)")
                    lastActivityMs = SystemClock.elapsedRealtime()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    evaluate("Reader.step(-1)")
                    lastActivityMs = SystemClock.elapsedRealtime()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Session end ----

    /**
     * The corner ✕ means what it looks like: leave. Minutes are earned by the
     * test, so the dialog says how many are on the table right now — walking out
     * should never quietly cost an hour of reading.
     */
    private fun confirmExit() {
        viewModel.persistProgress()
        val message = if (viewModel.isTooShort()) {
            getString(R.string.reader_exit_short)
        } else {
            getString(R.string.reader_exit_message, viewModel.expectedMinutes())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_exit_title)
            .setMessage(message)
            .setPositiveButton(R.string.reader_stay, null)
            .setNegativeButton(R.string.reader_exit) { _, _ -> exitWithoutMinutes() }
            .show()
    }

    private fun onFinishClicked() {
        viewModel.persistProgress()
        if (viewModel.isTooShort()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.reader_too_short_title)
                .setMessage(R.string.reader_too_short)
                .setPositiveButton(R.string.reader_stay, null)
                .setNegativeButton(R.string.reader_exit) { _, _ -> exitWithoutMinutes() }
                .show()
            return
        }
        // The layout is the only thing that knows which screens were actually
        // shown, so its report is taken before the session is frozen. Otherwise
        // the summary and the test would describe a guess.
        pullExcerpt { excerpt ->
            if (excerpt == null) {
                // Ribbon mode, or a page that is not ready: describe the session
                // by progress, and never reuse an older report.
                viewModel.clearExcerpt()
            } else {
                viewModel.noteExcerpt(excerpt)
            }
            viewModel.freezeSession()
            preparedQuestions = viewModel.buildQuiz()
            showSummary(preparedQuestions?.size ?: 0)
        }
    }

    /**
     * What the session produced, and a chance to confirm it before the test: the
     * pages are the ones the layout showed, the questions are sized to how much
     * was read, and the reward is named before it is asked for.
     *
     * Three ways out: read on ([R.string.reader_stay]), leave without a test
     * ([R.string.reader_exit], which is what "no test, no minutes" means), or
     * take the test.
     */
    private fun showSummary(questionCount: Int) {
        val summary = viewModel.summary()
        val read = if (summary.fromPage == summary.toPage) {
            getString(R.string.reader_summary_page, summary.fromPage, summary.minutesRead)
        } else {
            getString(
                R.string.reader_summary_pages,
                summary.fromPage,
                summary.toPage,
                summary.pagesRead,
                summary.minutesRead
            )
        }
        val earned = viewModel.expectedMinutes()
        val ask = if (questionCount > 0) {
            getString(R.string.reader_summary_ask, viewModel.totalPages(), questionCount, earned)
        } else {
            getString(R.string.reader_summary_no_test, viewModel.totalPages(), earned)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_summary_title)
            .setMessage("$read\n\n$ask")
            .setPositiveButton(
                if (questionCount > 0) R.string.reader_take_test else R.string.reader_take_minutes
            ) { _, _ ->
                if (questionCount > 0) {
                    startQuiz()
                } else {
                    lifecycleScope.launch { award(viewModel.grantWithoutQuiz(), 0) }
                }
            }
            .setNeutralButton(R.string.reader_stay) { _, _ -> backToReading() }
            .setNegativeButton(R.string.reader_exit) { _, _ -> exitWithoutMinutes() }
            .show()
    }

    /**
     * A postponed session is offered as soon as the book is open, so the reader
     * sees what is waiting for them: take the test, leave it for later, or drop
     * it. Until they answer, the clock is stopped — a dialog is not reading.
     */
    private fun maybeOfferPending() {
        val claim = pendingStore.forBook(intent.getLongExtra(EXTRA_BOOK_ID, -1L)) ?: return
        val earned = ReaderProgress.earnedMinutes(claim.minutesRead, viewModel.state.value.book?.format ?: "")
        val message = if (claim.fromPage == claim.toPage) {
            getString(R.string.reader_pending_one, claim.fromPage, claim.minutesRead, earned)
        } else {
            getString(
                R.string.reader_pending,
                claim.fromPage,
                claim.toPage,
                claim.pages,
                claim.minutesRead,
                earned
            )
        }
        modalOpen = true
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_pending_title)
            .setMessage(message)
            .setPositiveButton(R.string.reader_take_test) { _, _ -> claimPending(claim) }
            .setNeutralButton(R.string.reader_pending_later) { _, _ -> onPendingAnswered() }
            .setNegativeButton(R.string.reader_pending_forget) { _, _ ->
                pendingStore.remove(claim.bookId)
                onPendingAnswered()
            }
            .setOnCancelListener { onPendingAnswered() }
            .show()
    }

    /** The postponed test runs on the session it kept, not on the live clock. */
    private fun claimPending(claim: PendingSession) {
        claiming = true
        viewModel.beginPending(claim)
        onPendingAnswered()
        startQuiz()
    }

    private fun onPendingAnswered() {
        modalOpen = false
        // The user just touched the dialog; reading resumes from here.
        lastActivityMs = SystemClock.elapsedRealtime()
    }

    /**
     * Leaves the reader without taking the test. The session is kept for later
     * instead of being burned, so walking out by accident costs nothing.
     */
    private fun exitWithoutMinutes() {
        viewModel.persistProgress()
        val kept = viewModel.claimForExit()
        kept?.let { pendingStore.put(it) }
        toast(
            getString(
                if (kept != null) R.string.reader_exit_postponed else R.string.reader_exit_no_minutes
            )
        )
        finish()
    }

    /**
     * Asks the page which screens it showed this session, together with their
     * text. Answers on the main thread with null when the reader has no pages to
     * report (ribbon mode) or the page is not ready yet.
     */
    private fun pullExcerpt(onDone: (ReadExcerpt?) -> Unit) {
        binding.readerWeb.evaluateJavascript(
            "(function(){try{var e=Reader.excerpt();return e?JSON.stringify(e):null;}" +
                "catch(err){return null;}})()"
        ) { raw -> onDone(parseExcerpt(raw)) }
    }

    private fun parseExcerpt(raw: String?): ReadExcerpt? {
        if (raw == null || raw == "null" || raw.length < 4) return null
        return runCatching {
            // evaluateJavascript answers with JSON; a string comes quoted.
            val json = JSONObject(JSONTokener(raw).nextValue() as String)
            val text = json.optString("text")
            if (text.isBlank()) return null
            ReadExcerpt(
                text = text,
                fromPage = json.optInt("fromPage", 1),
                toPage = json.optInt("toPage", 1),
                pages = json.optInt("pages", 1)
            )
        }.getOrNull()
    }

    private fun startQuiz() {
        attempts++
        // The first attempt is exactly the test the summary announced; a retry
        // gets fresh questions from the same pages.
        val questions = preparedQuestions ?: viewModel.buildQuiz()
        preparedQuestions = null
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
        // Where the questions come from and how many there are: the test is
        // sized to the reading, so it must say so.
        val summary = viewModel.summary()
        val where = if (summary.fromPage == summary.toPage) {
            summary.fromPage.toString()
        } else {
            getString(R.string.reader_page_range, summary.fromPage, summary.toPage)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.reader_quiz_header, questions.size, where)
            textSize = 13f
            alpha = 0.7f
            setPadding(0, 0, 0, 12)
        })
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
            .setNeutralButton(R.string.reader_stay) { _, _ -> backToReading() }
            .setNegativeButton(R.string.reader_exit) { _, _ -> exitWithoutMinutes() }
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
            // A claimed postponed session is done with; a live one never had it.
            if (claiming) {
                pendingStore.remove(intent.getLongExtra(EXTRA_BOOK_ID, -1L))
            }
            claiming = false
            viewModel.cancelPending()
            Toast.makeText(
                this,
                getString(R.string.reader_quiz_pass, outcome.correct, outcome.total, outcome.grantedMinutes),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        val canRetry = attempts < MAX_ATTEMPTS
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_quiz_title)
            .setMessage(getString(R.string.reader_quiz_fail, outcome.correct, questionCount))
            .setPositiveButton(if (canRetry) R.string.reader_retry else R.string.reader_stay) { _, _ ->
                if (canRetry) startQuiz() else backToReading()
            }
            .setNegativeButton(R.string.reader_exit) { _, _ -> exitWithoutMinutes() }
            .show()
    }

    /** The user goes back to the book: the session continues counting honestly. */
    private fun backToReading() {
        attempts = 0
        preparedQuestions = null
        claiming = false
        viewModel.cancelPending()
        viewModel.unfreeze()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val KEY_THEME = "theme"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_MODE = "mode"
        private const val KEY_PAGING_HINT = "paging_hint_shown"

        /** Screen brightness the reader was left at; < 0 means "leave it alone". */
        private const val KEY_BRIGHTNESS = "brightness"

        /** Reading a full screen without touching it must not pause the timer. */
        private const val IDLE_TIMEOUT_MS = 180_000L

        /** How often the bookmark is written while the reader is active. */
        private const val AUTOSAVE_SECONDS = 30

        /** One fair second chance after a failed test, then the reading stands. */
        private const val MAX_ATTEMPTS = 2

        private const val TAG = "ReaderLayout"
    }
}
