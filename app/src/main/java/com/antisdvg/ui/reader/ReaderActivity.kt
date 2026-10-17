package com.antisdvg.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
import com.antisdvg.data.model.QuizQuestion
import com.antisdvg.databinding.ActivityReaderBinding
import com.antisdvg.ui.AppViewModelFactory
import kotlinx.coroutines.launch
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

    /** Colors of the theme currently drawn, so the timer can dim itself when idle. */
    private var palette = paletteOf(ReaderTheme.PAPER)

    /** Active seconds since the last bookmark save; see [AUTOSAVE_SECONDS]. */
    private var secondsSinceSave = 0

    private val ticker = object : Runnable {
        override fun run() {
            val active = SystemClock.elapsedRealtime() - lastActivityMs < IDLE_TIMEOUT_MS
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
            } else {
                applyStyleChanges(state)
            }
        }

        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
        binding.readerProgress.progress = percent
        binding.readerPercent.text = getString(
            R.string.reader_position,
            percent,
            viewModel.currentPage(),
            viewModel.totalPages()
        )

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
        fun onActivity() {
            lastActivityMs = SystemClock.elapsedRealtime()
        }

        /** Layout numbers, so a bad page split can be diagnosed from the log. */
        @JavascriptInterface
        fun debug(message: String) {
            Log.d(TAG, message)
        }
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
     * Leaves the reader without taking the test: the reading and the bookmark
     * stay, the minutes of this session are not claimed. Said out loud, because
     * walking out is exactly how someone loses an hour of reading by accident.
     */
    private fun exitWithoutMinutes() {
        viewModel.persistProgress()
        toast(getString(R.string.reader_exit_no_minutes))
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
        viewModel.unfreeze()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val KEY_THEME = "theme"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_MODE = "mode"
        private const val KEY_PAGING_HINT = "paging_hint_shown"

        /** Reading a full screen without touching it must not pause the timer. */
        private const val IDLE_TIMEOUT_MS = 180_000L

        /** How often the bookmark is written while the reader is active. */
        private const val AUTOSAVE_SECONDS = 30

        /** One fair second chance after a failed test, then the reading stands. */
        private const val MAX_ATTEMPTS = 2

        private const val TAG = "ReaderLayout"
    }
}
