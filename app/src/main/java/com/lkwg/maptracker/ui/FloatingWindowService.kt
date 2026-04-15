package com.lkwg.maptracker.ui

import android.app.*
import android.content.*
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.view.animation.*
import android.widget.*
import com.lkwg.maptracker.R
import com.lkwg.maptracker.data.*
import com.lkwg.maptracker.service.ScreenCaptureService
import com.lkwg.maptracker.util.ConfigManager
import java.io.File
import kotlin.math.*

/**
 * 可展开/收起的悬浮窗服务
 *
 * 功能：
 * - 展开模式：显示大地图 + 位置标注 + 资源点
 * - 收起模式：仅显示 mini 指南针 + 坐标
 * - 可拖动
 * - 双击切换展开/收起
 * - 资源点叠加显示
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindow"
        private const val CHANNEL_ID = "map_tracker_overlay"
        private const val NOTIFICATION_ID = 2

        private const val EXPANDED_SIZE_DP = 320
        private const val MINI_SIZE_DP = 56
        private const val COMPASS_SIZE_DP = 48
    }

    private var windowManager: WindowManager? = null

    // 展开视图
    private var expandedView: View? = null
    private var mapImageView: ImageView? = null
    private var positionText: TextView? = null
    private var regionText: TextView? = null
    private var resourceToggle: ImageButton? = null
    private var collapseBtn: ImageButton? = null
    private var resourceLegendLayout: LinearLayout? = null

    // 收起视图 (mini)
    private var miniView: View? = null
    private var miniCoordsText: TextView? = null
    private var miniCompass: ImageView? = null
    private var miniConfidenceDot: View? = null

    private var fullMapBitmap: Bitmap? = null
    private var resources: List<GameResource> = emptyList()
    private var isExpanded = true
    private var showResources = true

    // 定位状态
    private var currentX = 0.0
    private var currentY = 0.0
    private var currentConfidence = 0f
    private var currentRotation = 0.0

    private var expandedParams: WindowManager.LayoutParams? = null
    private var miniParams: WindowManager.LayoutParams? = null

    // 最后一次双击时间
    private var lastTapTime = 0L

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
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("🗺️ 悬浮窗已启动"))

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter(ScreenCaptureService.ACTION_MATCH_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(matchReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(matchReceiver, filter)
        }

        // 加载地图和资源
        loadMap()
        resources = MapRepository.loadResources(this)
        showResources = ConfigManager.isShowResourcesEnabled(this)
        isExpanded = ConfigManager.isFloatingExpanded(this)

        createExpandedView()
        createMiniView()

        if (isExpanded) {
            showExpanded()
        } else {
            showMini()
        }
    }

    // ─── 地图加载 ──────────────────────────────────────

    private fun loadMap() {
        val mapFile = File(getExternalFilesDir(null), "map_full.png")
        if (mapFile.exists()) {
            fullMapBitmap = BitmapFactory.decodeFile(mapFile.absolutePath)
            Log.d(TAG, "地图已加载: ${fullMapBitmap?.width}x${fullMapBitmap?.height}")
        }
    }

    // ─── 展开视图 ──────────────────────────────────────

    private fun createExpandedView() {
        val sizePx = dpToPx(EXPANDED_SIZE_DP)
        val headerHeight = dpToPx(36)
        val footerHeight = dpToPx(28)

        expandedView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 15, 15, 30))
            setPadding(2, 2, 2, 2)
            elevation = dpToPx(8).toFloat()
        }

        // 标题栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(200, 25, 25, 50))
            setPadding(dpToPx(8), 0, dpToPx(4), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headerHeight)
        }

        val titleText = TextView(this).apply {
            text = "🗺️ 地图追踪"
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // 资源点开关
        resourceToggle = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_myplaces)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(if (showResources) Color.GREEN else Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
            setOnClickListener {
                showResources = !showResources
                ConfigManager.setShowResourcesEnabled(this@FloatingWindowService, showResources)
                setColorFilter(if (showResources) Color.GREEN else Color.GRAY)
                updateOverlay()
            }
        }

        // 收起按钮
        collapseBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
            setOnClickListener { collapse() }
        }

        header.addView(titleText)
        header.addView(resourceToggle)
        header.addView(collapseBtn)

        // 地图显示区域
        mapImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(20, 20, 40))
            setImageResource(android.R.drawable.ic_dialog_map)
        }

        // 底部信息栏
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 25, 25, 50))
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, footerHeight)
        }

        positionText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            text = "⏳ 等待定位..."
        }

        regionText = TextView(this).apply {
            setTextColor(Color.rgb(150, 180, 255))
            textSize = 9f
            text = ""
        }

        footer.addView(positionText)
        footer.addView(regionText)

        (expandedView as LinearLayout).addView(header)
        (expandedView as LinearLayout).addView(mapImageView)
        (expandedView as LinearLayout).addView(footer)

        expandedParams = WindowManager.LayoutParams(
            sizePx + dpToPx(4),
            sizePx + headerHeight + footerHeight + dpToPx(4),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(8)
            y = dpToPx(80)
        }

        enableDrag(expandedView!!, expandedParams!!)
        enableDoubleTap(expandedView!!)
    }

    // ─── 收起视图 (mini) ──────────────────────────────

    private fun createMiniView() {
        val miniSize = dpToPx(MINI_SIZE_DP)

        miniView = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(210, 15, 15, 35))
            elevation = dpToPx(12).toFloat()
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        }

        // 圆形背景
        val circleBg = View(this).apply {
            setBackgroundColor(Color.argb(100, 40, 40, 80))
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(COMPASS_SIZE_DP), dpToPx(COMPASS_SIZE_DP)
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        (miniView as FrameLayout).addView(circleBg)

        // 迷你指南针
        miniCompass = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setColorFilter(Color.WHITE)
            alpha = 0.8f
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(COMPASS_SIZE_DP), dpToPx(COMPASS_SIZE_DP)
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        (miniView as FrameLayout).addView(miniCompass)

        // 置信度指示点
        miniConfidenceDot = View(this).apply {
            setBackgroundColor(Color.RED)
            layoutParams = FrameLayout.LayoutParams(dpToPx(8), dpToPx(8)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpToPx(6)
                rightMargin = dpToPx(6)
            }
        }
        (miniView as FrameLayout).addView(miniConfidenceDot)

        // 坐标文字
        miniCoordsText = TextView(this).apply {
            setTextColor(Color.rgb(180, 200, 255))
            textSize = 7f
            gravity = Gravity.CENTER
            text = "..."
            layoutParams = FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dpToPx(1)
            }
        }
        (miniView as FrameLayout).addView(miniCoordsText)

        miniParams = WindowManager.LayoutParams(
            miniSize, miniSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(8)
            y = dpToPx(80)
        }

        enableDrag(miniView!!, miniParams!!)
        enableDoubleTap(miniView!!)
    }

    // ─── 展开/收起切换 ─────────────────────────────────

    private fun showExpanded() {
        try {
            miniView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}

        if (expandedView?.parent == null) {
            try {
                windowManager?.addView(expandedView, expandedParams)
            } catch (e: Exception) {
                Log.e(TAG, "添加展开视图失败", e)
            }
        }

        // 动画
        expandedView?.let { view ->
            view.alpha = 0f
            view.scaleX = 0.8f
            view.scaleY = 0.8f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        isExpanded = true
        ConfigManager.setFloatingExpanded(this, true)
    }

    private fun showMini() {
        try {
            expandedView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}

        if (miniView?.parent == null) {
            try {
                windowManager?.addView(miniView, miniParams)
            } catch (e: Exception) {
                Log.e(TAG, "添加 mini 视图失败", e)
            }
        }

        // 动画
        miniView?.let { view ->
            view.alpha = 0f
            view.scaleX = 0.5f
            view.scaleY = 0.5f
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setInterpolator(OvershootInterpolator())
                .start()
        }

        isExpanded = false
        ConfigManager.setFloatingExpanded(this, false)
    }

    private fun expand() = showExpanded()
    private fun collapse() = showMini()

    private fun toggleExpand() {
        if (isExpanded) collapse() else expand()
    }

    // ─── 拖动支持 ──────────────────────────────────────

    private fun enableDrag(view: View, params: WindowManager.LayoutParams) {
        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    initTouchX = event.rawX; initTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - initTouchX).toInt()
                    params.y = initY + (event.rawY - initTouchY).toInt()
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun enableDoubleTap(view: View) {
        view.setOnClickListener(null) // will be set via touch
        // Using GestureDetector for double-tap
        val gestureDetector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    toggleExpand()
                    return true
                }
            }
        )

        // Combine with existing touch listener for drag
        var initX = 0; var initY = 0
        var initTouchX = 0f; var initTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = (if (v == expandedView) expandedParams else miniParams)?.x ?: 0
                    initY = (if (v == expandedView) expandedParams else miniParams)?.y ?: 0
                    initTouchX = event.rawX; initTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initTouchX
                    val dy = event.rawY - initTouchY
                    if (abs(dx) > 5 || abs(dy) > 5) isDragging = true
                    if (isDragging) {
                        val p = if (v == expandedView) expandedParams else miniParams
                        p?.let {
                            it.x = initX + dx.toInt()
                            it.y = initY + dy.toInt()
                            try { windowManager?.updateViewLayout(v, it) } catch (_: Exception) {}
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ─── 叠加更新 ──────────────────────────────────────

    private fun updateOverlay() {
        val map = fullMapBitmap

        if (isExpanded) {
            updateExpandedView(map)
        }
        updateMiniView(map)
    }

    private fun updateExpandedView(map: Bitmap?) {
        if (map == null) {
            positionText?.text = "⚠️ 未加载地图文件"
            return
        }

        val viewSize = dpToPx(EXPANDED_SIZE_DP)

        // 裁剪当前坐标附近区域
        val cx = currentX.toInt().coerceIn(viewSize / 2, map.width - viewSize / 2)
        val cy = currentY.toInt().coerceIn(viewSize / 2, map.height - viewSize / 2)
        val srcX = (cx - viewSize / 2).coerceAtLeast(0)
        val srcY = (cy - viewSize / 2).coerceAtLeast(0)
        val cropW = viewSize.coerceAtMost(map.width - srcX)
        val cropH = viewSize.coerceAtMost(map.height - srcY)

        if (cropW <= 0 || cropH <= 0) return

        val cropped = try {
            val region = Bitmap.createBitmap(map, srcX, srcY, cropW, cropH)
            val copy = region.copy(Bitmap.Config.ARGB_8888, true)
            region.recycle()
            copy
        } catch (e: Exception) {
            Log.e(TAG, "裁剪失败", e)
            return
        }

        val canvas = Canvas(cropped)

        // 绘制资源点
        if (showResources) {
            drawResources(canvas, srcX, srcY, cropW, cropH)
        }

        // 绘制玩家位置标记
        drawPlayerMarker(canvas, currentX - srcX, currentY - srcY)

        mapImageView?.setImageBitmap(cropped)

        // 更新文字
        val confStr = "${(currentConfidence * 100).toInt()}%"
        positionText?.text = "📍 (${currentX.toInt()}, ${currentY.toInt()}) | $confStr | ${currentRotation.toInt()}°"

        // 所在区域名称
        val regionName = findCurrentRegion(currentX.toInt(), currentY.toInt())
        regionText?.text = if (regionName.isNotEmpty()) "🏔️ $regionName" else ""
    }

    private fun updateMiniView(map: Bitmap?) {
        miniCoordsText?.text = if (map != null) {
            "(${currentX.toInt()},${currentY.toInt()})"
        } else {
            "..."
        }

        // 更新置信度指示点颜色
        val color = when {
            currentConfidence > 0.7f -> Color.GREEN
            currentConfidence > 0.4f -> Color.YELLOW
            currentConfidence > 0.1f -> Color.RED
            else -> Color.GRAY
        }
        miniConfidenceDot?.setBackgroundColor(color)

        // 指南针旋转
        miniCompass?.rotation = currentRotation.toFloat()
    }

    /**
     * 在裁剪区域上绘制资源点
     */
    private fun drawResources(canvas: Canvas, srcX: Int, srcY: Int, cropW: Int, cropH: Int) {
        val enabledTypes = ConfigManager.getEnabledResourceTypes(this)

        for (res in resources) {
            if (!enabledTypes.contains(res.type.name)) continue

            // 资源点是否在裁剪区域内
            val rx = res.x - srcX
            val ry = res.y - srcY
            if (rx < -20 || rx > cropW + 20 || ry < -20 || ry > cropH + 20) continue

            // 绘制资源点标记
            val color = Color.parseColor(res.type.colorHex)
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                alpha = 180
                style = Paint.Style.FILL
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }

            val dotRadius = 6f
            canvas.drawCircle(rx.toFloat(), ry.toFloat(), dotRadius, dotPaint)
            canvas.drawCircle(rx.toFloat(), ry.toFloat(), dotRadius, borderPaint)

            // 名称标签
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textSize = 9f
                setShadowLayer(2f, 1f, 1f, Color.BLACK)
            }
            canvas.drawText(res.name, rx + 10f, ry + 3f, textPaint)
        }
    }

    /**
     * 绘制玩家位置标记（带朝向箭头）
     */
    private fun drawPlayerMarker(canvas: Canvas, mx: Double, my: Double) {
        val color = when {
            currentConfidence > 0.7f -> Color.GREEN
            currentConfidence > 0.4f -> Color.YELLOW
            else -> Color.RED
        }

        val mxf = mx.toFloat()
        val myf = my.toFloat()

        // 外发光圈
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; alpha = 60; style = Paint.Style.FILL
        }
        canvas.drawCircle(mxf, myf, 28f, glowPaint)

        // 内实心圆
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; alpha = 220; style = Paint.Style.FILL
        }
        canvas.drawCircle(mxf, myf, 10f, fillPaint)

        // 白色边框
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
        }
        canvas.drawCircle(mxf, myf, 10f, borderPaint)

        // 朝向箭头
        if (currentConfidence > 0.3f) {
            val arrowLen = 32f
            val rad = Math.toRadians(currentRotation)
            val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
                strokeCap = Paint.Cap.ROUND
            }
            val endX = mxf + (arrowLen * sin(rad)).toFloat()
            val endY = myf - (arrowLen * cos(rad)).toFloat()
            canvas.drawLine(mxf, myf, endX, endY, arrowPaint)

            // 箭头头部
            val headLen = 8f
            val headAngle = 0.5
            val a1x = endX - (headLen * sin(rad - headAngle)).toFloat()
            val a1y = endY + (headLen * cos(rad - headAngle)).toFloat()
            val a2x = endX - (headLen * sin(rad + headAngle)).toFloat()
            val a2y = endY + (headLen * cos(rad + headAngle)).toFloat()
            canvas.drawLine(endX, endY, a1x, a1y, arrowPaint)
            canvas.drawLine(endX, endY, a2x, a2y, arrowPaint)
        }
    }

    /**
     * 查找当前所在区域
     */
    private fun findCurrentRegion(x: Int, y: Int): String {
        // 简单区域匹配
        val regions = listOf(
            Triple("魔法学院", 1200, 800),
            Triple("宠物园", 800, 1200),
            Triple("维苏威火山", 2000, 600),
            Triple("拉布朗矿山", 1800, 1400),
            Triple("天空城", 1500, 400),
            Triple("人鱼湾", 600, 1600),
            Triple("雪人谷", 2200, 300),
            Triple("云烟桃源", 400, 1000),
        )
        var minDist = Double.MAX_VALUE
        var nearest = ""
        for ((name, cx, cy) in regions) {
            val dist = hypot((x - cx).toDouble(), (y - cy).toDouble())
            if (dist < minDist && dist < 300) {
                minDist = dist
                nearest = name
            }
        }
        return nearest
    }

    // ─── 通知 ──────────────────────────────────────────

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
        expandedView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        miniView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        fullMapBitmap?.recycle()
    }
}
