package com.xapk.installer

import android.os.Environment
import java.io.File

/**
 * 扫描设备存储上的 .xapk。
 *
 * 走 MANAGE_EXTERNAL_STORAGE（所有文件访问）而不是 SAF：
 * 前者一次授权后可按路径直接读，且安装时能拿 File 流式读取（1GB+ 的包用 SAF 会很难受）。
 */
object XapkScanner {

    /**
     * 这些目录要么扫不到（受分区存储限制），要么必然不含目标文件，
     * 递归进去只会拖慢扫描速度。
     */
    private val SKIP_DIR_NAMES = setOf(
        "Android",          // 内含 data/obb/media，受保护且不含 xapk
        ".thumbnails",
        ".cache",
        ".trash",
        "LOST.DIR",
        "MIUI",
        "backups"
    )

    private const val MAX_DEPTH = 8

    data class ScanResult(
        val items: List<XapkInfo>,
        val scannedDirs: Int,
        val elapsedMs: Long
    )

    fun scan(): ScanResult {
        val started = System.currentTimeMillis()
        val root = Environment.getExternalStorageDirectory()
        val found = mutableListOf<File>()
        var dirs = 0

        if (root.exists() && root.canRead()) {
            root.walkTopDown()
                .maxDepth(MAX_DEPTH)
                .onEnter { dir ->
                    if (dir == root) {
                        true
                    } else {
                        val ok = !shouldSkip(dir)
                        if (ok) dirs++
                        ok
                    }
                }
                .filter { it.isFile && isXapk(it) }
                .forEach { found.add(it) }
        }

        val items = found
            .sortedByDescending { it.lastModified() }
            .map { XapkParser.parse(it) }

        return ScanResult(items, dirs, System.currentTimeMillis() - started)
    }

    private fun isXapk(f: File): Boolean = f.name.endsWith(".xapk", ignoreCase = true)

    private fun shouldSkip(dir: File): Boolean {
        val name = dir.name
        if (name.startsWith(".")) return true
        return name in SKIP_DIR_NAMES
    }
}
