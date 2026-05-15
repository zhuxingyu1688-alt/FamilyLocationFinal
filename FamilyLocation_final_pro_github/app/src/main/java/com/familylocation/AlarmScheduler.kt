package com.familylocation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {

    private const val REQUEST_CODE = 2001

    /**
     * 首次开启时调用：定到“最近的下一个用户设置时间”。
     * 例如现在 08:00，设置 07:30，则排到明天 07:30。
     */
    fun schedule(ctx: Context) {
        val nextTrigger = nextTime(ctx, addDays = 0)
        setAlarm(ctx, nextTrigger)
    }

    /**
     * 每次触发后由 AlarmReceiver 调用：预约明天同一时间，形成滚动链。
     */
    fun rescheduleForTomorrow(ctx: Context) {
        val nextTrigger = nextTime(ctx, addDays = 1)
        setAlarm(ctx, nextTrigger)
    }

    fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(pendingIntent(ctx))
    }

    private fun setAlarm(ctx: Context, triggerAtMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+：需要用户授权 SCHEDULE_EXACT_ALARM 权限。
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                // 未授权时降级：可穿透 Doze，但不保证完全准时。
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    /**
     * 计算目标触发时间戳。
     * addDays=0：今天的用户设置时间；如果已过，自动顺延到明天。
     * addDays=1：强制明天的用户设置时间。
     */
    private fun nextTime(ctx: Context, addDays: Int): Long {
        val hour = Prefs.getSendHour(ctx)
        val minute = Prefs.getSendMinute(ctx)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, addDays)
            if (addDays == 0 && timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
