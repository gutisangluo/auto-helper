package com.autohelper

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PROJECTION = 1001
    }

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var etApiKey: EditText
    private lateinit var etTask: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private var mediaProjectionManager: MediaProjectionManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        etApiKey = findViewById(R.id.etApiKey)
        etTask = findViewById(R.id.etTask)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        AutoHelperService.logCallback = { msg ->
            runOnUiThread {
                tvLog.append("$msg\n")
            }
        }
        AutoHelperService.statusCallback = { status ->
            runOnUiThread { tvStatus.text = "状态: $status" }
        }

        // 读取保存的配置
        val prefs = getSharedPreferences("auto_helper", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))
        etTask.setText(prefs.getString("task_desc", "我正在做618签到活动，帮我自动完成签到和领红包任务"))

        btnStart.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val task = etTask.text.toString().trim()
            if (apiKey.isEmpty() || task.isEmpty()) {
                showAlert("提示", "请填写 API Key 和任务描述")
                return@setOnClickListener
            }
            getSharedPreferences("auto_helper", MODE_PRIVATE).edit()
                .putString("api_key", apiKey)
                .putString("task_desc", task)
                .apply()

            // 检查无障碍
            if (!isAccessibilityOn()) {
                showAlert("需要无障碍权限", "请在设置 → 无障碍 → AI自动助手，开启服务")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }

            // 请求屏幕截取权限
            startActivityForResult(
                mediaProjectionManager!!.createScreenCaptureIntent(),
                REQUEST_CODE_PROJECTION
            )
        }

        btnStop.setOnClickListener {
            AutoHelperService.statusCallback?.invoke("已停止")
            tvLog.append("⏹ 已停止\n")
            stopService(Intent(this, AutoHelperService::class.java))
        }

        tvStatus.text = "状态: 就绪"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PROJECTION && resultCode == RESULT_OK && data != null) {
            // 获取 MediaProjection
            val projection = mediaProjectionManager!!.getMediaProjection(resultCode, data)
            AutoHelperService.mediaProjection = projection
            AutoHelperService.projectionReady = true
            tvLog.append("✅ 屏幕截取权限已授予\n")
            tvLog.append("🚀 启动自动化...\n")

            btnStart.isEnabled = false
            btnStop.isEnabled = true

            val intent = Intent(this, AutoHelperService::class.java).apply {
                putExtra("api_key", etApiKey.text.toString().trim())
                putExtra("task_desc", etTask.text.toString().trim())
                putExtra("action", "start")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            tvLog.append("⚠️ 屏幕截取权限被拒绝\n")
        }
    }

    private fun isAccessibilityOn(): Boolean {
        val svc = "${packageName}/com.autohelper.AutoHelperService"
        return try {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains(svc) == true
        } catch (e: Exception) { false }
    }

    private fun showAlert(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("确定", null).show()
    }

    override fun onDestroy() {
        AutoHelperService.logCallback = null
        AutoHelperService.statusCallback = null
        super.onDestroy()
    }
}
