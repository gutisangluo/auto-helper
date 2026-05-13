package com.autohelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

class AutoHelperService : AccessibilityService() {

    companion object {
        var mediaProjection: MediaProjection? = null
        var projectionReady: Boolean = false
        var logCallback: ((String) -> Unit)? = null
        var statusCallback: ((String) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var stepCount = 0
    private val maxSteps = 30
    private var apiKey = ""
    private var taskDesc = ""
    private var displayDensity = 0
    private var displayWidth = 0
    private var displayHeight = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        appendLog("🔌 无障碍服务已创建")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "auto_helper",
                "AI 自动助手",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 获取屏幕尺寸
        val display = windows?.firstOrNull()?.root?.let {
            val metrics = resources.displayMetrics
            displayDensity = metrics.densityDpi
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
        }
        appendLog("🔌 无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        appendLog("⚠️ 服务中断")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须调用 startForeground，否则 Android 14+ 会崩溃
        val notification = android.app.Notification.Builder(this, "auto_helper")
            .setContentTitle("AI 自动助手")
            .setContentText("运行中...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)

        when (intent?.getStringExtra("action")) {
            "start" -> {
                apiKey = intent.getStringExtra("api_key") ?: ""
                taskDesc = intent.getStringExtra("task_desc") ?: ""
                if (apiKey.isNotEmpty() && !isRunning) {
                    if (!projectionReady) {
                        appendLog("⚠️ 请先在 App 中授权屏幕截取权限")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    isRunning = true
                    stepCount = 0
                    appendLog("🚀 开始自动化: $taskDesc")
                    handler.postDelayed({ startAutoLoop() }, 2000)
                }
            }
            "stop" -> stopAuto()
        }
        return START_NOT_STICKY
    }

    private fun startAutoLoop() {
        if (!isRunning || stepCount >= maxSteps) {
            if (stepCount >= maxSteps) appendLog("⏹ 已达最大步数($maxSteps)")
            stopAuto()
            return
        }
        stepCount++
        appendLog("📍 第 $stepCount / $maxSteps 步")

        scope.launch {
            try {
                val screenshot = takeScreenshot()
                if (screenshot == null) {
                    appendLog("❌ 截屏失败，重试...")
                    delay(2000); handler.post { startAutoLoop() }; return@launch
                }
                val base64 = bitmapToBase64(screenshot)
                screenshot.recycle()

                appendLog("⏳ 分析页面...")
                val result = callDeepSeek(base64)
                if (result == null) {
                    appendLog("❌ API 失败，5秒后重试")
                    delay(5000); handler.post { startAutoLoop() }; return@launch
                }
                appendLog("💬 $result")

                val doneWords = listOf("已完成", "任务结束", "没有可点击", "全部完成", "明日再来")
                if (doneWords.any { result.contains(it) }) {
                    appendLog("✅ 任务完成！")
                    isRunning = false; return@launch
                }

                parseAndClick(result)
                delay(3000)
                handler.post { startAutoLoop() }

            } catch (e: Exception) {
                appendLog("❌ ${e.message}")
                delay(3000); handler.post { startAutoLoop() }
            }
        }
    }

    private fun callDeepSeek(base64: String): String? {
        val prompt = """
你是一个手机自动化助手。$taskDesc

分析截图，回复格式：
说明=页面描述
操作=点击|x,y|等待|返回

示例：
说明=首页，看到【签到】按钮
操作=点击|540,1200

完成时说：操作=已完成
""".trimIndent()

        val body = JSONObject().apply {
            put("model", "deepseek-chat")
            put("max_tokens", 500)
            put("temperature", 0.1)
            val msgs = JSONArray()
            val user = JSONObject()
            val content = JSONArray()
            content.put(JSONObject().apply {
                put("type", "text"); put("text", prompt)
            })
            content.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/png;base64,$base64")
                })
            })
            user.put("role", "user"); user.put("content", content)
            msgs.put(user)
            put("messages", msgs)
        }

        val req = Request.Builder()
            .url("https://api.deepseek.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { resp.close(); return null }
            val json = JSONObject(resp.body!!.string())
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } catch (e: Exception) { null }
    }

    private fun parseAndClick(resp: String) {
        val re = Regex("""操作=\s*点击\s*[|，]\s*(\d+)\s*[,，]\s*(\d+)""")
        val m = re.find(resp)
        if (m != null) {
            val x = m.groupValues[1].toIntOrNull() ?: return
            val y = m.groupValues[2].toIntOrNull() ?: return
            appendLog("👆 点击($x, $y)")
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
            return
        }
        if (resp.contains("操作=返回")) {
            appendLog("🔙 返回"); performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun takeScreenshot(): Bitmap? {
        val proj = mediaProjection ?: return null
        val reader = ImageReader.newInstance(displayWidth, displayHeight, PixelFormat.RGBA_8888, 2)
        val vDisplay: VirtualDisplay = proj.createVirtualDisplay(
            "Screenshot", displayWidth, displayHeight, displayDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        Thread.sleep(300)
        val image = reader.acquireLatestImage() ?: run { vDisplay.release(); reader.close(); return null }
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * displayWidth
        val bitmap = Bitmap.createBitmap(displayWidth + rowPadding / pixelStride, displayHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        val result = Bitmap.createBitmap(bitmap, 0, 0, displayWidth, displayHeight)
        image.close()
        vDisplay.release()
        reader.close()
        return result
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun stopAuto() {
        isRunning = false
        stopForeground(true)
        stopSelf()
    }

    private fun appendLog(msg: String) {
        println("AI-Helper: $msg")
        // 通知 MainActivity 更新日志（可通过静态回调）
        logCallback?.invoke(msg)
    }

}
