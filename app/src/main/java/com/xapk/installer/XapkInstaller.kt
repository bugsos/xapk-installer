package com.xapk.installer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/**
 * 用 PackageInstaller 的 Session API 安装 xapk。
 *
 * 为什么不用 Intent 跳系统安装器：系统安装器一次只收一个 apk，
 * 装不了 xapk 里的多个 split（base + config.<abi> + obbassets）。
 * 必须自己 createSession → 逐个 split 写流 → commit。
 *
 * 另一个好处是全程流式：不把 split 解压到磁盘，直接从 xapk 这个 zip 读进 session，
 * 省掉一次 1GB+ 的落地与清理。
 */
class XapkInstaller(private val context: Context) {

    data class Progress(val percent: Int, val message: String)

    fun install(
        info: XapkInfo,
        onProgress: (Progress) -> Unit,
        onFinished: (Boolean, String) -> Unit
    ) {
        thread(name = "xapk-install") {
            try {
                doInstall(info, onProgress)
            } catch (t: Throwable) {
                Log.w(TAG, "安装失败", t)
                onFinished(false, "安装失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun doInstall(info: XapkInfo, onProgress: (Progress) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        info.packageName?.let { params.setAppPackageName(it) }

        val sessionId = installer.createSession(params)
        var session: PackageInstaller.Session? = null

        try {
            session = installer.openSession(sessionId)
            val total = info.splits.size.coerceAtLeast(1)

            ZipFile(info.file).use { zip ->
                info.splits.forEachIndexed { index, split ->
                    val entry = zip.getEntry(split.entryName)
                        ?: throw IllegalStateException("xapk 内缺少条目：${split.entryName}")

                    onProgress(
                        Progress(
                            percent = index * 100 / total,
                            message = "正在写入 ${split.entryName}（${formatSize(split.size)}）"
                        )
                    )

                    zip.getInputStream(entry).use { input ->
                        session.openWrite(split.entryName, 0, entry.size).use { out ->
                            input.copyTo(out, BUFFER_SIZE)
                            session.fsync(out)
                        }
                    }
                }
            }

            val callback = Intent(context, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION
                putExtra(InstallResultReceiver.EXTRA_PACKAGE, info.packageName)
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )

            onProgress(Progress(100, "已提交，等待系统安装确认…"))
            session.commit(pendingIntent.intentSender)
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        } finally {
            runCatching { session?.close() }
        }
    }

    companion object {
        private const val TAG = "XapkInstaller"
        private const val BUFFER_SIZE = 256 * 1024

        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var v = bytes.toDouble()
            var i = 0
            while (v >= 1024 && i < units.lastIndex) {
                v /= 1024
                i++
            }
            return if (i == 0) "${bytes} B" else String.format("%.1f %s", v, units[i])
        }
    }
}
