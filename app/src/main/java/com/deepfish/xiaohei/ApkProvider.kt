package com.deepfish.xiaohei

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * 只干一件事：把 cacheDir/ota/xiaohei-pet.apk 以 content:// 暴露给系统安装器。
 * Android 7 起直接传 file:// 会抛 FileUriExposedException，项目又不依赖 androidx，
 * 所以不引 FileProvider，自己写一个最小 provider。不 exported，靠 Intent 临时授权。
 */
class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val f = OtaUpdater.apkFile(ctx)
        if (!f.isFile) return null
        // 只开放下载目录里那一个固定文件名，别的路径一律拒绝
        if (uri.path != "/ota/${f.name}") return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0
}
