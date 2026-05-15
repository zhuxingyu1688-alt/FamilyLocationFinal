package com.familylocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 手机重启后，如果之前已开启，重新注册闹钟
        if (Prefs.isEnabled(context)) {
            AlarmScheduler.schedule(context)
        }
    }
}
