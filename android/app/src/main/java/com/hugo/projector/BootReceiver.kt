package com.hugo.projector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("HUGO_V2", "BOOT_COMPLETED received; starting local controller service")
        try {
            ProjectorRuntime.startService(context)
        } catch (e: Exception) {
            Log.e("HUGO_V2", "Controller service autostart blocked", e)
        }
    }
}
