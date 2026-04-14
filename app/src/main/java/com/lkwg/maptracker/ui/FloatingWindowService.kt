package com.lkwg.maptracker.ui

import android.app.*
import android.content.*
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lkwg.maptracker.service.ScreenCaptureService
import java.io.File

/**
 * 悬浮窗服务
 * 实时显示大地图 + 位置标注
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindow"
        private const val WINDOW_SIZE_DP = 360
        private const val CHANNEL_ID = "map_tracker_overlay"
        private const val NOTIFICATION_ID = 2
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var mapImageView: ImageView? = null
    private var positionText: TextView? = null
    private var fullMapBitmap: Bitmap? = null

    private var currentX = 0.0
    private var currentY = 0.0
    private var currentConfidence = 0f
    private var currentRotation = 0.0

    private val matchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_MATCH_RESULT) {
                currentX = intent.getDoubleExtra(ScreenCaptureService.EXTRA_POS_X, 0.0)
                currentY = intent.getDoubleExtra(ScreenCaptureService.EXTRA_POS_Y, 0.0)
                currentConfidence = intent.getFloatExtra(ScreenCaptureService.EXTRA_CONFIDENCE, 0f)
                currentRotation = intent.getDoubleExtra(ScreenCaptureService.EXTRA_ROTATION, 0.0)
                updateOverlay()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // ⚠️ 必须先启动前台服务，否则 Android 14+ 会直接杀掉服务
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("🗺️ 悬浮窗已启动"))

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter(ScreenCaptureService.ACTION_MATCH_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(matchReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(matchReceiver, filter)
        }

        loadMap()
        createWindow()
    }

    private fun loadMap() {
        val mapFile = File(getExternalFilesDir(null), "map_full.png")
        if (mapFile.exists()) {
            fullMapBitmap = BitmapFactory.decodeFile(mapFile.absolutePath)
            Log.d(TAG, "地图已加载: ${fullMapBitmap?.width}x${fullMapBitmap?.height}")
        } else {
            Log.w(TAG, "未找到地图文件: ${mapFile.absolutePath}")
        }
    }

    private fun createWindow() {
        val sizePx = dpToPx(WINDOW_SIZE_DP)

        floatingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 15, 15, 30))
            setPadding(4, 4, 4, 4)
        }

        mapImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(30, 30, 50))
            setImageResource(android.R.drawable.ic_dialog_map)
        }

        positionText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            text = "⏳ 等待定位..."
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, dpToPx(4), 0, dpToPx(4))
        }

        (floatingView as LinearLayout).addView(mapImageView)
        (floatingView as LinearLayout).addView(positionText)

        val params = WindowManager.LayoutParams(
            sizePx + dpToPx(8),
            sizePx + dpToPx(32),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(8)
            y = dpToPx(100)
        }

        try {
            windowManager?.addView(floatingView, params)
            enableDrag(params)
        } catch (e: Exception) {
            Log.e(TAG, "创建悬浮窗失败", e)
            stopSelf()
        }
    }

    private fun enableDrag(params: WindowManager.LayoutParams) {
        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - initTouchX).toInt()
                    params.y = initY + (event.rawY - initTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 更新悬浮窗：裁剪当前位置附近区域 + 画标记
     */
    private fun updateOverlay() {
        val map = fullMapBitmap ?: run {
            positionText?.text = "⚠️ 未加载地图文件"
            return
        }

        val viewSize = dpToPx(WINDOW_SIZE_DP)

        // 裁剪当前坐标附近区域
        val cx = currentX.toInt().coerceIn(viewSize / 2, map.width - viewSize / 2)
        val cy = currentY.toInt().coerceIn(viewSize / 2, map.height - viewSize / 2)
        val srcX = (cx - viewSize / 2).coerceAtLeast(0)
        val srcY = (cy - viewSize / 2).coerceAtLeast(0)
        val cropW = viewSize.coerceAtMost(map.width - srcX)
        val cropH = viewSize.coerceAtMost(map.height - srcY)

        if (cropW <= 0 || cropH <= 0) return

        // ⚠️ 必须创建独立副本，不能直接用 createBitmap 共享原始像素缓冲区
        // 否则画标记会永久污染大地图，导致后续匹配精度持续下降
        val cropped = try {
            val region = Bitmap.createBitmap(map, srcX, srcY, cropW, cropH)
            val copy = region.copy(Bitmap.Config.ARGB_8888, true)
            region.recycle()
            copy
        } catch (e: Exception) {
            Log.e(TAG, "裁剪失败", e)
            return
        }

        // 画标记
        val canvas = Canvas(cropped)
        val mx = (currentX - srcX).toFloat()
        val my = (currentY - srcY).toFloat()

        // 根据置信度选颜色
        val color = when {
            currentConfidence > 0.7f -> Color.GREEN
            currentConfidence > 0.4f -> Color.YELLOW
            else -> Color.RED
        }

        // 外圈
        val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; alpha = 80; style = Paint.Style.FILL
        }
        canvas.drawCircle(mx, my, 24f, outerPaint)

        // 内圈
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; alpha = 200; style = Paint.Style.FILL
        }
        canvas.drawCircle(mx, my, 10f, innerPaint)

        // 白色边框
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
        }
        canvas.drawCircle(mx, my, 10f, borderPaint)

        // 朝向箭头
        if (currentConfidence > 0.3f) {
            val arrowLen = 30f
            val rad = Math.toRadians(currentRotation)
            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(mx, my,
                mx + (arrowLen * Math.sin(rad)).toFloat(),
                my - (arrowLen * Math.cos(rad)).toFloat(),
                arrowPaint)
        }

        mapImageView?.setImageBitmap(cropped)

        val confStr = "${(currentConfidence * 100).toInt()}%"
        positionText?.text = "📍 (${currentX.toInt()}, ${currentY.toInt()}) | 置信度: $confStr | 朝向: ${currentRotation.toInt()}°"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "悬浮窗地图",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "游戏地图悬浮窗实时跟点"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("🗺️ 洛克王国地图追踪")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(matchReceiver) } catch (_: Exception) {}
        floatingView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        fullMapBitmap?.recycle()
    }
}
