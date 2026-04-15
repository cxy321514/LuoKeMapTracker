package com.lkwg.maptracker.ui

import android.app.*
import android.content.*
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.*
import com.lkwg.maptracker.data.*
import com.lkwg.maptracker.service.ScreenCaptureService
import com.lkwg.maptracker.util.ConfigManager

/**
 * 悬浮窗服务（单视图版）
 *
 * 一个 View 同时处理展开/收起，避免双视图导致的崩溃。
 * - 短按小区域 → 展开大地图
 * - 点击收起按钮 → 缩为 mini 圆形
 * - 双击 → 切换展开/收起
 * - 长按拖拽 → 移动位置
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindow"
        private const val CHANNEL_ID = "map_tracker_overlay"
        private const val NOTIFICATION_ID = 2
    }

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var rootParams: WindowManager.LayoutParams? = null

    // 子视图
    private var expandedContainer: LinearLayout? = null
    private var miniContainer: FrameLayout? = null
    private var mapImageView: ImageView? = null
    private var positionText: TextView? = null
    private var regionText: TextView? = null

    // 数据
    private var fullMapBitmap: Bitmap? = null
    private var resources: List<GameResource> = emptyList()
    private var isExpanded = true
    private var showResources = true

    // 定位状态
    private var currentX = 0.0
    private var currentY = 0.0
    private var currentConfidence = 0f
    private var currentRotation = 0.0

    // 拖拽
    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false

    // 双击检测
    private var lastClickTime = 0L

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

        // 必须先创建通知渠道再 startForeground
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("🗺️ 悬浮窗已启动"))

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 注册广播
        try {
            val filter = IntentFilter(ScreenCaptureService.ACTION_MATCH_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(matchReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(matchReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册广播失败", e)
        }

        // 加载数据
        fullMapBitmap = MapRepository.loadMapSync(this)
        resources = MapRepository.loadResources(this)
        showResources = ConfigManager.isShowResourcesEnabled(this)
        isExpanded = ConfigManager.isFloatingExpanded(this)

        // 创建视图
        createViews()

        // 显示
        showCurrentState()
    }

    /**
     * 同步加载地图（悬浮窗服务不能用协程 suspend）
     */
    private fun MapRepository.loadMapSync(context: Context): Bitmap? {
        // 简单同步加载
        val configPath = ConfigManager.getMapFilePath(context)
        if (configPath.isNotEmpty()) {
            val bmp = android.graphics.BitmapFactory.decodeFile(configPath)
            if (bmp != null) return bmp
        }
        val cached = java.io.File(context.getExternalFilesDir(null), "map_full.png")
        if (cached.exists()) {
            val bmp = android.graphics.BitmapFactory.decodeFile(cached.absolutePath)
            if (bmp != null) return bmp
        }
        // 自动生成
        return MapGenerator.generate()
    }

    // ─── 视图创建 ──────────────────────────────────────

    private fun createViews() {
        val density = resources.displayMetrics.density
        val expandedSize = (320 * density).toInt()
        val miniSize = (60 * density).toInt()
        val headerH = (36 * density).toInt()
        val footerH = (30 * density).toInt()
        val pad4 = (4 * density).toInt()
        val pad8 = (8 * density).toInt()

        // ── 根容器（FrameLayout 包裹两个状态视图）──
        rootView = FrameLayout(this)

        // ── 展开视图 ──
        expandedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 15, 15, 30))
            setPadding(pad4, pad4, pad4, pad4)
        }

        // 标题栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(200, 25, 25, 50))
            setPadding(pad8, 0, pad4, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headerH)
        }
        header.addView(TextView(this).apply {
            text = "🗺️ 地图追踪"
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 资源点开关按钮
        header.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_myplaces)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(if (showResources) Color.GREEN else Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener {
                showResources = !showResources
                ConfigManager.setShowResourcesEnabled(this@FloatingWindowService, showResources)
                setColorFilter(if (showResources) Color.GREEN else Color.GRAY)
                updateOverlay()
            }
        })
        // 收起按钮
        header.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener { collapse() }
        })

        // 地图
        mapImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(expandedSize, expandedSize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(20, 20, 40))
        }

        // 底部信息
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 25, 25, 50))
            setPadding(pad8, pad4 / 2, pad8, pad4 / 2)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, footerH)
        }
        positionText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 10f; text = "⏳ 等待定位..."
        }
        regionText = TextView(this).apply {
            setTextColor(Color.rgb(150, 180, 255)); textSize = 9f; text = ""
        }
        footer.addView(positionText)
        footer.addView(regionText)

        expandedContainer!!.addView(header)
        expandedContainer!!.addView(mapImageView)
        expandedContainer!!.addView(footer)

        // ── 迷你视图 ──
        miniContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(210, 15, 15, 35))
            setPadding(pad4, pad4, pad4, pad4)
        }
        val compassIv = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setColorFilter(Color.WHITE); alpha = 0.9f
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.CENTER
            }
        }
        val miniDot = View(this).apply {
            setBackgroundColor(Color.RED)
            layoutParams = FrameLayout.LayoutParams(dp(8), dp(8)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = pad4; rightMargin = pad4
            }
            tag = "dot"
        }
        val miniCoord = TextView(this).apply {
            setTextColor(Color.rgb(180, 200, 255)); textSize = 7f
            gravity = Gravity.CENTER; text = "..."
            tag = "coord"
            layoutParams = FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM; bottomMargin = dp(2) }
        }
        miniContainer!!.addView(compassIv)
        miniContainer!!.addView(miniDot)
        miniContainer!!.addView(miniCoord)

        // 添加到根容器
        (rootView as FrameLayout).addView(expandedContainer)
        (rootView as FrameLayout).addView(miniContainer)

        // ── Window 参数 ──
        rootParams = WindowManager.LayoutParams(
            expandedSize + dp(8),
            expandedSize + headerH + footerH + dp(8),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = dp(80)
        }

        // ── 触摸事件 ──
        setupTouch()
    }

    /**
     * 触摸处理：拖拽 + 双击切换
     */
    private fun setupTouch() {
        rootView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = rootParams?.x ?: 0
                    dragStartY = rootParams?.y ?: 0
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false

                    // 双击检测
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime < 300) {
                        toggleExpand()
                        lastClickTime = 0
                        return@setOnTouchListener true
                    }
                    lastClickTime = now
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        isDragging = true
                        lastClickTime = 0 // 取消双击
                    }
                    if (isDragging) {
                        rootParams?.let {
                            it.x = dragStartX + dx.toInt()
                            it.y = dragStartY + dy.toInt()
                            try { windowManager.updateViewLayout(view, it) } catch (_: Exception) {}
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ─── 展开/收起 ──────────────────────────────────────

    private fun showCurrentState() {
        if (isExpanded) showExpanded() else showMini()
    }

    private fun showExpanded() {
        val density = resources.displayMetrics.density
        val expandedSize = (320 * density).toInt()
        val headerH = (36 * density).toInt()
        val footerH = (30 * density).toInt()

        expandedContainer?.visibility = View.VISIBLE
        miniContainer?.visibility = View.GONE

        // 更新尺寸
        rootParams?.width = expandedSize + dp(8)
        rootParams?.height = expandedSize + headerH + footerH + dp(8)
        try {
            rootView?.let { windowManager.updateViewLayout(it, rootParams) }
        } catch (_: Exception) {}

        isExpanded = true
        ConfigManager.setFloatingExpanded(this, true)

        // 首次添加
        if (rootView?.parent == null) {
            try {
                windowManager.addView(rootView, rootParams)
            } catch (e: Exception) {
                Log.e(TAG, "添加悬浮窗失败", e)
            }
        }

        updateOverlay()
    }

    private fun showMini() {
        val miniSize = dp(60)

        expandedContainer?.visibility = View.GONE
        miniContainer?.visibility = View.VISIBLE

        rootParams?.width = miniSize
        rootParams?.height = miniSize
        try {
            rootView?.let { windowManager.updateViewLayout(it, rootParams) }
        } catch (_: Exception) {}

        isExpanded = false
        ConfigManager.setFloatingExpanded(this, false)

        if (rootView?.parent == null) {
            try {
                windowManager.addView(rootView, rootParams)
            } catch (e: Exception) {
                Log.e(TAG, "添加悬浮窗失败", e)
            }
        }

        updateMiniView()
    }

    private fun expand() = showExpanded()
    private fun collapse() = showMini()
    private fun toggleExpand() {
        if (isExpanded) collapse() else expand()
    }

    // ─── 叠加更新 ──────────────────────────────────────

    private fun updateOverlay() {
        if (isExpanded) updateExpandedView() else updateMiniView()
    }

    private fun updateExpandedView() {
        val map = fullMapBitmap ?: run {
            positionText?.text = "⚠️ 未加载地图"
            return
        }

        val viewSize = dp(320)

        // 裁剪当前位置附近区域
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

        // 资源点
        if (showResources) drawResources(canvas, srcX, srcY, cropW, cropH)

        // 玩家标记
        drawPlayerMarker(canvas, currentX - srcX, currentY - srcY)

        mapImageView?.setImageBitmap(cropped)

        // 文字
        positionText?.text = "📍 (${currentX.toInt()}, ${currentY.toInt()}) | ${(currentConfidence * 100).toInt()}% | ${currentRotation.toInt()}°"
        val region = findRegion(currentX.toInt(), currentY.toInt())
        regionText?.text = if (region.isNotEmpty()) "🏔️ $region" else ""
    }

    private fun updateMiniView() {
        miniContainer?.findViewWithTag<TextView>("coord")?.text =
            "(${currentX.toInt()},${currentY.toInt()})"

        val color = when {
            currentConfidence > 0.7f -> Color.GREEN
            currentConfidence > 0.4f -> Color.YELLOW
            currentConfidence > 0.1f -> Color.RED
            else -> Color.GRAY
        }
        miniContainer?.findViewWithTag<View>("dot")?.setBackgroundColor(color)
    }

    private fun drawResources(canvas: Canvas, sx: Int, sy: Int, cw: Int, ch: Int) {
        val enabled = ConfigManager.getEnabledResourceTypes(this)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f; color = Color.WHITE
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        for (res in resources) {
            if (!enabled.contains(res.type.name)) continue
            val rx = res.x - sx; val ry = res.y - sy
            if (rx < -20 || rx > cw + 20 || ry < -20 || ry > ch + 20) continue
            val color = Color.parseColor(res.type.colorHex)
            canvas.drawCircle(rx.toFloat(), ry.toFloat(), 6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 180 })
            canvas.drawCircle(rx.toFloat(), ry.toFloat(), 6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f
                })
            canvas.drawText(res.name, rx + 10f, ry + 3f, textPaint)
        }
    }

    private fun drawPlayerMarker(canvas: Canvas, mx: Double, my: Double) {
        val color = when {
            currentConfidence > 0.7f -> Color.GREEN
            currentConfidence > 0.4f -> Color.YELLOW
            else -> Color.RED
        }
        val mxf = mx.toFloat(); val myf = my.toFloat()

        // 外圈
        canvas.drawCircle(mxf, myf, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; alpha = 60
        })
        // 内圈
        canvas.drawCircle(mxf, myf, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; alpha = 220
        })
        // 白框
        canvas.drawCircle(mxf, myf, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
        })
        // 朝向箭头
        if (currentConfidence > 0.3f) {
            val rad = Math.toRadians(currentRotation)
            val len = 32f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(mxf, myf,
                mxf + (len * kotlin.math.sin(rad)).toFloat(),
                myf - (len * kotlin.math.cos(rad)).toFloat(), paint)
        }
    }

    private fun findRegion(x: Int, y: Int): String {
        val regions = listOf(
            Triple("魔法学院", 1200, 800), Triple("宠物园", 800, 1200),
            Triple("维苏威火山", 2000, 600), Triple("拉布朗矿山", 1800, 1400),
            Triple("天空城", 1500, 400), Triple("人鱼湾", 600, 1600),
            Triple("雪人谷", 2200, 300), Triple("云烟桃源", 400, 1000),
        )
        var minDist = Double.MAX_VALUE; var nearest = ""
        for ((name, cx, cy) in regions) {
            val d = kotlin.math.hypot((x - cx).toDouble(), (y - cy).toDouble())
            if (d < minDist && d < 300) { minDist = d; nearest = name }
        }
        return nearest
    }

    // ─── 通知 ──────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "悬浮窗地图",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "地图悬浮窗"; setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        return b.setContentTitle("🗺️ 洛克王国地图追踪")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true).build()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(matchReceiver) } catch (_: Exception) {}
        rootView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        fullMapBitmap?.recycle()
    }
}
