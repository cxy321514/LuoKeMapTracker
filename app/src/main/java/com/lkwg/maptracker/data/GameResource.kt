package com.lkwg.maptracker.data

/**
 * 游戏资源点数据
 */
data class GameResource(
    val id: String,
    val name: String,
    val type: ResourceType,
    val x: Int,
    val y: Int,
    val description: String = "",
    val icon: String = ""  // emoji icon
)

enum class ResourceType(val label: String, val colorHex: String) {
    NPC("NPC", "#FF9800"),
    MONSTER("怪物", "#F44336"),
    COLLECTION("采集点", "#4CAF50"),
    PORTAL("传送点", "#2196F3"),
    DUNGEON("副本入口", "#9C27B0"),
    SHOP("商店", "#FFC107"),
    QUEST("任务点", "#00BCD4"),
    LANDMARK("地标", "#607D8B")
}

/**
 * 地图区域定义
 */
data class MapRegion(
    val id: String,
    val name: String,
    val centerX: Int,
    val centerY: Int,
    val width: Int,
    val height: Int
)
