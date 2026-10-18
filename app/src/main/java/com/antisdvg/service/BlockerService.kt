package com.antisdvg.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import com.antisdvg.R
import com.antisdvg.data.local.AppDatabase
import com.antisdvg.data.model.BlockedApp
import com.antisdvg.data.repository.DataRepository
import com.antisdvg.ui.activities.MainActivity
import com.antisdvg.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches for a blocked app coming to the foreground and, when the user has no
 * remaining fun time AND no emergency token, shows a full-screen "read to
 * unlock" overlay instead of letting them in.
 *
 * This is a "shield of honesty": it can be turned off, but the whole premise of
 * the app relies on the user keeping it on.
 *
 * Fast path: time/token values are read from a SharedPreferences mirror
 * ([PreferencesManager]) rather than from Room, because every window-change
 * event is dispatched on the accessibility thread and must not block on a DB.
 */
class BlockerService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var spendJob: Job? = null

    private lateinit var prefs: PreferencesManager
    private lateinit var windowManager: WindowManager

    @Volatile
    private var blockedPackages: Set<String> = emptySet()

    // Currently-active blocked package whose window is in the foreground and is
    // being given earned time. Set only while the app is on screen.
    @Volatile
    private var activeApp: String? = null

    // Spend accounting: per-started-minute billing to close the 59s-peek exploit.
    private var spendSessionStartMs: Long = 0L
    private var lastMinuteSpendMs: Long = 0L

    /**
     * What one emergency token buys, cached for the overlay's label: the overlay
     * is built on the main thread and must not read the database there.
     */
    @Volatile
    private var emergencyMinutes: Int = EMERGENCY_MINUTES_PER_TOKEN

    /**
     * The interrupted visit, on disk.
     *
     * The in-flight spend lived only in memory, so a service restart in the middle
     * of a visit quietly forgot the minute that had already started — and killing
     * the service became a way not to pay. The state is kept here and settled when
     * the service comes back.
     */
    private val spendPrefs by lazy { getSharedPreferences("blocker_spend", Context.MODE_PRIVATE) }

    private fun persistSpendSession(
        packageName: String?,
        startedMs: Long,
        lastMinuteMs: Long,
        lastSeenMs: Long = lastMinuteMs
    ) {
        if (packageName == null) return
        spendPrefs.edit()
            .putString(SPEND_PKG, packageName)
            .putLong(SPEND_STARTED, startedMs)
            .putLong(SPEND_LAST_MINUTE, lastMinuteMs)
            .putLong(SPEND_LAST_SEEN, lastSeenMs)
            .apply()
    }

    private fun clearSpendSession() {
        spendPrefs.edit().clear().apply()
    }

    /**
     * Settles a visit a restart interrupted: the started minute is charged (the
     * same rule as walking out by hand), unless the service was gone long enough
     * that charging would be a guess — then the visit is simply forgotten.
     */
    private fun settleInterruptedSpend() {
        val pkg = spendPrefs.getString(SPEND_PKG, null) ?: return
        val started = spendPrefs.getLong(SPEND_STARTED, 0L)
        val lastMinute = spendPrefs.getLong(SPEND_LAST_MINUTE, 0L)
        // How long the visit really lasted comes from the heartbeat, not from the
        // last charged minute: until a full minute has been charged that number is
        // still zero, so a 15-second visit interrupted by a restart used to settle
        // as "visited 0ms" and cost nothing at all.
        val lastSeen = spendPrefs.getLong(SPEND_LAST_SEEN, lastMinute)
        clearSpendSession()
        if (started <= 0L || lastSeen <= 0L) return
        val visited = lastSeen - started
        val gap = System.currentTimeMillis() - lastSeen
        Log.d("BlockerSvc", "settle interrupted spend pkg=$pkg visited=${visited}ms gap=${gap}ms")
        if (gap <= INTERRUPTED_SPEND_MS && visited >= 10_000) {
            prefs.tickDownRemaining(1)
            flushSpentMinutes(1)
        }
    }

    // Fields for the overlay view.
    private var blockView: android.view.View? = null

    // Fields for the floating countdown timer pill.
    private var floatingTimerView: android.view.View? = null
    private var floatingParams: WindowManager.LayoutParams? = null

    // Known book reader apps to detect reading sessions and remind to record pages.
    private val readerPackages = setOf(
        "org.readera",
        "org.readera.premium",
        "com.neverland.fb2reader",
        "org.geometerplus.zlibrary.ui.android",
        "com.flyersoft.moonreader",
        "com.flyersoft.moonreaderp",
        "com.foobnix.pdf.reader",
        "com.yandex.audiobooks",
        "ru.litres.android",
        "com.google.android.apps.books",
        "com.amazon.kindle"
    )
    private var readingSessionStartMs: Long = 0L
    private var activeReaderPkg: String? = null

    companion object {
        const val CHANNEL_ID = "blocker"
        const val REMINDER_CHANNEL_ID = "reading_reminders"
        const val NOTIFICATION_ID = 1001
        const val REMINDER_NOTIFICATION_ID = 1002

        /** How long a restart may take before an interrupted visit is forgotten. */
        private const val INTERRUPTED_SPEND_MS = 2 * 60 * 1000L

        private const val SPEND_PKG = "pkg"
        private const val SPEND_STARTED = "started"
        private const val SPEND_LAST_MINUTE = "lastMinute"
        private const val SPEND_LAST_SEEN = "lastSeen"

        /** Minutes granted when the user spends one emergency token. */
        const val EMERGENCY_MINUTES_PER_TOKEN = 10

        const val ACTION_REFRESH_BLOCKED = "com.antisdvg.REFRESH_BLOCKED"
        const val EXTRA_BLOCKED = "blocked_packages"

        /** Broadcast to nudge the service to reload blocked apps + settings. */
        fun broadcastRefresh(context: Context) {
            context.sendBroadcast(Intent(ACTION_REFRESH_BLOCKED).setPackage(context.packageName))
        }
    }

    // In-memory settings cache (kept current via broadcast).
    private var blockerEnabled = true

    @Volatile
    private var dailyLimitMin: Int = 0

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshFromDatabase()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = PreferencesManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Start foreground notification so the user knows the blocker is on.
        startForegroundNotification()
        // The fence is back: take the warning down and settle whatever visit a
        // restart interrupted.
        cancelBlockerWarning()
        settleInterruptedSpend()

        registerReceiver(
            refreshReceiver,
            IntentFilter(ACTION_REFRESH_BLOCKED),
            android.content.Context.RECEIVER_NOT_EXPORTED
        )

        // Initial load.
        refreshBlockedPackages()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                Log.d("BlockerSvc", "WINDOW_STATE pkg=$pkg blockedEnabled=$blockerEnabled activeApp=$activeApp blocked=$blockedPackages")
                if (!blockerEnabled) return
                Log.d("BlockerSvc", "after gate pkg=$pkg")

                // Ignore our own overlay window. When the block overlay is shown
                // it is an application-overlay owned by this package, so Android
                // emits a window-state event for com.antisdvg. Treating that as
                // "the user escaped to a non-blocked app" would make the blocker
                // remove its own overlay right after showing it (the overlay can
                // never stay up). Our own window must never clear the block.
                if (pkg == packageName) {
                    return
                }

                if (pkg !in blockedPackages) {
                    checkReaderAppSession(pkg)
                    if (activeApp != null && !isTransientSystemWindow(pkg)) {
                        stopAccessSpendTimer(pkg)
                    }
                    removeBlockView()
                    removeFloatingTimer()
                    return
                }

                // A blocked app is now in the foreground.
                startAccessApplication(pkg)
            }

            // Type is enabled only to keep the spend timer alive on app resume.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (!blockerEnabled) return
                if (pkg == packageName) return
                if (pkg in blockedPackages) {
                    startAccessApplication(pkg)
                }
            }
        }
    }

    /**
     * The user is inside a blocked app. If earned time is left they get it (and
     * it is counted), otherwise the block goes up immediately.
     */
    private fun startAccessApplication(pkg: String) {
        if (activeApp == pkg) {
            // Still inside the same blocked app. Normally the overlay is up or the
            // spend timer is running. If neither is true the fence has a hole —
            // an exit that did not stick, or a window that took the overlay down —
            // and guessing is not good enough for an app whose whole promise is
            // that these apps stay closed. Put the block back.
            if (blockView != null || spendJob != null) return
            Log.d("BlockerSvc", "startAccessApplication: same app, no block up — re-arming")
        }
        Log.d("BlockerSvc", "startAccessApplication pkg=$pkg remaining=${prefs.readRemainingMinutes()}")
        stopAccessSpendTimer(null)
        removeBlockView()
        activeApp = pkg

        val remaining = prefs.readRemainingMinutes()
        val capped = com.antisdvg.util.SpendTracker.limitReached(
            prefs.currentSpentToday(), dailyLimitMin
        )
        if (capped) {
            Log.d("BlockerSvc", "daily limit reached spent=${prefs.currentSpentToday()} limit=$dailyLimitMin")
        }
        if (remaining > 0 && !capped) {
            startAccessSpendTimer()
        } else {
            showBlockView()
        }
    }

    /** Spends earned time while a blocked app is open. Ticks every 15s, charges per started minute. */
    private fun startAccessSpendTimer() {
        spendJob?.cancel()
        val currentRemaining = prefs.readRemainingMinutes()
        showFloatingTimer(currentRemaining)
        val now = System.currentTimeMillis()
        spendSessionStartMs = now
        lastMinuteSpendMs = now
        persistSpendSession(activeApp, now, now)
        spendJob = serviceScope.launch {
            while (coroutineContext.isActive) {
                delay(15_000)
                if (!isScreenOn()) continue
                val elapsed = System.currentTimeMillis()
                // A heartbeat, so an interrupted visit can be measured even before
                // the first full minute has been charged.
                persistSpendSession(activeApp, spendSessionStartMs, lastMinuteSpendMs, elapsed)
                if (elapsed - lastMinuteSpendMs < 60_000) continue
                lastMinuteSpendMs = elapsed
                persistSpendSession(activeApp, spendSessionStartMs, elapsed, elapsed)
                val newRemaining = prefs.tickDownRemaining(1)
                updateFloatingTimerText(newRemaining)
                if (newRemaining == 1) {
                    triggerOneMinuteWarning()
                }
                flushSpentMinutes(1)
                if (newRemaining <= 0) {
                    removeFloatingTimer()
                    stopAccessSpendTimer(null)
                    showBlockView()
                    break
                }
            }
        }
    }

    /** True for system/overlay windows that may pop over a blocked app without the
     * user leaving it (system UI, VPN overlays, notification shade). These must not
     * cancel an in-flight spend timer. */
    private fun isTransientSystemWindow(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
            pkg.startsWith("com.android.keyguard") ||
            pkg.startsWith("com.samsung.android.incallui") ||
            pkg.startsWith("v2ray") || pkg.startsWith("com.tm")
    }

    /** True when the screen is on and interactive (not asleep / locked-off). */
    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    /**
     * Stops the in-flight spend timer. A partial (sub-minute) visit costs one
     * minute when the user spent at least 10s inside, so 59s peeks are not free.
     */
    private fun stopAccessSpendTimer(nextPkg: String?) {
        Log.d("BlockerSvc", "stopAccessSpendTimer nextPkg=$nextPkg")
        val wasSpending = spendJob != null
        val now = System.currentTimeMillis()
        spendJob?.cancel()
        spendJob = null
        removeFloatingTimer()
        if (wasSpending && activeApp != null) {
            val sessionElapsed = now - spendSessionStartMs
            val sinceLastSpend = now - lastMinuteSpendMs
            if (sessionElapsed >= 10_000 && sinceLastSpend >= 10_000) {
                val newRemaining = prefs.tickDownRemaining(1)
                lastMinuteSpendMs = now
                updateFloatingTimerText(newRemaining)
            }
            flushSpentMinutes(1)
        }
        if (activeApp != null) {
            activeApp = nextPkg?.takeIf { it in blockedPackages }
        }
    }

    /** Pushes the spent-time state from the fast prefs mirror into Room. */
    private fun flushSpentMinutes(batch: Int) {
        if (batch <= 0) return
        serviceScope.launch(Dispatchers.IO) {
            try {
                DataRepository(AppDatabase.getInstance(this@BlockerService).appDao(), prefs)
                    .syncRemainingFromPrefs()
            } catch (t: Throwable) {
                // Best-effort flush; the prefs mirror remains authoritative.
            }
        }
    }

    override fun onInterrupt() {
        // Accessibility may temporarily interrupt; nothing special needed.
    }

    /**
     * The system unbinds the service when the user (or something else) switches it
     * off. That is the moment the app must stop pretending: without the service
     * there is no blocking at all, and saying nothing would leave the fence down
     * for days while the app still claimed everything was fine.
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d("BlockerSvc", "onUnbind: the blocker is being switched off")
        runCatching { showBlockerWarning() }
        return super.onUnbind(intent)
    }

    private fun showBlockerWarning() = BlockerWarning.show(this)

    private fun cancelBlockerWarning() = BlockerWarning.cancel(this)

    override fun onDestroy() {
        super.onDestroy()
        if (::prefs.isInitialized) {
            try {
                unregisterReceiver(refreshReceiver)
            } catch (ignored: IllegalArgumentException) {
                // Already unregistered.
            }
        }
        removeBlockView()
        removeFloatingTimer()
        serviceScope.cancel()
    }

    // ---- Refresh blocked packages (called on broadcast or service connect) ----

    private fun refreshBlockedPackages() {
        serviceScope.launch(Dispatchers.IO) {
            val settings = AppDatabase.getInstance(this@BlockerService)
                .appDao().getSettings()
            val apps = AppDatabase.getInstance(this@BlockerService)
                .appDao().getBlockedApps()
            val enabled = apps.filter { it.enabled }.mapTo(mutableSetOf()) { it.packageName }
            runOnMain {
                blockerEnabled = settings?.blockerEnabled ?: true
                dailyLimitMin = settings?.dailyLimitMin ?: 0
                emergencyMinutes = (settings?.emergencyMinutes ?: EMERGENCY_MINUTES_PER_TOKEN)
                    .coerceAtLeast(0)
                blockedPackages = enabled
            }
        }
    }

    private fun refreshFromDatabase() = refreshBlockedPackages()

    private fun runOnMain(block: () -> Unit) {
        serviceScope.launch { block() }
    }

    // ---- Overlay ----

    private fun showBlockView() {
        if (blockView != null) return

        // Inflate with the app theme: the service base context has no
        // MaterialComponents attributes, which leaves MaterialButtons
        // invisible/untouchable in the overlay window.
        val themed = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_AntiSvdg)
        val view = android.view.LayoutInflater.from(themed).inflate(R.layout.view_block_overlay, null)
        view.findViewById<TextView>(R.id.block_message).text =
            getString(R.string.blocked_message)
        // The price of a token is a setting, so the button names the real number.
        view.findViewById<Button>(R.id.block_spend_token).text =
            getString(R.string.blocked_spend_token, emergencyMinutes)

        view.findViewById<Button>(R.id.block_unlock).setOnClickListener {
            Log.d("BlockerSvc", "overlay: unlock tapped")
            removeBlockView()
            // Straight into the book when there is one. At the moment of the
            // craving a trip through the home screen is a second chance to give up.
            val dao = AppDatabase.getInstance(this@BlockerService).appDao()
            serviceScope.launch(Dispatchers.IO) {
                val book = runCatching { dao.findActive() }.getOrNull()
                withContext(Dispatchers.Main) {
                    val target = if (book != null && book.filePath.isNotBlank()) {
                        Intent(this@BlockerService, com.antisdvg.ui.reader.ReaderActivity::class.java)
                            .putExtra(com.antisdvg.ui.reader.ReaderActivity.EXTRA_BOOK_ID, book.id)
                    } else {
                        Intent(this@BlockerService, MainActivity::class.java)
                    }
                    target.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(target)
                }
            }
        }

        // Spend an emergency token for a grace period from settings instead of reading.
        // Reads/writes Room on IO; remove overlay immediately on the Main thread.
        view.findViewById<Button>(R.id.block_spend_token).setOnClickListener {
            Log.d("BlockerSvc", "overlay: spend-token tapped")
            removeBlockView()
            val pkg = activeApp
            serviceScope.launch(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(this@BlockerService).appDao()
                val repo = DataRepository(dao, prefs)
                val minutes = (dao.getSettings()?.emergencyMinutes ?: EMERGENCY_MINUTES_PER_TOKEN).coerceAtLeast(0)
                val spent = repo.spendTokenForMinutes(minutes)
                // Refresh the in-memory blocked-apps/settings cache so the
                // fast path on the accessibility thread sees the new minutes.
                refreshFromDatabase()
                withContext(Dispatchers.Main) {
                    if (spent && pkg != null && activeApp == pkg && prefs.readRemainingMinutes() > 0) {
                        startAccessSpendTimer()
                    } else if (!spent) {
                        showBlockView()
                    }
                }
            }
        }

        // Leave the overlay and return to the launcher without reading or
        // spending a token. The blocker stays armed for the next launch.
        view.findViewById<Button>(R.id.block_exit).setOnClickListener {
            Log.d("BlockerSvc", "overlay: exit tapped")
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
            removeBlockView()
            // Walking out by hand must not leave the door open: coming back has to
            // be blocked again, and that hinges on forgetting which app we were in.
            // The emergency token stays the one clear way to buy time.
            activeApp = null
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(view, params)
            blockView = view
        } catch (e: Exception) {
            // Window already exists or permission missing; ignore.
            blockView = null
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun removeBlockView() {
        val view = blockView
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // Already removed.
            }
            blockView = null
        }
    }

    // ---- Floating countdown timer pill overlay ----

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingTimer(remainingMinutes: Int) {
        if (floatingTimerView != null) {
            updateFloatingTimerText(remainingMinutes)
            return
        }

        val themed = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_AntiSvdg)
        val view = android.view.LayoutInflater.from(themed).inflate(R.layout.view_floating_timer, null)
        val textView = view.findViewById<TextView>(R.id.floating_timer_text)
        textView.text = "$remainingMinutes мин"

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 48
            y = 180
        }
        floatingParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (ignored: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(view, params)
            floatingTimerView = view
        } catch (e: Exception) {
            floatingTimerView = null
        }
    }

    private fun updateFloatingTimerText(remainingMinutes: Int) {
        val view = floatingTimerView ?: return
        view.findViewById<TextView>(R.id.floating_timer_text)?.text = "$remainingMinutes мин"
        if (remainingMinutes <= 1) {
            view.findViewById<TextView>(R.id.floating_timer_icon)?.text = "⚠️"
        }
    }

    private fun removeFloatingTimer() {
        val view = floatingTimerView ?: return
        try {
            windowManager.removeView(view)
        } catch (ignored: Exception) {}
        floatingTimerView = null
        floatingParams = null
    }

    private fun triggerOneMinuteWarning() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(400)
            }
        } catch (ignored: Exception) {}
    }

    // ---- Reader apps detection and reminder ----

    private fun checkReaderAppSession(pkg: String) {
        if (pkg in readerPackages) {
            if (activeReaderPkg != pkg) {
                activeReaderPkg = pkg
                readingSessionStartMs = System.currentTimeMillis()
            }
        } else if (activeReaderPkg != null && !isTransientSystemWindow(pkg)) {
            val elapsedMinutes = ((System.currentTimeMillis() - readingSessionStartMs) / 60_000).toInt()
            if (elapsedMinutes >= 1) {
                notifyReaderFinished(elapsedMinutes)
            }
            activeReaderPkg = null
            readingSessionStartMs = 0L
        }
    }

    private fun notifyReaderFinished(minutes: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REMINDER_CHANNEL_ID,
                "Напоминания о чтении",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_reading", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, REMINDER_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle("Время зафиксировать чтение ")
            .setContentText("Вы читали около $minutes мин. Подтвердите прочитанные страницы в Anti-SDVG!")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(REMINDER_NOTIFICATION_ID, notif)
    }

    // ---- Foreground notification ----

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.blocker_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Notification.Builder(context, channelId) requires API 26+; minSdk is 24.
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setPriority(Notification.PRIORITY_LOW)
        }
        val notification = builder
            .setContentTitle(getString(R.string.blocker_notification_title))
            .setContentText(getString(R.string.blocker_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
