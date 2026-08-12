package com.fitsviewer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fitsviewer.app.fits.FitsFile
import com.fitsviewer.app.store.RecentStore
import com.fitsviewer.app.view.ChartView
import java.io.File

/**
 * 全局持有当前打开的 FITS 文件, 供各 Activity 共享
 * (通过 Intent 只传 HDU 序号等轻量信息)。
 */
object FitsRepo {

    var fits: FitsFile? = null
        private set
    var fileName: String = ""
        private set

    /** 画布多图: 已打开的共享文件 (key = uri hash) */
    private val openFiles = HashMap<String, FitsFile>()

    /** 待绘制图表数据 (TableActivity → ChartActivity) */
    var chartX: DoubleArray = DoubleArray(0)
    var chartY: DoubleArray = DoubleArray(0)
    var chartType: ChartView.Type = ChartView.Type.LINE
    var chartXLabel: String = ""
    var chartYLabel: String = ""
    var chartTitle: String = ""

    /**
     * 从 SAF Uri 打开: 复制到缓存目录以获得随机访问能力, 然后解析。
     * 需在后台线程调用。
     */
    @Throws(Exception::class)
    fun open(ctx: Context, uri: Uri) {
        val cache = File(ctx.cacheDir, "current.fits")
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取所选文件" }
            cache.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
        fits?.close()
        fits = null   // 先置空: 若下面解析失败, 不能留下已关闭文件的引用
        fileName = ""
        fits = FitsFile(cache)
        fileName = queryName(ctx, uri) ?: cache.name
        tryPersist(ctx, uri)
        RecentStore.add(ctx, uri.toString(), fileName)
    }

    /**
     * 打开一个"共享"文件 (画布多图场景, 不影响当前 fits)。
     * 按 Uri 缓存到独立文件并复用已打开的 FitsFile。需在后台线程调用。
     */
    @Throws(Exception::class)
    fun openShared(ctx: Context, uri: Uri): FitsFile {
        val key = keyForUri(uri)
        openFiles[key]?.let { return it }
        val cache = File(ctx.cacheDir, "fits_$key.fits")
        if (!cache.exists() || cache.length() == 0L) {
            ctx.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选文件" }
                cache.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
            }
        }
        val f = FitsFile(cache)
        openFiles[key] = f
        tryPersist(ctx, uri)
        return f
    }

    /** SAF Uri 显示名 (供外部记录最近文件用) */
    fun displayName(ctx: Context, uri: Uri): String = queryName(ctx, uri) ?: uri.lastPathSegment ?: "file"

    private fun keyForUri(uri: Uri): String = Integer.toHexString(uri.toString().hashCode())

    /** 尝试申请持久化读权限 (ACTION_OPEN_DOCUMENT 授予; file:// 或普通分享会失败, 忽略) */
    private fun tryPersist(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
    }

    private fun queryName(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }
}
