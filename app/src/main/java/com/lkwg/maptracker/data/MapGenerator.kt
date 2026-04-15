package com.lkwg.maptracker.data

import android.graphics.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 地图生成器
 * 首次启动无地图时，自动生成一张带区域标记的占位地图
 */
object MapGenerator {

    private const val TAG = "MapGenerator"

    // 地图尺寸
    private const val MAP_W = 2600
    private const val MAP_H = 1900

    /**
     * 生成默认地图 Bitmap
     */
    fun generate(): Bitmap {
        val bmp = Bitmap.createBitmap(MAP_W, MAP_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // ── 背景：草地基底 ──
        canvas.drawColor(Color.rgb(76, 140, 70))

        // ── 绘制地形 ──
        drawTerrain(canvas)

        // ── 网格线 ──
        drawGrid(canvas)

        // ── 区域着色 ──
        drawRegions(canvas)

        // ── 道路 ──
        drawRoads(canvas)

        // ── 区域名称 ──
        drawLabels(canvas)

        // ── 边框 ──
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 80, 35)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(2f, 2f, MAP_W - 2f, MAP_H - 2f, borderPaint)

        Log.d(TAG, "地图生成完成: ${MAP_W}x${MAP_H}")
        return bmp
    }

    private fun drawTerrain(canvas: Canvas) {
        // 海洋/水域 - 人鱼湾
        val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 165, 245); alpha = 120
        }
        canvas.drawCircle(600f, 1600f, 250f, waterPaint)
        // 河流
        val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 181, 246); style = Paint.Style.STROKE
            strokeWidth = 18f; strokeCap = Paint.Cap.ROUND
        }
        val river = Path().apply {
            moveTo(600f, 1750f); cubicTo(700f, 1500f, 900f, 1300f, 1100f, 1100f)
            cubicTo(1300f, 900f, 1400f, 700f, 1500f, 500f)
        }
        canvas.drawPath(river, riverPaint)

        // 火山区域 - 红色岩浆色
        val lavaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(211, 47, 47); alpha = 80
        }
        canvas.drawCircle(2000f, 600f, 280f, lavaPaint)
        val volcanoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(183, 28, 28)
        }
        // 火山三角
        val volcano = Path().apply {
            moveTo(1950f, 500f); lineTo(2050f, 500f); lineTo(2000f, 400f); close()
        }
        canvas.drawPath(volcano, volcanoPaint)

        // 雪山区域 - 白色
        val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(224, 247, 250); alpha = 140
        }
        canvas.drawCircle(2200f, 300f, 220f, snowPaint)
        // 雪山
        for (i in 0..3) {
            val sx = 2100f + i * 70f
            val peak = Path().apply {
                moveTo(sx - 30f, 330f); lineTo(sx, 250f - i * 15f); lineTo(sx + 30f, 330f); close()
            }
            canvas.drawPath(peak, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(200, 230, 240)
            })
        }

        // 矿山区域 - 棕色
        val minePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(121, 85, 72); alpha = 100
        }
        canvas.drawCircle(1800f, 1400f, 200f, minePaint)

        // 天空城 - 云朵色
        val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(200, 220, 255); alpha = 100
        }
        canvas.drawCircle(1500f, 400f, 180f, skyPaint)
        // 云朵
        for (cx in listOf(1400, 1500, 1600)) {
            canvas.drawCircle(cx.toFloat(), 350f + (cx % 100), 35f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 240, 255); alpha = 180 })
        }

        // 桃源 - 粉色花树区
        val peachPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(248, 187, 208); alpha = 90
        }
        canvas.drawCircle(400f, 1000f, 220f, peachPaint)
        // 桃花树
        val treePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(233, 30, 99); alpha = 150
        }
        for (i in 0..5) {
            canvas.drawCircle(
                300f + (i * 40) + (i % 2) * 20,
                900f + i * 30f,
                20f + i * 3f, treePaint
            )
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(50, 100, 45); alpha = 50
            style = Paint.Style.STROKE; strokeWidth = 0.8f
        }
        // 竖线
        var x = 100f
        while (x < MAP_W) {
            canvas.drawLine(x, 0f, x, MAP_H.toFloat(), gridPaint)
            x += 100f
        }
        // 横线
        var y = 100f
        while (y < MAP_H) {
            canvas.drawLine(0f, y, MAP_W.toFloat(), y, gridPaint)
            y += 100f
        }
    }

    private fun drawRegions(canvas: Canvas) {
        // 魔法学院 - 中心深绿色
        val academyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(46, 125, 50); alpha = 80
        }
        canvas.drawRoundRect(1000f, 600f, 1500f, 1000f, 30f, 30f, academyPaint)

        // 宠物园 - 黄绿色
        val petPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(156, 204, 101); alpha = 80
        }
        canvas.drawRoundRect(650f, 1050f, 950f, 1350f, 25f, 25f, petPaint)
    }

    private fun drawRoads(canvas: Canvas) {
        val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(161, 136, 127); style = Paint.Style.STROKE
            strokeWidth = 10f; strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        }
        // 主干道 - 连接各区域
        val mainRoad = Path().apply {
            moveTo(1200f, 800f) // 魔法学院
            lineTo(800f, 1200f) // 宠物园
        }
        canvas.drawPath(mainRoad, roadPaint)

        val road2 = Path().apply {
            moveTo(1200f, 800f)
            lineTo(2000f, 600f) // 火山
        }
        canvas.drawPath(road2, roadPaint)

        val road3 = Path().apply {
            moveTo(1200f, 800f)
            lineTo(1800f, 1400f) // 矿山
        }
        canvas.drawPath(road3, roadPaint)

        val road4 = Path().apply {
            moveTo(1200f, 800f)
            lineTo(1500f, 400f) // 天空城
        }
        canvas.drawPath(road4, roadPaint)

        val road5 = Path().apply {
            moveTo(800f, 1200f)
            lineTo(600f, 1600f) // 人鱼湾
        }
        canvas.drawPath(road5, roadPaint)

        val road6 = Path().apply {
            moveTo(2000f, 600f)
            lineTo(2200f, 300f) // 雪人谷
        }
        canvas.drawPath(road6, roadPaint)

        val road7 = Path().apply {
            moveTo(800f, 1200f)
            lineTo(400f, 1000f) // 桃源
        }
        canvas.drawPath(road7, roadPaint)
    }

    private fun drawLabels(canvas: Canvas) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 36f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 220, 220); textSize = 24f
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        // 区域名称
        canvas.drawText("🏰 魔法学院", 1100f, 790f, labelPaint)
        canvas.drawText("🌳 宠物园", 720f, 1190f, labelPaint)
        canvas.drawText("🌋 维苏威火山", 1880f, 590f, labelPaint)
        canvas.drawText("⛏️ 拉布朗矿山", 1680f, 1390f, labelPaint)
        canvas.drawText("☁️ 天空城", 1400f, 390f, labelPaint)
        canvas.drawText("🐚 人鱼湾", 520f, 1590f, labelPaint)
        canvas.drawText("❄️ 雪人谷", 2100f, 290f, labelPaint)
        canvas.drawText("🌸 云烟桃源", 310f, 990f, labelPaint)

        // 坐标标注
        canvas.drawText("0", 8f, 20f, smallPaint)
        canvas.drawText("${MAP_W}", (MAP_W - 80).toFloat(), 20f, smallPaint)
        canvas.drawText("0", 8f, 40f, smallPaint)
    }
}
