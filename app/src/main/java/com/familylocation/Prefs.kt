package com.familylocation

import android.content.Context
import java.util.Locale

object Prefs {
    private const val NAME = "fl_prefs"
    private const val KEY_URL = "webhook_url"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PENDING_ENABLE = "pending_enable"
    private const val KEY_LAST_SENT = "last_sent"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_SEND_HOUR = "send_hour"
    private const val KEY_SEND_MINUTE = "send_minute"

    private const val DEFAULT_FEISHU_URL = "https://open.feishu.cn/open-apis/bot/v2/hook/8731bf5d-87c8-400f-836f-3dd3c159a6f2"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getWebhookUrl(ctx: Context): String = sp(ctx).getString(KEY_URL, DEFAULT_FEISHU_URL) ?: DEFAULT_FEISHU_URL
    fun setWebhookUrl(ctx: Context, url: String) = sp(ctx).edit().putString(KEY_URL, url).apply()

    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, enabled: Boolean) = sp(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun isPendingEnable(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_PENDING_ENABLE, false)
    fun setPendingEnable(ctx: Context, pending: Boolean) = sp(ctx).edit().putBoolean(KEY_PENDING_ENABLE, pending).apply()

    fun getLastSent(ctx: Context): String = sp(ctx).getString(KEY_LAST_SENT, "") ?: ""
    fun setLastSent(ctx: Context, value: String) = sp(ctx).edit().putString(KEY_LAST_SENT, value).apply()

    fun getLastStatus(ctx: Context): String = sp(ctx).getString(KEY_LAST_STATUS, "") ?: ""
    fun setLastStatus(ctx: Context, value: String) = sp(ctx).edit().putString(KEY_LAST_STATUS, value).apply()

    fun getSendHour(ctx: Context): Int = sp(ctx).getInt(KEY_SEND_HOUR, 6).coerceIn(0, 23)
    fun getSendMinute(ctx: Context): Int = sp(ctx).getInt(KEY_SEND_MINUTE, 0).coerceIn(0, 59)
    fun setSendTime(ctx: Context, hour: Int, minute: Int) {
        sp(ctx).edit()
            .putInt(KEY_SEND_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_SEND_MINUTE, minute.coerceIn(0, 59))
            .apply()
    }

    fun getSendTimeText(ctx: Context): String = String.format(Locale.US, "%02d:%02d", getSendHour(ctx), getSendMinute(ctx))
}
