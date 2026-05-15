package com.familylocation

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var npHour: NumberPicker
    private lateinit var npMinute: NumberPicker
    private lateinit var btnSaveTime: Button
    private lateinit var btnToggle: Button
    private lateinit var btnTest: Button
    private lateinit var tvState: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvLastStatus: TextView
    private lateinit var cardStatus: View

    private enum class PendingAction { NONE, ENABLE, TEST }
    private var pendingAction = PendingAction.NONE

    private val basicPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants.values.all { it }
        if (!ok) {
            pendingAction = PendingAction.NONE
            toast("需要位置权限才能发送高精度位置")
            return@registerForActivityResult
        }
        when (pendingAction) {
            PendingAction.TEST -> {
                pendingAction = PendingAction.NONE
                startLocationService()
            }
            PendingAction.ENABLE -> checkBackgroundLocation()
            else -> Unit
        }
    }

    private val android10BgLocLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) checkExactAlarmPermission() else showBackgroundLocationSettingsDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.et_url)
        npHour = findViewById(R.id.np_hour)
        npMinute = findViewById(R.id.np_minute)
        btnSaveTime = findViewById(R.id.btn_save_time)
        btnToggle = findViewById(R.id.btn_toggle)
        btnTest = findViewById(R.id.btn_test)
        tvState = findViewById(R.id.tv_state)
        tvLastSent = findViewById(R.id.tv_last_sent)
        tvLastStatus = findViewById(R.id.tv_last_status)
        cardStatus = findViewById(R.id.card_status)

        etUrl.setText(Prefs.getWebhookUrl(this))
        setupWheelPickers()
        syncToggleUI(Prefs.isEnabled(this))

        btnSaveTime.setOnClickListener {
            if (!saveConfig()) return@setOnClickListener
            if (Prefs.isEnabled(this)) {
                AlarmScheduler.cancel(this)
                AlarmScheduler.schedule(this)
                syncToggleUI(true)
                toast("已改为每天 ${Prefs.getSendTimeText(this)} 自动发送")
            } else {
                syncToggleUI(false)
                toast("发送时间已保存：${Prefs.getSendTimeText(this)}")
            }
        }

        btnToggle.setOnClickListener {
            val enabled = Prefs.isEnabled(this)
            if (!enabled) {
                if (!saveConfig()) return@setOnClickListener
                Prefs.setPendingEnable(this, true)
                pendingAction = PendingAction.ENABLE
                requestBasicPermissionsOrContinue()
            } else {
                pendingAction = PendingAction.NONE
                Prefs.setPendingEnable(this, false)
                Prefs.setEnabled(this, false)
                AlarmScheduler.cancel(this)
                syncToggleUI(false)
                toast("已关闭每日位置发送")
            }
        }

        btnTest.setOnClickListener {
            if (!saveConfig()) return@setOnClickListener
            pendingAction = PendingAction.TEST
            requestBasicPermissionsOrContinue()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.isPendingEnable(this) && !Prefs.isEnabled(this)) {
            if (hasBasicLocationPermission() && hasBackgroundLocationPermission() && hasExactAlarmPermission()) {
                doEnable()
            }
        }
        updateStatus()
    }

    private fun setupWheelPickers() {
        npHour.minValue = 0
        npHour.maxValue = 23
        npHour.displayedValues = Array(24) { String.format("%02d", it) }
        npHour.wrapSelectorWheel = true
        npHour.value = Prefs.getSendHour(this)

        npMinute.minValue = 0
        npMinute.maxValue = 59
        npMinute.displayedValues = Array(60) { String.format("%02d", it) }
        npMinute.wrapSelectorWheel = true
        npMinute.value = Prefs.getSendMinute(this)
    }

    private fun saveConfig(): Boolean {
        val url = etUrl.text.toString().trim()
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            toast("请先填写正确的接收地址")
            return false
        }
        Prefs.setWebhookUrl(this, url)
        Prefs.setSendTime(this, npHour.value, npMinute.value)
        return true
    }

    private fun requestBasicPermissionsOrContinue() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            if (pendingAction == PendingAction.TEST) {
                pendingAction = PendingAction.NONE
                startLocationService()
                toast("正在获取高精度位置，最多等待 2 分钟…")
            } else {
                checkBackgroundLocation()
            }
        } else {
            basicPermLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocationPermission()) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                AlertDialog.Builder(this)
                    .setTitle("后台位置权限")
                    .setMessage("请选择「始终允许」，否则锁屏后无法按时自动发送。")
                    .setPositiveButton("继续") { _, _ ->
                        android10BgLocLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton("取消") { _, _ -> Prefs.setPendingEnable(this, false) }
                    .show()
            } else {
                showBackgroundLocationSettingsDialog()
            }
            return
        }
        checkExactAlarmPermission()
    }

    private fun showBackgroundLocationSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要手动开启后台位置")
            .setMessage("请在设置页进入「权限」→「位置信息」→ 选择「始终允许」，并开启「使用精确位置」。")
            .setPositiveButton("去应用设置") { _, _ -> openAppSettings() }
            .setNegativeButton("取消") { _, _ -> Prefs.setPendingEnable(this, false) }
            .show()
    }

    private fun checkExactAlarmPermission() {
        if (!hasExactAlarmPermission()) {
            AlertDialog.Builder(this)
                .setTitle("精确闹钟权限")
                .setMessage("需要允许「闹钟和提醒 / 精确提醒」，才能尽量准时触发。")
                .setPositiveButton("去设置") { _, _ -> openExactAlarmSettings() }
                .setNegativeButton("跳过") { _, _ -> doEnable() }
                .show()
            return
        }
        doEnable()
    }

    private fun doEnable() {
        pendingAction = PendingAction.NONE
        Prefs.setPendingEnable(this, false)
        Prefs.setEnabled(this, true)
        AlarmScheduler.schedule(this)
        syncToggleUI(true)
        toast("已设置：每天 ${Prefs.getSendTimeText(this)} 自动发送位置")
    }

    private fun startLocationService() {
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun hasBasicLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasExactAlarmPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun syncToggleUI(enabled: Boolean) {
        val time = Prefs.getSendTimeText(this)
        btnToggle.text = if (enabled) "🟢  关闭自动发送" else "⚪  开启每日 $time 发送"
        cardStatus.visibility = if (enabled) View.VISIBLE else View.GONE
        updateStatus()
    }

    private fun updateStatus() {
        val lastSent = Prefs.getLastSent(this)
        val lastStatus = Prefs.getLastStatus(this)
        val time = Prefs.getSendTimeText(this)
        tvLastSent.text = if (lastSent.isNotEmpty()) "上次发送时间：$lastSent" else "尚未发送过"
        tvLastStatus.text = lastStatus
        tvState.text = if (Prefs.isEnabled(this)) "每天 $time 自动发送高精度位置 🗺️" else ""
        if (!Prefs.isEnabled(this)) btnToggle.text = "⚪  开启每日 $time 发送"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        })
    }

    private fun openExactAlarmSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
        }
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { openAppSettings() }
    }
}
