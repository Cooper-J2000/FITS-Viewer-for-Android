package com.fitsviewer.app.fits

/**
 * 一张 80 字符 FITS header 卡片 (FITS Standard 4.0, Sect 4.1)。
 * 格式: KEYWORD (1-8列) + "= " (9-10列, 若有值) + 值/注释。
 */
class FitsCard(raw80: String) {
    val raw: String = raw80.trimEnd()
    val key: String
    var value: String? = null      // 已去掉引号的值字符串
        private set
    var comment: String? = null
        private set
    var isString: Boolean = false
        private set

    init {
        val padded = if (raw80.length < 80) raw80.padEnd(80) else raw80
        key = padded.substring(0, 8).trim()
        if (padded.length >= 10 && padded[8] == '=' && padded[9] == ' ' &&
            key.isNotEmpty() && key != "COMMENT" && key != "HISTORY"
        ) {
            parseValue(padded.substring(10))
        } else if (key == "COMMENT" || key == "HISTORY" || key.isEmpty()) {
            comment = padded.substring(8).trim()
        }
    }

    private fun parseValue(field: String) {
        var s = field
        var i = 0
        while (i < s.length && s[i] == ' ') i++
        if (i < s.length && s[i] == '\'') {
            // 字符串值, '' 转义单引号
            isString = true
            val sb = StringBuilder()
            var j = i + 1
            while (j < s.length) {
                if (s[j] == '\'') {
                    if (j + 1 < s.length && s[j + 1] == '\'') { sb.append('\''); j += 2 }
                    else { j++; break }
                } else { sb.append(s[j]); j++ }
            }
            value = sb.toString().trimEnd()
            val rest = s.substring(minOf(j, s.length))
            val slash = rest.indexOf('/')
            if (slash >= 0) comment = rest.substring(slash + 1).trim()
        } else {
            val slash = s.indexOf('/')
            val v: String
            if (slash >= 0) { v = s.substring(0, slash); comment = s.substring(slash + 1).trim() }
            else v = s
            value = v.trim().ifEmpty { null }
        }
    }

    /** 用于展示的格式化行 */
    fun displayString(): String = raw.ifEmpty { key }
}

/** 一个 HDU 的完整 header */
class FitsHeader(val cards: List<FitsCard>) {

    private val map = HashMap<String, FitsCard>()

    init {
        for (c in cards) if (c.key.isNotEmpty() && !map.containsKey(c.key)) map[c.key] = c
    }

    fun has(key: String) = map.containsKey(key)

    fun card(key: String): FitsCard? = map[key]

    fun getString(key: String, def: String? = null): String? = map[key]?.value ?: def

    fun getInt(key: String, def: Int = 0): Int =
        map[key]?.value?.trim()?.toDoubleOrNull()?.toInt() ?: def

    fun getLong(key: String, def: Long = 0L): Long =
        map[key]?.value?.trim()?.toDoubleOrNull()?.toLong() ?: def

    fun getDouble(key: String, def: Double = Double.NaN): Double {
        val v = map[key]?.value?.trim() ?: return def
        // FITS 允许 Fortran 风格 D 指数
        return v.replace('D', 'E').replace('d', 'E').toDoubleOrNull() ?: def
    }

    fun getBoolean(key: String, def: Boolean = false): Boolean =
        when (map[key]?.value?.trim()) { "T" -> true; "F" -> false; else -> def }
}
