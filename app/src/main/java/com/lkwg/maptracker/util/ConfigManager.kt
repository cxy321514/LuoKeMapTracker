package com.lkwg.maptracker.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器 - 持久化存储所有用户配置
 */
object ConfigManager {

    private const val PREF_NAME = "map_tracker_config"

    // 小地图裁剪
    private const val KEY_MINIMAP_X = "minimap_x"
    private const val KEY_MINIMAP_Y = "minimap_y"
    private const val KEY_MINIMAP_W = "minimap_w"
    private const val KEY_MINIMAP_H = "minimap_h"
    private const val DEFAULT_MINIMAP_X = 0
    private const val DEFAULT_MINIMAP_Y = 0
    private const val DEFAULT_MINIMAP_W = 300
    private const val DEFAULT_MINIMAP_H = 300

    // 匹配参数
    private const val KEY_MATCH_INTERVAL = "match_interval"
    private const val DEFAULT_MATCH_INTERVAL = 200L
    private const val KEY_ORB_FEATURES = "orb_features"
    private const val DEFAULT_ORB_FEATURES = 5000
    private const val KEY_CONFIDENCE_THRESHOLD = "confidence_threshold"
    private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.3f

    // 地图
    private const val KEY_MAP_FILE = "map_file_path"
    private const val KEY_MAP_DOWNLOAD_URL = "map_download_url"

    // 自动检测
    private const val KEY_AUTO_DETECT_GAME = "auto_detect_game"
    private const val KEY_SHOW_RESOURCES = "show_resources"
    private const val KEY_RESOURCE_TYPES = "resource_types"

    // 悬浮窗
    private const val KEY_FLOATING_EXPANDED = "floating_expanded"
    private const val KEY_FLOATING_SIZE = "floating_size"
    private const val DEFAULT_FLOATING_SIZE = 280

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ─── 小地图裁剪 ────────────────────────────────────

    fun getMinimapRect(context: Context): IntArray {
        val p = prefs(context)
        return intArrayOf(
            p.getInt(KEY_MINIMAP_X, DEFAULT_MINIMAP_X),
            p.getInt(KEY_MINIMAP_Y, DEFAULT_MINIMAP_Y),
            p.getInt(KEY_MINIMAP_W, DEFAULT_MINIMAP_W),
            p.getInt(KEY_MINIMAP_H, DEFAULT_MINIMAP_H)
        )
    }

    fun setMinimapRect(context: Context, x: Int, y: Int, w: Int, h: Int) {
        prefs(context).edit()
            .putInt(KEY_MINIMAP_X, x)
            .putInt(KEY_MINIMAP_Y, y)
            .putInt(KEY_MINIMAP_W, w)
            .putInt(KEY_MINIMAP_H, h)
            .apply()
    }

    // ─── 匹配参数 ──────────────────────────────────────

    fun getMatchInterval(context: Context): Long =
        prefs(context).getLong(KEY_MATCH_INTERVAL, DEFAULT_MATCH_INTERVAL)

    fun getOrbFeatures(context: Context): Int =
        prefs(context).getInt(KEY_ORB_FEATURES, DEFAULT_ORB_FEATURES)

    fun getConfidenceThreshold(context: Context): Float =
        prefs(context).getFloat(KEY_CONFIDENCE_THRESHOLD, DEFAULT_CONFIDENCE_THRESHOLD)

    fun setConfidenceThreshold(context: Context, threshold: Float) {
        prefs(context).edit().putFloat(KEY_CONFIDENCE_THRESHOLD, threshold).apply()
    }

    // ─── 地图 ─────────────────────────────────────────

    fun getMapFilePath(context: Context): String =
        prefs(context).getString(KEY_MAP_FILE, "") ?: ""

    fun setMapFilePath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_MAP_FILE, path).apply()
    }

    fun getMapDownloadUrl(context: Context): String =
        prefs(context).getString(KEY_MAP_DOWNLOAD_URL, "") ?: ""

    fun setMapDownloadUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_MAP_DOWNLOAD_URL, url).apply()
    }

    // ─── 自动检测 ─────────────────────────────────────

    fun isAutoDetectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_DETECT_GAME, true)

    fun setAutoDetectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_DETECT_GAME, enabled).apply()
    }

    fun isShowResourcesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_RESOURCES, true)

    fun setShowResourcesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_RESOURCES, enabled).apply()
    }

    fun getEnabledResourceTypes(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_RESOURCE_TYPES, null)
            ?: enumValues<com.lkwg.maptracker.data.ResourceType>().map { it.name }.toSet()

    fun setEnabledResourceTypes(context: Context, types: Set<String>) {
        prefs(context).edit().putStringSet(KEY_RESOURCE_TYPES, types).apply()
    }

    // ─── 悬浮窗 ───────────────────────────────────────

    fun isFloatingExpanded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_EXPANDED, true)

    fun setFloatingExpanded(context: Context, expanded: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_EXPANDED, expanded).apply()
    }

    fun getFloatingSize(context: Context): Int =
        prefs(context).getInt(KEY_FLOATING_SIZE, DEFAULT_FLOATING_SIZE)

    fun setFloatingSize(context: Context, size: Int) {
        prefs(context).edit().putInt(KEY_FLOATING_SIZE, size).apply()
    }
}
