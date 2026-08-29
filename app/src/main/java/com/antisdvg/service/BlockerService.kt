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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
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

    // Fields for the overlay view.
    private var blockView: android.view.View? = null

    companion object {
        const val CHANNEL_ID = "blocker"
        const val NOTIFICATION_ID = 1001

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
                    // A transient system/overlay window (status bar, VPN overlay,
                    // system UI) does not mean the user left the blocked app. Only
                    // a real jump to a non-blocked app should stop the spend timer;
                    // otherwise the 60s tick gets cancelled by harmless windows and
                    // earned minutes drain at half the real pace.
                    if (activeApp != null && !isTransientSystemWindow(pkg)) {
                        stopAccessSpendTimer(pkg)
                    }
                    removeBlockView()
                    return
                }

                // A blocked app is now in the foreground.
                startAccessApplication(pkg)
            }

            // Type is enabled only to keep the spend timer alive on app resume.
            // On the phone, resuming a blocked app from recents/tap does NOT always
            // emit a TYPE_WINDOW_STATE_CHANGED, so the old code never started the
            // tick timer and earned minutes were never spent even while the user
            // sat in TikTok/YouTube. Content-change events fire constantly while a
            // blocked app is foregrounded, so we use them to (re)assert the active
            // blocked app. Content events are never used to STOP spending: a
            // transient content popup must not cancel the active tick.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (!blockerEnabled) return
                if (pkg == packageName) return
                if (pkg in blockedPackages) {
                    // startAccessApplication returns early if already active, so
                    // this is cheap even though content changes arrive very often.
                    startAccessApplication(pkg)
                }
            }
        }
    }

    /**
     * The user is inside a blocked app. Grant them earned time if they have any,
     * otherwise give a short grace window before blocking.
     */
    private fun startAccessApplication(pkg: String) {
        if (activeApp == pkg) {
            // Same app still foreground; the tick timer is already running.
            return
        }
        Log.d("BlockerSvc", "startAccessApplication pkg=$pkg remaining=${prefs.readRemainingMinutes()}")
        stopAccessSpendTimer(null)
        removeBlockView()
        activeApp = pkg

        val remaining = prefs.readRemainingMinutes()
        if (remaining > 0) {
            // Spend their earned minutes in real time while the app is open.
            startAccessSpendTimer()
        } else {
            // No earned time: block immediately.
            showBlockView()
        }
    }

    /** Spends one earned minute per elapsed minute while a blocked app is open. */
    private fun startAccessSpendTimer() {
        spendJob?.cancel()
        spendJob = serviceScope.launch {
            var flushed = 0
            while (coroutineContext.isActive) {
                delay(60_000)
                // Don't drain time while the screen is off (device asleep / Doze).
                // A blocked app may still report as foreground with the screen off
                // (e.g. background audio or a video that kept playing), and that
                // used to burn the whole remaining balance overnight. Only spend
                // when the screen is actually interactive.
                if (!isScreenOn()) continue
                val newRemaining = prefs.tickDownRemaining(1)
                flushed++
                // Flush to Room in batches so the statistics stay accurate without
                // hammering the DB on every single tick.
                if (flushed >= 5) {
                    flushSpentMinutes(flushed)
                    flushed = 0
                }
                if (newRemaining <= 0) {
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
            pkg.startsWith("v2ray") || pkg.startsWith("com.tm") // VPN overlay
    }

    /** True when the screen is on and interactive (not asleep / locked-off). */
    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    /**
     * Stops the in-flight spend timer. If [nextPkg] is null the app changed to a
     * non-blocked window and any partial (sub-minute) time is treated as spent.
     */
    private fun stopAccessSpendTimer(nextPkg: String?) {
        Log.d("BlockerSvc", "stopAccessSpendTimer nextPkg=$nextPkg")
        spendJob?.cancel()
        spendJob = null
        if (activeApp != null) {
            activeApp = nextPkg?.takeIf { it in blockedPackages }
        }
    }

    /** Pushes the spent-time state from the fast prefs mirror into Room. */
    private fun flushSpentMinutes(batch: Int) {
        if (batch <= 0) return
        CoroutineScope(Dispatchers.IO).launch {
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
        serviceScope.cancel()
    }

    // ---- Refresh blocked packages (called on broadcast or service connect) ----

    private fun refreshBlockedPackages() {
        CoroutineScope(Dispatchers.IO).launch {
            val settings = AppDatabase.getInstance(this@BlockerService)
                .appDao().getSettings()
            val apps = AppDatabase.getInstance(this@BlockerService)
                .appDao().getBlockedApps()
            val enabled = apps.filter { it.enabled }.mapTo(mutableSetOf()) { it.packageName }
            runOnMain {
                blockerEnabled = settings?.blockerEnabled ?: true
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

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.view_block_overlay, null)
        view.findViewById<TextView>(R.id.block_message).text =
            getString(R.string.blocked_message)

        view.findViewById<Button>(R.id.block_unlock).setOnClickListener {
            // Open the app so the user can decide to read for time.
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("from_blocker", true)
            }
            startActivity(intent)
            removeBlockView()
        }

        // Spend an emergency token for a +10 min grace period instead of reading.
        // Reads/writes Room on IO; never block the accessibility thread.
        view.findViewById<Button>(R.id.block_spend_token).setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val dao = AppDatabase.getInstance(this@BlockerService).appDao()
                val repo = DataRepository(dao, prefs)
                val spent = repo.spendEmergencyToken()
                if (spent) repo.grantEmergencyMinutes(EMERGENCY_MINUTES_PER_TOKEN)
                // Refresh the in-memory blocked-apps/settings cache so the
                // fast path on the accessibility thread sees the new minutes.
                refreshFromDatabase()
                removeBlockView()
            }
            removeBlockView()
        }

        // Leave the overlay and return to the launcher without reading or
        // spending a token. The blocker stays armed for the next launch.
        view.findViewById<Button>(R.id.block_exit).setOnClickListener {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(home)
            removeBlockView()
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
