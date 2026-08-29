package com.antisdvg

import android.app.Application
import android.content.Context
import com.antisdvg.data.local.AppDatabase
import com.antisdvg.data.model.AppSettings
import com.antisdvg.data.model.BlockedApp
import com.antisdvg.data.model.EmergencyTokens
import com.antisdvg.data.model.UserStats
import com.antisdvg.data.model.Wallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AntiSdvgApp : Application() {

    val db: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        seedDefaults(this)
    }

    private fun seedDefaults(context: Context) {
        val dao = AppDatabase.getInstance(context).appDao()
        CoroutineScope(Dispatchers.IO).launch {
            if (dao.getWallet() == null) dao.upsertWallet(Wallet())
            if (dao.getTokens() == null) dao.upsertTokens(EmergencyTokens())
            if (dao.getStats() == null) dao.upsertStats(UserStats())
            if (dao.getSettings() == null) dao.upsertSettings(AppSettings())

            // Seed default blocked apps if none exist yet.
            if (dao.getBlockedApps().isEmpty()) {
                dao.upsertBlockedApp(BlockedApp("com.google.android.youtube", "YouTube"))
                dao.upsertBlockedApp(BlockedApp("com.zhiliaoapp.musically", "TikTok"))
            }
        }
    }
}
