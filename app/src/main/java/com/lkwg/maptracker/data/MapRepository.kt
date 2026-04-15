package com.lkwg.maptracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * 地图数据仓库
 * - 自动加载 / 下载 / 生成大地图
 * - 加载资源点数据
 * - 缓存管理
 */
object MapRepository {

    private const val TAG = "MapRepository"
    private const val MAP_FILE = "map_full.png"
    private const val RESOURCES_FILE = "game_resources.json"

    /**
     * 加载大地图（优先用户文件 > 缓存 > assets > 自动生成）
     */
    suspend fun loadMap(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        // 1. 用户手动选择的文件
        val configPath = ConfigManager.getMapFilePath(context)
        if (configPath.isNotEmpty() && File(configPath).exists()) {
            Log.d(TAG, "从用户配置路径加载: $configPath")
            return@withContext BitmapFactory.decodeFile(configPath)
        }

        // 2. 外部存储缓存
        val cachedFile = File(context.getExternalFilesDir(null), MAP_FILE)
        if (cachedFile.exists()) {
            Log.d(TAG, "从缓存加载: ${cachedFile.absolutePath}")
            return@withContext BitmapFactory.decodeFile(cachedFile.absolutePath)
        }

        // 3. assets 内置
        try {
            val stream = context.assets.open(MAP_FILE)
            val bitmap = BitmapFactory.decodeStream(stream)
            stream.close()
            if (bitmap != null) {
                Log.d(TAG, "从 assets 加载: ${bitmap.width}x${bitmap.height}")
                cacheBitmap(context, bitmap)
                return@withContext bitmap
            }
        } catch (_: Exception) {}

        // 4. 自动生成占位地图
        Log.d(TAG, "未找到地图，自动生成...")
        val generated = MapGenerator.generate()
        cacheBitmap(context, generated)
        return@withContext generated
    }

    /**
     * 确保有地图可用，没有就自动生成
     */
    suspend fun ensureMap(context: Context): Bitmap? {
        if (hasMap(context)) return loadMap(context)
        Log.d(TAG, "自动生成地图...")
        val generated = MapGenerator.generate()
        cacheBitmap(context, generated)
        return generated
    }

    /**
     * 从指定 URL 下载并缓存地图
     */
    suspend fun downloadMap(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doInput = true
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "下载失败: HTTP ${conn.responseCode}")
                return@withContext null
            }

            val bitmap = BitmapFactory.decodeStream(conn.inputStream)
            conn.inputStream.close()
            conn.disconnect()

            if (bitmap != null) {
                cacheBitmap(context, bitmap)
                ConfigManager.setMapDownloadUrl(context, url)
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "下载异常", e)
            null
        }
    }

    private fun cacheBitmap(context: Context, bitmap: Bitmap) {
        try {
            val file = File(context.getExternalFilesDir(null), MAP_FILE)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "已缓存: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "缓存失败", e)
        }
    }

    /**
     * 检查地图是否可用
     */
    fun hasMap(context: Context): Boolean {
        val configPath = ConfigManager.getMapFilePath(context)
        if (configPath.isNotEmpty() && File(configPath).exists()) return true
        val cached = File(context.getExternalFilesDir(null), MAP_FILE)
        if (cached.exists()) return true
        try {
            context.assets.open(MAP_FILE).close()
            return true
        } catch (_: Exception) {}
        return false
    }

    /**
     * 获取地图尺寸（不加载完整位图）
     */
    fun getMapSize(context: Context): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val configPath = ConfigManager.getMapFilePath(context)
        if (configPath.isNotEmpty() && File(configPath).exists()) {
            BitmapFactory.decodeFile(configPath, opts)
        } else {
            val cached = File(context.getExternalFilesDir(null), MAP_FILE)
            if (cached.exists()) {
                BitmapFactory.decodeFile(cached.absolutePath, opts)
            }
        }
        return if (opts.outWidth > 0) Pair(opts.outWidth, opts.outHeight) else null
    }

    /**
     * 加载资源点数据
     */
    fun loadResources(context: Context): List<GameResource> {
        val resources = mutableListOf<GameResource>()
        try {
            val json = context.assets.open(RESOURCES_FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val arr = root.getJSONArray("resources")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = try {
                    ResourceType.valueOf(obj.getString("type"))
                } catch (_: Exception) {
                    ResourceType.LANDMARK
                }
                resources.add(
                    GameResource(
                        id = obj.optString("id", "r$i"),
                        name = obj.getString("name"),
                        type = type,
                        x = obj.getInt("x"),
                        y = obj.getInt("y"),
                        description = obj.optString("description", ""),
                        icon = obj.optString("icon", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载资源数据失败: ${e.message}")
        }
        return resources
    }

    /**
     * 加载地图区域定义
     */
    fun loadRegions(context: Context): List<MapRegion> {
        val regions = mutableListOf<MapRegion>()
        try {
            val json = context.assets.open(RESOURCES_FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            if (root.has("regions")) {
                val arr = root.getJSONArray("regions")
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    regions.add(
                        MapRegion(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            centerX = obj.getInt("centerX"),
                            centerY = obj.getInt("centerY"),
                            width = obj.getInt("width"),
                            height = obj.getInt("height")
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return regions
    }
}
