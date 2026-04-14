package com.lkwg.maptracker.cv

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/**
 * 基于 ORB 特征的地图匹配器
 *
 * ORB 替代 SIFT（Android OpenCV SDK 不内置 SIFT）
 * 配合 CLAHE 弱纹理增强，在海洋/纯色区域也能匹配
 */
class MapMatcher {

    companion object {
        private const val TAG = "MapMatcher"
        private const val RATIO_THRESH = 0.75f
        private const val RANSAC_THRESH = 5.0
        private const val MIN_MATCHES = 10
    }

    private var fullMapGray: Mat? = null
    private var fullMapDescriptors: Mat? = null
    private var fullMapKeypoints: MatOfKeyPoint? = null
    private var orb: ORB? = null
    private var matcher: org.opencv.features2d.DescriptorMatcher? = null
    private var fullMapWidth = 0
    private var fullMapHeight = 0

    data class MatchResult(
        val x: Double,
        val y: Double,
        val confidence: Float,
        val rotation: Double
    )

    /**
     * 加载完整大地图
     */
    fun loadFullMap(mapBitmap: Bitmap) {
        release()

        val mapMat = Mat()
        Utils.bitmapToMat(mapBitmap, mapMat)
        fullMapWidth = mapBitmap.width
        fullMapHeight = mapBitmap.height

        val grayMat = Mat()
        Imgproc.cvtColor(mapMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        mapMat.release()

        orb = ORB.create(
            5000, 1.2f, 8, 31, 0, 2,
            ORB.HARRIS_SCORE, 31, 20
        )

        matcher = org.opencv.features2d.DescriptorMatcher.create(
            org.opencv.features2d.DescriptorMatcher.BRUTEFORCE_HAMMING
        )

        val kps = MatOfKeyPoint()
        val desc = Mat()
        orb!!.detectAndCompute(grayMat, Mat(), kps, desc)

        fullMapGray = grayMat
        fullMapKeypoints = kps
        fullMapDescriptors = desc

        Log.d(TAG, "大地图已加载: ${mapBitmap.width}x${mapBitmap.height}, 特征点: ${kps.toArray().size}")
        mapBitmap.recycle()
    }

    /**
     * 匹配小地图区域
     */
    fun match(miniMapBitmap: Bitmap): MatchResult? {
        val mapGray = fullMapGray ?: return null
        val mapDesc = fullMapDescriptors ?: return null
        val mapKps = fullMapKeypoints ?: return null
        val orbDetector = orb ?: return null
        val descriptorMatcher = matcher ?: return null

        val miniMat = Mat()
        Utils.bitmapToMat(miniMapBitmap, miniMat)
        val miniGray = Mat()
        Imgproc.cvtColor(miniMat, miniGray, Imgproc.COLOR_RGBA2GRAY)
        miniMat.release()

        // CLAHE 弱纹理增强
        try {
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(miniGray, miniGray)
        } catch (_: Exception) {}

        val miniKps = MatOfKeyPoint()
        val miniDesc = Mat()
        orbDetector.detectAndCompute(miniGray, Mat(), miniKps, miniDesc)

        if (miniDesc.empty() || mapDesc.empty()) {
            cleanup(miniGray, miniKps, miniDesc)
            return null
        }

        // KNN 匹配
        val matches = mutableListOf<MatOfDMatch>()
        try {
            descriptorMatcher.knnMatch(miniDesc, mapDesc, matches, 2)
        } catch (e: Exception) {
            Log.e(TAG, "knnMatch 失败", e)
            cleanup(miniGray, miniKps, miniDesc)
            return null
        }

        // Lowe's ratio test
        val goodMatches = mutableListOf<DMatch>()
        for (pair in matches) {
            val m = pair.toArray()
            if (m.size == 2 && m[0].distance < RATIO_THRESH * m[1].distance) {
                goodMatches.add(m[0])
            }
        }

        if (goodMatches.size < MIN_MATCHES) {
            Log.d(TAG, "优质匹配不足: ${goodMatches.size}")
            cleanup(miniGray, miniKps, miniDesc)
            return null
        }

        // RANSAC 单应矩阵
        val miniKpArray = miniKps.toArray()
        val mapKpArray = mapKps.toArray()

        val srcPts = MatOfPoint2f(*goodMatches.map { miniKpArray[it.queryIdx].pt }.toTypedArray())
        val dstPts = MatOfPoint2f(*goodMatches.map { mapKpArray[it.trainIdx].pt }.toTypedArray())
        val mask = Mat()

        val homography = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, RANSAC_THRESH, mask)

        if (homography == null || homography.empty()) {
            cleanup(miniGray, miniKps, miniDesc)
            srcPts.release(); dstPts.release(); mask.release()
            return null
        }

        // 计算小地图中心在大地图中的位置
        val cx = miniMapBitmap.width / 2.0
        val cy = miniMapBitmap.height / 2.0
        val centerPt = Mat(3, 1, CvType.CV_64F).apply {
            put(0, 0, cx); put(1, 0, cy); put(2, 0, 1.0)
        }

        val resultPt = Mat()
        Core.gemm(homography, centerPt, 1.0, Mat(), 0.0, resultPt)
        val w = resultPt.get(2, 0)[0]
        if (w == 0.0) {
            cleanup(miniGray, miniKps, miniDesc)
            srcPts.release(); dstPts.release(); mask.release()
            homography.release(); centerPt.release(); resultPt.release()
            return null
        }

        val mapX = resultPt.get(0, 0)[0] / w
        val mapY = resultPt.get(1, 0)[0] / w

        // 朝向角度
        val angle = Math.toDegrees(Math.atan2(
            homography.get(1, 0)[0], homography.get(0, 0)[0]
        ))

        // 置信度 = RANSAC 内点比例
        val inliers = Core.countNonZero(mask)
        val confidence = inliers.toFloat() / goodMatches.size

        // 清理
        cleanup(miniGray, miniKps, miniDesc)
        srcPts.release(); dstPts.release(); mask.release()
        homography.release(); centerPt.release(); resultPt.release()

        Log.d(TAG, "匹配成功: ($mapX, $mapY) 置信度:$confidence 匹配点:${goodMatches.size}")

        return MatchResult(mapX, mapY, confidence, angle)
    }

    fun isLoaded(): Boolean = fullMapGray != null

    fun getMapSize(): Pair<Int, Int> = Pair(fullMapWidth, fullMapHeight)

    private fun cleanup(vararg mats: Mat) {
        for (m in mats) { try { m.release() } catch (_: Exception) {} }
    }

    fun release() {
        cleanup(fullMapGray ?: Mat(), fullMapDescriptors ?: Mat(), fullMapKeypoints ?: Mat())
        fullMapGray = null
        fullMapDescriptors = null
        fullMapKeypoints = null
        orb?.clear()
        orb = null
        matcher?.clear()
        matcher = null
    }
}
