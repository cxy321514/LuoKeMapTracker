package com.lkwg.maptracker.ui

import android.app.Activity
import android.content.*
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lkwg.maptracker.R
import com.lkwg.maptracker.data.MapRepository
import com.lkwg.maptracker.data.ResourceType
import com.lkwg.maptracker.service.GameDetector
import com.lkwg.maptracker.service.ScreenCaptureService
import com.lkwg.maptracker.util.ConfigManager
import kotlinx.coroutines.launch

/**
 * 主界面
 * 权限管理 / 启停控制 / 参数校准 / 文件选择 / 自动检测 / 资源过滤
 */
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var gameDetector: GameDetector

    // 视图
    private lateinit var tvStatus: TextView
    private lateinit var tvMapInfo: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvRegion: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSelectMap: Button
    private lateinit var btnDownloadMap: Button
    private lateinit var switchAutoDetect: Switch
    private lateinit var switchShowResources: Switch
    private lateinit var llResourceFilters: LinearLayout
    private lateinit var tvGameStatus: TextView

    // 校准
    private lateinit var sbX: SeekBar
    private lateinit var sbY: SeekBar
    private lateinit var sbW: SeekBar
    private lateinit var sbH: SeekBar
    private lateinit var tvCalInfo: TextView

    // 状态
    private var isTracking = false

    // 广播接收
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenCaptureService.ACTION_MATCH_RESULT -> {
                    val x = intent.getDoubleExtra(ScreenCaptureService.EXTRA_POS_X, 0.0)
                    val y = intent.getDoubleExtra(ScreenCaptureService.EXTRA_POS_Y, 0.0)
                    val conf = intent.getFloatExtra(ScreenCaptureService.EXTRA_CONFIDENCE, 0f)
                    tvCoords.text = "📍 坐标: (${x.toInt()}, ${y.toInt()})  置信度: ${(conf * 100).toInt()}%"
                }
                ScreenCaptureService.ACTION_STATUS -> {
                    val msg = intent.getStringExtra(ScreenCaptureService.EXTRA_STATUS_MSG) ?: ""
                    tvStatus.text = "🟡 $msg"
                }
            }
        }
    }

    private val gameReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GameDetector.ACTION_GAME_STARTED -> {
                    tvGameStatus.text = "🎮 游戏运行中"
                    tvGameStatus.setTextColor(Color.GREEN)
                    // 自动开始追踪
                    if (ConfigManager.isAutoDetectEnabled(this@MainActivity) && !isTracking) {
                        autoStartTracking()
                    }
                }
                GameDetector.ACTION_GAME_STOPPED -> {
                    tvGameStatus.text = "⏸ 游戏未运行"
                    tvGameStatus.setTextColor(Color.GRAY)
                }
            }
        }
    }

    // MediaProjection 权限
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
            startFloatingWindow()
            setTrackingState(true)
        } else {
            Toast.makeText(this, "需要屏幕录制权限", Toast.LENGTH_SHORT).show()
        }
    }

    // 悬浮窗权限
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            requestProjection()
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能显示地图", Toast.LENGTH_SHORT).show()
        }
    }

    // 文件选择
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleMapFileSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        gameDetector = GameDetector(this)

        loadSavedConfig()
        setupCalibration()
        setupButtons()
        setupResourceFilters()
        setupGameDetection()

        // 注册广播
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_MATCH_RESULT)
            addAction(ScreenCaptureService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(resultReceiver, filter)
        }

        val gameFilter = IntentFilter().apply {
            addAction(GameDetector.ACTION_GAME_STARTED)
            addAction(GameDetector.ACTION_GAME_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gameReceiver, gameFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gameReceiver, gameFilter)
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        tvCoords = findViewById(R.id.tv_coords)
        tvMapInfo = findViewById(R.id.tv_map_info)
        tvRegion = findViewById(R.id.tv_region)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnSelectMap = findViewById(R.id.btn_select_map)
        btnDownloadMap = findViewById(R.id.btn_download_map)
        switchAutoDetect = findViewById(R.id.switch_auto_detect)
        switchShowResources = findViewById(R.id.switch_show_resources)
        llResourceFilters = findViewById(R.id.ll_resource_filters)
        tvGameStatus = findViewById(R.id.tv_game_status)
        sbX = findViewById(R.id.sb_minimap_x)
        sbY = findViewById(R.id.sb_minimap_y)
        sbW = findViewById(R.id.sb_minimap_w)
        sbH = findViewById(R.id.sb_minimap_h)
        tvCalInfo = findViewById(R.id.tv_calibration_info)
    }

    private fun setupButtons() {
        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                overlayLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
                return@setOnClickListener
            }
            requestProjection()
        }

        btnStop.setOnClickListener {
            stopAll()
            setTrackingState(false)
        }

        btnSelectMap.setOnClickListener {
            filePickerLauncher.launch("image/*")
        }

        btnDownloadMap.setOnClickListener {
            showMapDownloadDialog()
        }

        switchAutoDetect.setOnCheckedChangeListener { _, isChecked ->
            ConfigManager.setAutoDetectEnabled(this, isChecked)
            if (isChecked) {
                gameDetector.startMonitoring()
            } else {
                gameDetector.stopMonitoring()
                tvGameStatus.text = "⏸ 自动检测已关闭"
                tvGameStatus.setTextColor(Color.GRAY)
            }
        }

        switchShowResources.setOnCheckedChangeListener { _, isChecked ->
            ConfigManager.setShowResourcesEnabled(this, isChecked)
            llResourceFilters.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }

    private fun setupCalibration() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                saveCalibration()
                updateCalInfo()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        sbX.setOnSeekBarChangeListener(listener)
        sbY.setOnSeekBarChangeListener(listener)
        sbW.setOnSeekBarChangeListener(listener)
        sbH.setOnSeekBarChangeListener(listener)
    }

    /**
     * 设置资源类型过滤复选框
     */
    private fun setupResourceFilters() {
        llResourceFilters.removeAllViews()
        val enabledTypes = ConfigManager.getEnabledResourceTypes(this)

        for (type in ResourceType.values()) {
            val cb = CheckBox(this).apply {
                text = "${type.label}"
                setTextColor(Color.WHITE)
                isChecked = enabledTypes.contains(type.name)
                setOnCheckedChangeListener { _, isChecked ->
                    val current = ConfigManager.getEnabledResourceTypes(this@MainActivity).toMutableSet()
                    if (isChecked) current.add(type.name) else current.remove(type.name)
                    ConfigManager.setEnabledResourceTypes(this@MainActivity, current)
                }
            }
            llResourceFilters.addView(cb)
        }
    }

    /**
     * 设置游戏自动检测
     */
    private fun setupGameDetection() {
        gameDetector.onGameStateChanged = { isRunning ->
            if (isRunning) {
                tvGameStatus.text = "🎮 游戏运行中"
                tvGameStatus.setTextColor(Color.GREEN)
            } else {
                tvGameStatus.text = "⏸ 游戏未运行"
                tvGameStatus.setTextColor(Color.GRAY)
            }
        }

        if (ConfigManager.isAutoDetectEnabled(this)) {
            gameDetector.startMonitoring()
        }
    }

    /**
     * 游戏启动时自动开始追踪
     */
    private fun autoStartTracking() {
        if (isTracking) return
        if (!MapRepository.hasMap(this)) {
            Toast.makeText(this, "⚠️ 未加载地图，无法自动追踪", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startFloatingWindow() {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAll() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, FloatingWindowService::class.java))
    }

    private fun setTrackingState(running: Boolean) {
        isTracking = running
        if (running) {
            tvStatus.text = "🟢 运行中"
            tvStatus.setTextColor(Color.GREEN)
            btnStart.isEnabled = false
            btnStop.isEnabled = true
        } else {
            tvStatus.text = "🔴 已停止"
            tvStatus.setTextColor(Color.RED)
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    /**
     * 显示地图下载对话框
     */
    private fun showMapDownloadDialog() {
        val input = EditText(this).apply {
            hint = "输入地图图片 URL"
            setText(ConfigManager.getMapDownloadUrl(this@MainActivity))
            setPadding(40, 20, 40, 20)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("🌐 下载大地图")
            .setMessage("输入地图图片的下载地址\n\n💡 也可以点击「选择大地图文件」从本地导入")
            .setView(input)
            .setPositiveButton("下载") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    downloadMap(url)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadMap(url: String) {
        tvMapInfo.text = "⏳ 正在下载地图..."
        lifecycleScope.launch {
            val bitmap = MapRepository.downloadMap(this@MainActivity, url)
            if (bitmap != null) {
                tvMapInfo.text = "✅ 地图: ${bitmap.width}x${bitmap.height}"
                Toast.makeText(this@MainActivity, "地图下载成功！", Toast.LENGTH_SHORT).show()
            } else {
                tvMapInfo.text = "❌ 下载失败"
                Toast.makeText(this@MainActivity, "下载失败，请检查 URL", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleMapFileSelected(uri: Uri) {
        try {
            val destFile = java.io.File(getExternalFilesDir(null), "map_full.png")
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            ConfigManager.setMapFilePath(this, destFile.absolutePath)

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(destFile.absolutePath, opts)
            tvMapInfo.text = "✅ 地图: ${opts.outWidth}x${opts.outHeight}"
            Toast.makeText(this, "大地图已加载，请重新启动追踪", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSavedConfig() {
        val rect = ConfigManager.getMinimapRect(this)
        sbX.progress = rect[0]
        sbY.progress = rect[1]
        sbW.progress = rect[2]
        sbH.progress = rect[3]
        updateCalInfo()

        // 检查地图
        val mapSize = MapRepository.getMapSize(this)
        if (mapSize != null) {
            tvMapInfo.text = "✅ 地图: ${mapSize.first}x${mapSize.second}"
        } else {
            tvMapInfo.text = "⚠️ 未选择大地图"
        }

        switchAutoDetect.isChecked = ConfigManager.isAutoDetectEnabled(this)
        switchShowResources.isChecked = ConfigManager.isShowResourcesEnabled(this)
        llResourceFilters.visibility =
            if (ConfigManager.isShowResourcesEnabled(this)) View.VISIBLE else View.GONE

        setTrackingState(false)
    }

    private fun saveCalibration() {
        ConfigManager.setMinimapRect(
            this,
            sbX.progress,
            sbY.progress,
            sbW.progress.coerceAtLeast(50),
            sbH.progress.coerceAtLeast(50)
        )
    }

    private fun updateCalInfo() {
        tvCalInfo.text = "小地图区域: (${sbX.progress}, ${sbY.progress}) " +
                "${sbW.progress.coerceAtLeast(50)}x${sbH.progress.coerceAtLeast(50)}"
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(gameReceiver) } catch (_: Exception) {}
        gameDetector.destroy()
    }
}
