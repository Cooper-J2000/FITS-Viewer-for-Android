package com.fitsviewer.app.region

import android.graphics.Color
import com.fitsviewer.app.wcs.Wcs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 一个标记区域。坐标统一存为 0-based 图像像素坐标：
 *  circle:  x,y + p[0]=r
 *  ellipse: x,y + p[0]=r1, p[1]=r2, p[2]=angle(deg)
 *  box:     x,y + p[0]=w,  p[1]=h,  p[2]=angle(deg)
 *  point:   x,y
 *  line:    x,y(起点) + p[0],p[1]=终点
 *  text:    x,y
 *  annulus: x,y + p[0..n]=各半径
 *  polygon: x,y=质心 + p[i],p[i+1]=各顶点 (interleaved)
 */
class Region(
    val shape: String,
    var x: Double,
    var y: Double,
    var p: DoubleArray = DoubleArray(0),
    var color: Int = Color.GREEN,
    var label: String = "",
    var width: Int = 2
) {
    /** 平移 (数据像素坐标) */
    fun translate(dx: Double, dy: Double) {
        x += dx; y += dy
        when (shape) {
            "line" -> if (p.size >= 2) { p[0] += dx; p[1] += dy }
            "polygon" -> { var i = 0; while (i + 1 < p.size) { p[i] += dx; p[i + 1] += dy; i += 2 } }
        }
    }

    /** 多边形: 由顶点重算质心存入 x,y (供标签定位与整体平移参考) */
    fun recomputeCentroid() {
        if (shape != "polygon" || p.size < 2) return
        var sx = 0.0; var sy = 0.0; var n = 0; var i = 0
        while (i + 1 < p.size) { sx += p[i]; sy += p[i + 1]; n++; i += 2 }
        if (n > 0) { x = sx / n; y = sy / n }
    }

    /** 命中测试, tol 为数据像素容差 (内部或靠近边缘均判命中) */
    fun hit(px: Double, py: Double, tol: Double): Boolean {
        val dx = px - x; val dy = py - y
        return when (shape) {
            "circle" -> hypot(dx, dy) <= p.getOrElse(0) { 0.0 } + tol
            "annulus" -> hypot(dx, dy) <= (p.maxOrNull() ?: 0.0) + tol
            "point", "text" -> hypot(dx, dy) <= tol * 1.5
            "box", "ellipse" -> {
                val ang = Math.toRadians(p.getOrElse(2) { 0.0 })
                val rx = dx * cos(ang) + dy * sin(ang)
                val ry = -dx * sin(ang) + dy * cos(ang)
                if (shape == "box")
                    abs(rx) <= p.getOrElse(0) { 0.0 } / 2 + tol && abs(ry) <= p.getOrElse(1) { 0.0 } / 2 + tol
                else {
                    val a = p.getOrElse(0) { 1.0 } + tol; val b = p.getOrElse(1) { 1.0 } + tol
                    (rx * rx) / (a * a) + (ry * ry) / (b * b) <= 1.0
                }
            }
            "line" -> distToSeg(px, py, x, y, p.getOrElse(0) { x }, p.getOrElse(1) { y }) <= tol
            "polygon" -> hitPolygon(px, py, tol)
            else -> hypot(dx, dy) <= tol
        }
    }

    private fun hitPolygon(px: Double, py: Double, tol: Double): Boolean {
        val n = p.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = p[i * 2]; val yi = p[i * 2 + 1]; val xj = p[j * 2]; val yj = p[j * 2 + 1]
            if (((yi > py) != (yj > py)) && (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) inside = !inside
            if (distToSeg(px, py, xi, yi, xj, yj) <= tol) return true
            j = i
        }
        return inside
    }
}

/** 点到线段距离 (数据像素) */
private fun distToSeg(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val vx = bx - ax; val vy = by - ay
    val len2 = vx * vx + vy * vy
    if (len2 < 1e-12) return hypot(px - ax, py - ay)
    val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * vx), py - (ay + t * vy))
}

/**
 * ds9 .reg 文件解析/序列化。
 * 支持坐标系: image / physical / fk5 / icrs / j2000 (等价按 fk5 处理)。
 * 支持尺寸单位: " (角秒) ' (角分) d (度) 或裸数字。
 * 位置支持六十进制 (HH:MM:SS / DD:MM:SS)。
 */
object Ds9 {

    private val COLORS = mapOf(
        "white" to Color.WHITE, "black" to Color.BLACK, "red" to Color.RED,
        "green" to Color.GREEN, "blue" to Color.rgb(64, 128, 255),
        "cyan" to Color.CYAN, "magenta" to Color.MAGENTA, "yellow" to Color.YELLOW
    )

    private val SKY_FRAMES = setOf("fk5", "icrs", "j2000", "fk4")
    private val PIX_FRAMES = setOf("image", "physical", "linear")

    /**
     * 解析 .reg 文本。wcs 用于把天球坐标区域换算到像素；
     * 若无有效 WCS，天球坐标区域会被跳过。
     * @return (区域列表, 跳过条数)
     */
    fun parse(text: String, wcs: Wcs?): Pair<List<Region>, Int> {
        val regions = ArrayList<Region>()
        var skipped = 0
        var frame = "physical"
        var globalColor = Color.GREEN
        var globalWidth = 2

        for (rawLine in text.lines()) {
            // 一行可能用分号分隔多条
            for (piece in rawLine.split(";")) {
                val line = piece.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val lower = line.lowercase()
                if (lower in SKY_FRAMES || lower in PIX_FRAMES || lower == "galactic" ||
                    lower == "ecliptic" || lower == "wcs" || lower == "amplifier" || lower == "detector"
                ) {
                    frame = lower; continue
                }
                if (lower.startsWith("global")) {
                    parseColor(line)?.let { globalColor = it }
                    parseWidth(line)?.let { globalWidth = it }
                    continue
                }
                val m = Regex("^-?\\s*([a-zA-Z]+)\\s*\\(([^)]*)\\)\\s*(#.*)?$").find(line) ?: run {
                    skipped++; null
                } ?: continue
                val shape = m.groupValues[1].lowercase()
                val args = m.groupValues[2].split(",").map { it.trim() }
                val props = m.groupValues[3]
                val isSky = frame in SKY_FRAMES
                if (frame == "galactic" || frame == "ecliptic") { skipped++; continue }
                if (isSky && (wcs == null || !wcs.valid)) { skipped++; continue }

                val region = buildRegion(shape, args, isSky, wcs) ?: run { skipped++; null } ?: continue
                region.color = parseColor(props) ?: globalColor
                region.width = parseWidth(props) ?: globalWidth
                region.label = Regex("text=\\{([^}]*)\\}").find(props)?.groupValues?.get(1) ?: ""
                regions.add(region)
            }
        }
        return Pair(regions, skipped)
    }

    private fun buildRegion(shape: String, args: List<String>, isSky: Boolean, wcs: Wcs?): Region? {
        if (args.size < 2) return null
        val pos = parsePos(args[0], args[1], isSky, wcs) ?: return null
        val (px, py) = pos
        val scale = if (isSky) wcs!!.pixelScaleDeg() else 1.0  // deg/pix

        fun size(tok: String): Double? = parseSize(tok, isSky, scale)

        return when (shape) {
            "circle" -> {
                val r = size(args.getOrNull(2) ?: return null) ?: return null
                Region("circle", px, py, doubleArrayOf(r))
            }
            "ellipse" -> {
                val r1 = size(args.getOrNull(2) ?: return null) ?: return null
                val r2 = size(args.getOrNull(3) ?: return null) ?: return null
                val ang = args.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                Region("ellipse", px, py, doubleArrayOf(r1, r2, ang))
            }
            "box" -> {
                val w = size(args.getOrNull(2) ?: return null) ?: return null
                val h = size(args.getOrNull(3) ?: return null) ?: return null
                val ang = args.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                Region("box", px, py, doubleArrayOf(w, h, ang))
            }
            "point" -> Region("point", px, py)
            "text" -> Region("text", px, py)
            "line" -> {
                val pos2 = parsePos(args.getOrNull(2) ?: return null,
                    args.getOrNull(3) ?: return null, isSky, wcs) ?: return null
                Region("line", px, py, doubleArrayOf(pos2[0], pos2[1]))
            }
            "annulus" -> {
                val radii = args.drop(2).mapNotNull { size(it) }
                if (radii.isEmpty()) null
                else Region("annulus", px, py, radii.toDoubleArray())
            }
            "polygon" -> {
                // 顶点成对: sky 帧为 ra,dec; 像素帧为 x,y
                val verts = ArrayList<Double>()
                var i = 0
                while (i + 1 < args.size) {
                    val pos2 = parsePos(args[i], args[i + 1], isSky, wcs) ?: return null
                    verts.add(pos2[0]); verts.add(pos2[1]); i += 2
                }
                if (verts.size < 6) null
                else Region("polygon", 0.0, 0.0, verts.toDoubleArray()).also { it.recomputeCentroid() }
            }
            else -> null
        }
    }

    /** 位置 → 0-based 像素坐标 */
    private fun parsePos(a: String, b: String, isSky: Boolean, wcs: Wcs?): DoubleArray? {
        return if (isSky) {
            val ra = Wcs.parseSexagesimal(a, isRa = a.contains(":")) ?: return null
            val dec = Wcs.parseSexagesimal(b, isRa = false) ?: return null
            wcs?.worldToPix(ra, dec)
        } else {
            val x = a.toDoubleOrNull() ?: return null
            val y = b.toDoubleOrNull() ?: return null
            doubleArrayOf(x - 1.0, y - 1.0)   // ds9 image 坐标 1-based
        }
    }

    /** 尺寸 token → 像素 */
    private fun parseSize(tok: String, isSky: Boolean, degPerPix: Double): Double? {
        val t = tok.trim()
        val (num, unit) = when {
            t.endsWith("\"") -> Pair(t.dropLast(1), "arcsec")
            t.endsWith("'") -> Pair(t.dropLast(1), "arcmin")
            t.endsWith("d") -> Pair(t.dropLast(1), "deg")
            t.endsWith("r") -> Pair(t.dropLast(1), "rad")
            else -> Pair(t, if (isSky) "deg" else "pix")
        }
        val v = num.toDoubleOrNull() ?: return null
        val deg = when (unit) {
            "arcsec" -> v / 3600.0
            "arcmin" -> v / 60.0
            "deg" -> v
            "rad" -> Math.toDegrees(v)
            else -> return v   // 像素
        }
        return if (degPerPix > 0) deg / degPerPix else null
    }

    private fun parseColor(s: String): Int? {
        val m = Regex("color\\s*=\\s*(\\w+)").find(s.lowercase()) ?: return null
        return COLORS[m.groupValues[1]]
    }

    private fun parseWidth(s: String): Int? {
        val m = Regex("width\\s*=\\s*(\\d+)").find(s.lowercase()) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /**
     * 序列化为标准 ds9 .reg 文本。有有效 WCS 时输出 fk5 (度)，否则 image (1-based 像素)。
     */
    fun serialize(regions: List<Region>, wcs: Wcs?): String {
        val sb = StringBuilder()
        sb.append("# Region file format: DS9 version 4.1\n")
        sb.append("global color=green dashlist=8 3 width=1 font=\"helvetica 10 normal roman\" ")
        sb.append("select=1 highlite=1 dash=0 fixed=0 edit=1 move=1 delete=1 include=1 source=1\n")
        val useSky = wcs != null && wcs.valid
        sb.append(if (useSky) "fk5\n" else "image\n")
        val scale = if (useSky) wcs!!.pixelScaleDeg() else 1.0

        loop@ for (r in regions) {
            val posStr: String
            if (useSky) {
                val w = wcs!!.pixToWorld(r.x, r.y) ?: continue@loop
                posStr = "%.6f,%.6f".format(w[0], w[1])
            } else {
                posStr = "%.2f,%.2f".format(r.x + 1.0, r.y + 1.0)
            }
            fun sz(pix: Double): String =
                if (useSky) "%.3f\"".format(pix * scale * 3600.0) else "%.2f".format(pix)

            val body = when (r.shape) {
                "circle" -> "circle($posStr,${sz(r.p[0])})"
                "ellipse" -> "ellipse($posStr,${sz(r.p[0])},${sz(r.p[1])},${fmtAng(r.p.getOrElse(2) { 0.0 })})"
                "box" -> "box($posStr,${sz(r.p[0])},${sz(r.p[1])},${fmtAng(r.p.getOrElse(2) { 0.0 })})"
                "point" -> "point($posStr)"
                "text" -> "text($posStr)"
                "annulus" -> "annulus($posStr," + r.p.joinToString(",") { sz(it) } + ")"
                "polygon" -> {
                    val parts = ArrayList<String>()
                    var i = 0
                    while (i + 1 < r.p.size) {
                        if (useSky) {
                            val wv = wcs!!.pixToWorld(r.p[i], r.p[i + 1]) ?: continue@loop
                            parts.add("%.6f".format(wv[0])); parts.add("%.6f".format(wv[1]))
                        } else {
                            parts.add("%.2f".format(r.p[i] + 1.0)); parts.add("%.2f".format(r.p[i + 1] + 1.0))
                        }
                        i += 2
                    }
                    "polygon(" + parts.joinToString(",") + ")"
                }
                "line" -> {
                    if (useSky) {
                        val w2 = wcs!!.pixToWorld(r.p[0], r.p[1]) ?: continue@loop
                        "line($posStr,%.6f,%.6f)".format(w2[0], w2[1])
                    } else {
                        "line($posStr,%.2f,%.2f)".format(r.p[0] + 1.0, r.p[1] + 1.0)
                    }
                }
                else -> continue@loop
            }
            sb.append(body)
            val attrs = ArrayList<String>()
            val cname = COLORS.entries.firstOrNull { it.value == r.color }?.key
            if (cname != null && cname != "green") attrs.add("color=$cname")
            if (r.width != 1) attrs.add("width=${r.width}")
            if (r.label.isNotEmpty()) attrs.add("text={${r.label}}")
            if (attrs.isNotEmpty()) sb.append(" # ").append(attrs.joinToString(" "))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun fmtAng(a: Double): String =
        if (abs(a - a.toLong()) < 1e-9) a.toLong().toString() else "%.2f".format(a)
}
