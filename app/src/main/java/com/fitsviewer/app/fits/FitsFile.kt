package com.fitsviewer.app.fits

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** HDU 类型 */
enum class HduType { PRIMARY, IMAGE, BINTABLE, ASCII_TABLE, UNKNOWN }

/** 单个 Header-Data Unit 的元信息 */
class Hdu(
    val index: Int,
    val header: FitsHeader,
    val dataOffset: Long,
    val dataSizeBytes: Long
) {
    val type: HduType = when {
        index == 0 -> HduType.PRIMARY
        else -> when (header.getString("XTENSION")?.trim()?.uppercase()) {
            "IMAGE" -> HduType.IMAGE
            "BINTABLE" -> HduType.BINTABLE
            "TABLE" -> HduType.ASCII_TABLE
            else -> HduType.UNKNOWN
        }
    }

    val bitpix: Int = header.getInt("BITPIX", 8)
    val naxis: Int = header.getInt("NAXIS", 0)
    val naxes: LongArray = LongArray(naxis) { header.getLong("NAXIS${it + 1}", 0) }

    val name: String =
        header.getString("EXTNAME")?.trim()
            ?: if (index == 0) "PRIMARY" else "HDU $index"

    /** 该 HDU 是否含有可显示的 2D(+) 图像数据 */
    val hasImage: Boolean
        get() = (type == HduType.PRIMARY || type == HduType.IMAGE) &&
                naxis >= 1 && naxes.all { it > 0 } && dataSizeBytes > 0

    val isTable: Boolean
        get() = type == HduType.BINTABLE || type == HduType.ASCII_TABLE

    fun dimsString(): String = when {
        isTable -> "${header.getLong("NAXIS2")} 行 × ${header.getInt("TFIELDS")} 列"
        naxis == 0 || dataSizeBytes == 0L -> "无数据"
        else -> naxes.joinToString(" × ")
    }

    fun typeString(): String = when (type) {
        HduType.PRIMARY -> if (hasImage) "Primary (图像)" else "Primary (仅Header)"
        HduType.IMAGE -> "Image 扩展"
        HduType.BINTABLE -> "Binary Table 扩展"
        HduType.ASCII_TABLE -> "ASCII Table 扩展"
        HduType.UNKNOWN -> "未知扩展 (${header.getString("XTENSION") ?: "?"})"
    }
}

/** 读入的图像数据（float 化，含降采样系数） */
class ImageData(
    val data: FloatArray,
    val width: Int,
    val height: Int,
    /** 显示像素相对原始像素的抽样步长 (>=1) */
    val bin: Int
)

/**
 * FITS 文件读取器。基于 2880 字节逻辑记录扫描全部 HDU (FITS Standard 4.0)。
 */
class FitsFile(val file: File) : Closeable {

    companion object {
        const val BLOCK = 2880
        const val CARD = 80
    }

    private val raf = RandomAccessFile(file, "r")
    val hdus = ArrayList<Hdu>()

    init {
        scan()
        require(hdus.isNotEmpty()) { "不是有效的 FITS 文件（未找到任何 HDU）" }
    }

    private fun scan() {
        var pos = 0L
        val len = raf.length()
        var index = 0
        while (pos + BLOCK <= len) {
            val cards = ArrayList<FitsCard>()
            var end = false
            var p = pos
            // 逐块读 header 直到 END 卡片
            while (!end && p + BLOCK <= len) {
                val block = ByteArray(BLOCK)
                raf.seek(p)
                raf.readFully(block)
                if (p == pos && index == 0) {
                    val first = String(block, 0, 6, Charsets.US_ASCII)
                    require(first == "SIMPLE") { "文件不以 SIMPLE 开头，不是标准 FITS" }
                }
                if (p == pos && index > 0) {
                    // 扩展 HDU 必须以 XTENSION 开头；否则视为尾部填充，结束扫描
                    val first = String(block, 0, 8, Charsets.US_ASCII).trim()
                    if (first != "XTENSION") return
                }
                for (i in 0 until BLOCK / CARD) {
                    val line = String(block, i * CARD, CARD, Charsets.US_ASCII)
                    if (line.startsWith("END") && line.substring(3).isBlank()) { end = true; break }
                    if (line.isNotBlank()) cards.add(FitsCard(line))
                }
                p += BLOCK
            }
            if (!end) return  // header 不完整，停止
            val header = FitsHeader(cards)
            val dataBytes = dataSizeOf(header)
            val dataPadded = (dataBytes + BLOCK - 1) / BLOCK * BLOCK
            hdus.add(Hdu(index, header, p, dataBytes))
            pos = p + dataPadded
            index++
        }
    }

    /** 依据标准公式: Nbytes = |BITPIX|/8 × GCOUNT × (PCOUNT + NAXIS1×…×NAXISn) */
    private fun dataSizeOf(h: FitsHeader): Long {
        val naxis = h.getInt("NAXIS", 0)
        if (naxis == 0) return 0
        var prod = 1L
        for (i in 1..naxis) {
            val n = h.getLong("NAXIS$i", 0)
            if (n == 0L) return 0
            prod *= n
        }
        val bitpix = h.getInt("BITPIX", 8)
        val gcount = h.getLong("GCOUNT", 1).coerceAtLeast(1)
        val pcount = h.getLong("PCOUNT", 0)
        return Math.abs(bitpix).toLong() / 8 * gcount * (pcount + prod)
    }

    /**
     * 读取图像 HDU 为 float 数组（应用 BSCALE/BZERO，BLANK→NaN）。
     * 大图按步长抽样降采样，保证像素数 <= maxPixels。
     * 若 NAXIS>2 只读第一个切面。1 维数据按 N×1 图像处理。
     */
    fun readImage(hdu: Hdu, maxPixels: Int = 4096 * 2048): ImageData {
        require(hdu.hasImage) { "该 HDU 无图像数据" }
        val w = hdu.naxes[0].toInt()
        val h = if (hdu.naxis >= 2) hdu.naxes[1].toInt() else 1
        var bin = 1
        while ((w.toLong() / bin) * (h.toLong() / bin) > maxPixels) bin++
        val ow = (w + bin - 1) / bin
        val oh = (h + bin - 1) / bin

        val bitpix = hdu.bitpix
        val bpp = Math.abs(bitpix) / 8
        val bscale = hdu.header.getDouble("BSCALE", 1.0).let { if (it.isNaN()) 1.0 else it }
        val bzero = hdu.header.getDouble("BZERO", 0.0).let { if (it.isNaN()) 0.0 else it }
        val hasBlank = hdu.header.has("BLANK")
        val blank = hdu.header.getLong("BLANK", Long.MIN_VALUE)

        val out = FloatArray(ow * oh)
        val rowBytes = w.toLong() * bpp
        val rowBuf = ByteArray(w * bpp)

        for (oy in 0 until oh) {
            val srcY = (oy * bin).coerceAtMost(h - 1)
            // 同步保护: 多个 Activity 的后台线程可能并发 seek/read 同一 raf
            synchronized(raf) {
                raf.seek(hdu.dataOffset + srcY * rowBytes)
                raf.readFully(rowBuf)
            }
            val bb = ByteBuffer.wrap(rowBuf).order(ByteOrder.BIG_ENDIAN)
            var ox = 0
            var sx = 0
            while (ox < ow) {
                val idx = sx * bpp
                val v: Double = when (bitpix) {
                    8 -> (rowBuf[idx].toInt() and 0xFF).toDouble()
                    16 -> bb.getShort(idx).toDouble()
                    32 -> bb.getInt(idx).toDouble()
                    64 -> bb.getLong(idx).toDouble()
                    -32 -> bb.getFloat(idx).toDouble()
                    -64 -> bb.getDouble(idx)
                    else -> Double.NaN
                }
                out[oy * ow + ox] =
                    if (bitpix > 0 && hasBlank && v.toLong() == blank) Float.NaN
                    else (bscale * v + bzero).toFloat()
                ox++
                sx = (ox * bin).coerceAtMost(w - 1)
            }
        }
        return ImageData(out, ow, oh, bin)
    }

    /** 表格读取入口（详见 FitsTable） */
    fun openTable(hdu: Hdu): FitsTable = FitsTable(raf, hdu)

    override fun close() {
        try { raf.close() } catch (_: Exception) {}
    }
}
