package com.fitsviewer.app.fits

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 表格列描述。
 * 二进制表 TFORM: rT，T ∈ {L,X,B,I,J,K,A,E,D,C,M,P,Q}
 * ASCII 表 TFORM: Aw / Iw / Fw.d / Ew.d / Dw.d，配合 TBCOLn 定位。
 */
class TableColumn(
    val index: Int,          // 0-based
    val name: String,
    val unit: String,
    val tform: String,
    val typeChar: Char,
    val repeat: Int,
    val byteOffset: Int,     // 二进制表: 行内字节偏移; ASCII表: TBCOL-1
    val byteWidth: Int,      // 该列占用的字节数(二进制) / 字符数(ASCII)
    val tscal: Double,
    val tzero: Double
) {
    /** 是否可以取出数值用于绘图 (二进制: B/I/J/K/E/D/L/C/M; ASCII: I/F/E/D) */
    val isNumeric: Boolean
        get() = typeChar in "BIJKEDLCMF"
}

/**
 * FITS 表格访问器。支持 XTENSION='BINTABLE' 与 'TABLE'。
 * 数据按需从文件读取，避免整表载入内存。
 */
class FitsTable(private val raf: RandomAccessFile, val hdu: Hdu) {

    val isBinary = hdu.type == HduType.BINTABLE
    val rowBytes = hdu.header.getInt("NAXIS1", 0)
    val nRows = hdu.header.getLong("NAXIS2", 0)
    val columns: List<TableColumn>

    init {
        val h = hdu.header
        val nFields = h.getInt("TFIELDS", 0)
        val cols = ArrayList<TableColumn>(nFields)
        var offset = 0
        for (i in 1..nFields) {
            val tform = (h.getString("TFORM$i") ?: "").trim()
            val name = (h.getString("TTYPE$i") ?: "col$i").trim()
            val unit = (h.getString("TUNIT$i") ?: "").trim()
            val tscal = h.getDouble("TSCAL$i", 1.0).let { if (it.isNaN()) 1.0 else it }
            val tzero = h.getDouble("TZERO$i", 0.0).let { if (it.isNaN()) 0.0 else it }
            if (isBinary) {
                val m = Regex("^(\\d*)([LXBIJKAEDCMPQ])").find(tform.uppercase())
                val repeat = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val r = if (m?.groupValues?.get(1)?.isEmpty() != false) 1 else repeat
                val t = m?.groupValues?.get(2)?.get(0) ?: 'A'
                val width = when (t) {
                    'L', 'B', 'A' -> r
                    'X' -> (r + 7) / 8
                    'I' -> 2 * r
                    'J', 'E' -> 4 * r
                    'K', 'D', 'C', 'P' -> 8 * r
                    'M', 'Q' -> 16 * r
                    else -> r
                }
                cols.add(TableColumn(i - 1, name, unit, tform, t, r, offset, width, tscal, tzero))
                offset += width
            } else {
                val tbcol = h.getInt("TBCOL$i", 1)
                val m = Regex("^([AIFED])(\\d+)").find(tform.uppercase())
                val t = m?.groupValues?.get(1)?.get(0) ?: 'A'
                val wdt = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
                cols.add(TableColumn(i - 1, name, unit, tform, t, 1, tbcol - 1, wdt, tscal, tzero))
            }
        }
        columns = cols
    }

    private fun readRowBytes(row: Long): ByteArray {
        val buf = ByteArray(rowBytes)
        // 与 FitsFile.readImage 共用同一 raf, 需同步保护
        synchronized(raf) {
            raf.seek(hdu.dataOffset + row * rowBytes)
            raf.readFully(buf)
        }
        return buf
    }

    /** 读取 [start, start+count) 行，格式化为字符串单元格 */
    fun readRows(start: Long, count: Int): List<Array<String>> {
        val out = ArrayList<Array<String>>(count)
        var r = start
        val endRow = minOf(nRows, start + count)
        while (r < endRow) {
            val buf = readRowBytes(r)
            out.add(Array(columns.size) { c -> formatCell(buf, columns[c]) })
            r++
        }
        return out
    }

    /** 取某列数值（矢量列取首元素），非数值返回 NaN。最多 maxRows 行 */
    fun readNumericColumn(colIdx: Int, maxRows: Int = 100000): DoubleArray {
        val col = columns[colIdx]
        val n = minOf(nRows, maxRows.toLong()).toInt()
        val out = DoubleArray(n)
        for (r in 0 until n) {
            val buf = readRowBytes(r.toLong())
            out[r] = numericValue(buf, col)
        }
        return out
    }

    /** 取某一行的全部数值列的值 */
    fun readNumericRow(row: Long): DoubleArray =
        DoubleArray(columns.size) { c -> numericValue(readRowBytes(row), columns[c]) }

    // ---------- 单元格解码 ----------

    private fun formatCell(rowBuf: ByteArray, col: TableColumn): String {
        if (!isBinary) return asciiCell(rowBuf, col)
        val bb = ByteBuffer.wrap(rowBuf).order(ByteOrder.BIG_ENDIAN)
        val o = col.byteOffset
        return when (col.typeChar) {
            'A' -> String(rowBuf, o, col.repeat, Charsets.US_ASCII).trim()
            'L' -> (0 until minOf(col.repeat, 3)).joinToString(",") {
                when (rowBuf[o + it].toInt().toChar()) { 'T' -> "T"; 'F' -> "F"; else -> "?" }
            } + more(col.repeat, 3)
            'X' -> "0x" + rowBuf.copyOfRange(o, o + col.byteWidth)
                .joinToString("") { "%02X".format(it) }
            'P' -> "[变长数组 ${bb.getInt(o)} 元素]"
            'Q' -> "[变长数组 ${bb.getLong(o)} 元素]"
            'C' -> "(${fmt(scaled(bb.getFloat(o).toDouble(), col))}, ${fmt(bb.getFloat(o + 4).toDouble())})"
            'M' -> "(${fmt(scaled(bb.getDouble(o), col))}, ${fmt(bb.getDouble(o + 8))})"
            else -> {
                val show = minOf(col.repeat, 3)
                (0 until show).joinToString(",") { k ->
                    fmt(scaled(elem(bb, rowBuf, col, k), col))
                } + more(col.repeat, 3)
            }
        }
    }

    private fun more(repeat: Int, shown: Int) = if (repeat > shown) ",…(${repeat})" else ""

    private fun elem(bb: ByteBuffer, rowBuf: ByteArray, col: TableColumn, k: Int): Double {
        val o = col.byteOffset
        return when (col.typeChar) {
            'B' -> (rowBuf[o + k].toInt() and 0xFF).toDouble()
            'I' -> bb.getShort(o + 2 * k).toDouble()
            'J' -> bb.getInt(o + 4 * k).toDouble()
            'K' -> bb.getLong(o + 8 * k).toDouble()
            'E' -> bb.getFloat(o + 4 * k).toDouble()
            'D' -> bb.getDouble(o + 8 * k)
            'L' -> if (rowBuf[o + k].toInt().toChar() == 'T') 1.0 else 0.0
            else -> Double.NaN
        }
    }

    private fun scaled(v: Double, col: TableColumn) = col.tscal * v + col.tzero

    private fun numericValue(rowBuf: ByteArray, col: TableColumn): Double {
        if (!isBinary) {
            val s = asciiCell(rowBuf, col)
            return s.replace('D', 'E').replace('d', 'E').toDoubleOrNull() ?: Double.NaN
        }
        val bb = ByteBuffer.wrap(rowBuf).order(ByteOrder.BIG_ENDIAN)
        return when (col.typeChar) {
            'B', 'I', 'J', 'K', 'E', 'D', 'L' -> scaled(elem(bb, rowBuf, col, 0), col)
            'C' -> scaled(bb.getFloat(col.byteOffset).toDouble(), col)   // 复数取实部
            'M' -> scaled(bb.getDouble(col.byteOffset), col)
            else -> Double.NaN
        }
    }

    private fun asciiCell(rowBuf: ByteArray, col: TableColumn): String {
        val from = col.byteOffset.coerceIn(0, rowBytes)
        val to = (col.byteOffset + col.byteWidth).coerceIn(from, rowBytes)
        return String(rowBuf, from, to - from, Charsets.US_ASCII).trim()
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString()
        else "%.6g".format(v)
}
