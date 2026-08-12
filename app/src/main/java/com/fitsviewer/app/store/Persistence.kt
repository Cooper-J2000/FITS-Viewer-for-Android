package com.fitsviewer.app.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个图源引用: SAF Uri 字符串 + 显示名 + HDU 序号。
 * 用于最近文件列表与画布历史的图像来源记录。
 */
data class SourceRef(
    val uri: String,
    val name: String,
    val hduIndex: Int = -1
) {
    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri).put("name", name).put("hdu", hduIndex)

    companion object {
        fun fromJson(o: JSONObject) = SourceRef(
            o.optString("uri"), o.optString("name"), o.optInt("hdu", -1)
        )
    }
}

/** 最近打开的文件项 */
data class RecentEntry(val uri: String, val name: String, val time: Long)

/**
 * 最近打开文件列表 (SharedPreferences + JSON)。
 * 保存 SAF Uri 字符串; 是否仍可用由打开时实际尝试决定。
 */
object RecentStore {
    private const val PREF = "fits_recent"
    private const val KEY = "list"
    private const val MAX = 30

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<RecentEntry> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RecentEntry(o.optString("uri"), o.optString("name"), o.optLong("time"))
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 添加/置顶一条记录 (按 uri 去重, 最新在前) */
    fun add(ctx: Context, uri: String, name: String) {
        val cur = list(ctx).filter { it.uri != uri }.toMutableList()
        cur.add(0, RecentEntry(uri, name, System.currentTimeMillis()))
        write(ctx, if (cur.size > MAX) cur.subList(0, MAX) else cur)
    }

    fun remove(ctx: Context, uri: String) {
        write(ctx, list(ctx).filter { it.uri != uri })
    }

    private fun write(ctx: Context, items: List<RecentEntry>) {
        val arr = JSONArray()
        for (e in items) arr.put(
            JSONObject().put("uri", e.uri).put("name", e.name).put("time", e.time)
        )
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}

/** 一个保存的画布 */
data class CanvasEntry(
    val id: String,
    val title: String,
    val time: Long,
    val top: SourceRef?,
    val bottom: SourceRef?,
    val align: Boolean
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id).put("title", title).put("time", time).put("align", align)
        top?.let { o.put("top", it.toJson()) }
        bottom?.let { o.put("bottom", it.toJson()) }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject) = CanvasEntry(
            o.optString("id"), o.optString("title"), o.optLong("time"),
            o.optJSONObject("top")?.let { SourceRef.fromJson(it) },
            o.optJSONObject("bottom")?.let { SourceRef.fromJson(it) },
            o.optBoolean("align")
        )
    }
}

/** 画布历史列表 (SharedPreferences + JSON) */
object CanvasStore {
    private const val PREF = "fits_canvas"
    private const val KEY = "list"
    private const val MAX = 50

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<CanvasEntry> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { CanvasEntry.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun get(ctx: Context, id: String): CanvasEntry? = list(ctx).firstOrNull { it.id == id }

    /** 保存 (按 id 覆盖, 最新在前) */
    fun save(ctx: Context, entry: CanvasEntry) {
        val cur = list(ctx).filter { it.id != entry.id }.toMutableList()
        cur.add(0, entry)
        write(ctx, if (cur.size > MAX) cur.subList(0, MAX) else cur)
    }

    fun remove(ctx: Context, id: String) {
        write(ctx, list(ctx).filter { it.id != id })
    }

    fun newId(): String = "cv_" + System.currentTimeMillis()

    private fun write(ctx: Context, items: List<CanvasEntry>) {
        val arr = JSONArray()
        for (e in items) arr.put(e.toJson())
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
