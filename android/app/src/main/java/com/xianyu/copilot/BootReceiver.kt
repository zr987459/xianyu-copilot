package com.xianyu.copilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> handleAutoStart(context)
            else -> Unit
        }
    }

    private fun handleAutoStart(context: Context) {
        try {
            val db = CardDatabase.get(context)
            val enabled = db.getSetting("daemon_enabled", "true") == "true"
            if (enabled) {
                DaemonService.start(context)
                Log.i(TAG, "DaemonService auto-started")
            } else {
                Log.i(TAG, "daemon_enabled=false, skip")
            }
        } catch (e: Exception) {
            Log.e(TAG, "auto start failed", e)
        }
    }
}
