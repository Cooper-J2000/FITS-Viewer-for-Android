package com.fitsviewer.app.wcs

import com.fitsviewer.app.fits.FitsHeader
import kotlin.math.*

/**
 * 简化 WCS 实现 (Greisen & Calabretta 2002)。
 * 支持:
 *  - 线性变换: CDi_j 矩阵 / PCi_j×CDELT / CDELT+CROTA2
 *  - 投影: TAN(心射), SIN(正交), 其余按线性近似处理
 * 像素约定: 本类接口使用 0-based 数据数组坐标，内部转 FITS 1-based。
 */
class Wcs(header: FitsHeader) {

    val valid: Boolean
    val ctype1: String
    val ctype2: String
    private val crval1: Double
    private val crval2: Double
    private val crpix1: Double
    private val crpix2: Double
    // 线性矩阵 cd = [[cd11, cd12], [cd21, cd22]] (deg/pixel)
    private val cd = DoubleArray(4)
    private val cdInv = DoubleArray(4)
    private val projection: String

    init {
        ctype1 = header.getString("CTYPE1")?.trim() ?: ""
        ctype2 = header.getString("CTYPE2")?.trim() ?: ""
        crval1 = header.getDouble("CRVAL1")
        crval2 = header.getDouble("CRVAL2")
        crpix1 = header.getDouble("CRPIX1")
        crpix2 = header.getDouble("CRPIX2")
        projection = if (ctype1.length >= 8) ctype1.substring(5, 8).uppercase() else ""

        var ok = !crval1.isNaN() && !crval2.isNaN() && !crpix1.isNaN() && !crpix2.isNaN()

        if (ok) {
            val cd11 = header.getDouble("CD1_1")
            if (!cd11.isNaN()) {
                cd[0] = cd11
                cd[1] = header.getDouble("CD1_2", 0.0).orZero()
                cd[2] = header.getDouble("CD2_1", 0.0).orZero()
                cd[3] = header.getDouble("CD2_2", 0.0).orZero()
            } else {
                val cdelt1 = header.getDouble("CDELT1")
                val cdelt2 = header.getDouble("CDELT2")
                if (cdelt1.isNaN() || cdelt2.isNaN()) {
                    ok = false
                } else if (header.has("PC1_1") || header.has("PC1_2") ||
                    header.has("PC2_1") || header.has("PC2_2")
                ) {
                    val pc11 = header.getDouble("PC1_1", 1.0).orDefault(1.0)
                    val pc12 = header.getDouble("PC1_2", 0.0).orZero()
                    val pc21 = header.getDouble("PC2_1", 0.0).orZero()
                    val pc22 = header.getDouble("PC2_2", 1.0).orDefault(1.0)
                    cd[0] = cdelt1 * pc11; cd[1] = cdelt1 * pc12
                    cd[2] = cdelt2 * pc21; cd[3] = cdelt2 * pc22
                } else {
                    // 旧式 CROTA2 旋转
                    val rot = Math.toRadians(header.getDouble("CROTA2", 0.0).orZero())
                    cd[0] = cdelt1 * cos(rot); cd[1] = -cdelt2 * sin(rot)
                    cd[2] = cdelt1 * sin(rot); cd[3] = cdelt2 * cos(rot)
                }
            }
        }
        if (ok) {
            val det = cd[0] * cd[3] - cd[1] * cd[2]
            if (det == 0.0 || det.isNaN()) ok = false
            else {
                cdInv[0] = cd[3] / det; cdInv[1] = -cd[1] / det
                cdInv[2] = -cd[2] / det; cdInv[3] = cd[0] / det
            }
        }
        valid = ok
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this
    private fun Double.orDefault(d: Double) = if (isNaN()) d else this

    /** 像素尺度 (deg/pixel, 几何平均) */
    fun pixelScaleDeg(): Double =
        sqrt(abs(cd[0] * cd[3] - cd[1] * cd[2]))

    /**
     * 0-based 数据坐标 → 天球坐标 [ra, dec] (deg)。失败返回 null。
     */
    fun pixToWorld(x: Double, y: Double): DoubleArray? {
        if (!valid) return null
        val dx = (x + 1.0) - crpix1
        val dy = (y + 1.0) - crpix2
        // 中间世界坐标 (deg)
        val ix = cd[0] * dx + cd[1] * dy
        val iy = cd[2] * dx + cd[3] * dy
        return when (projection) {
            "TAN" -> tanToSky(ix, iy)
            "SIN" -> sinToSky(ix, iy)
            else -> doubleArrayOf(norm360(crval1 + ix / cosd(crval2)), crval2 + iy)
        }
    }

    /**
     * 天球坐标 (deg) → 0-based 数据坐标 [x, y]。失败返回 null。
     */
    fun worldToPix(ra: Double, dec: Double): DoubleArray? {
        if (!valid) return null
        val xy = when (projection) {
            "TAN" -> skyToTan(ra, dec) ?: return null
            "SIN" -> skyToSin(ra, dec) ?: return null
            else -> doubleArrayOf(angDiff(ra, crval1) * cosd(crval2), dec - crval2)
        }
        val dx = cdInv[0] * xy[0] + cdInv[1] * xy[1]
        val dy = cdInv[2] * xy[0] + cdInv[3] * xy[1]
        return doubleArrayOf(crpix1 + dx - 1.0, crpix2 + dy - 1.0)
    }

    // ---------- 球面旋转 (native ↔ celestial, 极点在 CRVAL, LONPOLE=180) ----------

    private fun nativeToSky(phi: Double, theta: Double): DoubleArray {
        val phip = PI          // 180 deg
        val dp = Math.toRadians(crval2)
        val sinT = sin(theta); val cosT = cos(theta)
        val dPhi = phi - phip
        val dec = asin(sinT * sin(dp) + cosT * cos(dp) * cos(dPhi))
        val ra = Math.toRadians(crval1) +
                atan2(-cosT * sin(dPhi), sinT * cos(dp) - cosT * sin(dp) * cos(dPhi))
        return doubleArrayOf(norm360(Math.toDegrees(ra)), Math.toDegrees(dec))
    }

    private fun skyToNative(ra: Double, dec: Double): DoubleArray {
        val phip = PI
        val dp = Math.toRadians(crval2)
        val a = Math.toRadians(ra) - Math.toRadians(crval1)
        val d = Math.toRadians(dec)
        val theta = asin(sin(d) * sin(dp) + cos(d) * cos(dp) * cos(a))
        val phi = phip + atan2(-cos(d) * sin(a), sin(d) * cos(dp) - cos(d) * sin(dp) * cos(a))
        return doubleArrayOf(phi, theta)
    }

    // ---------- TAN (gnomonic) ----------

    private fun tanToSky(ix: Double, iy: Double): DoubleArray {
        val r = sqrt(ix * ix + iy * iy)          // deg
        val theta = atan2(180.0 / PI, r)         // R_theta = (180/pi) cot(theta)
        val phi = atan2(Math.toRadians(ix), -Math.toRadians(iy))
        return nativeToSky(phi, theta)
    }

    private fun skyToTan(ra: Double, dec: Double): DoubleArray? {
        val (phi, theta) = skyToNative(ra, dec)
        if (tan(theta) == 0.0) return null
        val r = (180.0 / PI) / tan(theta)
        if (r.isNaN() || r.isInfinite() || theta <= 0) return null
        return doubleArrayOf(r * sin(phi), -r * cos(phi))
    }

    // ---------- SIN (orthographic) ----------

    private fun sinToSky(ix: Double, iy: Double): DoubleArray {
        val r = sqrt(ix * ix + iy * iy)
        val arg = (Math.toRadians(r)).coerceIn(-1.0, 1.0)
        val theta = acos(arg)
        val phi = atan2(Math.toRadians(ix), -Math.toRadians(iy))
        return nativeToSky(phi, theta)
    }

    private fun skyToSin(ra: Double, dec: Double): DoubleArray? {
        val (phi, theta) = skyToNative(ra, dec)
        if (theta < 0) return null
        val r = Math.toDegrees(cos(theta))
        return doubleArrayOf(r * sin(phi), -r * cos(phi))
    }

    // ---------- 工具 ----------

    private operator fun DoubleArray.component1() = this[0]
    private operator fun DoubleArray.component2() = this[1]

    private fun cosd(deg: Double) = cos(Math.toRadians(deg))

    private fun norm360(a: Double): Double {
        var x = a % 360.0
        if (x < 0) x += 360.0
        return x
    }

    /** 最小角差 a-b, 处理 RA 环绕 */
    private fun angDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180) d -= 360; if (d < -180) d += 360
        return d
    }

    companion object {
        /** RA (deg) → HH:MM:SS.ss */
        fun formatRa(deg: Double): String {
            val h = norm(deg) / 15.0
            val hh = floor(h).toInt()
            val m = (h - hh) * 60
            val mm = floor(m).toInt()
            val ss = (m - mm) * 60
            return "%02d:%02d:%05.2f".format(hh, mm, ss)
        }

        /** Dec (deg) → ±DD:MM:SS.s */
        fun formatDec(deg: Double): String {
            val sign = if (deg < 0) "-" else "+"
            val d = abs(deg)
            val dd = floor(d).toInt()
            val m = (d - dd) * 60
            val mm = floor(m).toInt()
            val ss = (m - mm) * 60
            return "%s%02d:%02d:%04.1f".format(sign, dd, mm, ss)
        }

        private fun norm(a: Double): Double {
            var x = a % 360.0
            if (x < 0) x += 360.0
            return x
        }

        /** 六十进制字符串 → 度。isRa=true 时按小时计 (×15) */
        fun parseSexagesimal(s: String, isRa: Boolean): Double? {
            val parts = s.trim().split(":")
            if (parts.size < 2) return s.toDoubleOrNull()
            val sign = if (parts[0].trim().startsWith("-")) -1.0 else 1.0
            val a = parts[0].trim().removePrefix("+").removePrefix("-").toDoubleOrNull() ?: return null
            val b = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val c = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            val v = sign * (a + b / 60.0 + c / 3600.0)
            return if (isRa) v * 15.0 else v
        }
    }
}
