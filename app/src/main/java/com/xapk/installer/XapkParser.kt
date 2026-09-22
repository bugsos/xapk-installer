package com.xapk.installer

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 解析 xapk（本质上是个 zip）。
 *
 * xapk 内部结构（XAPK v2）：
 *   manifest.json          元数据：package_name / version_name / split_apks ...
 *   <base>.apk             base apk
 *   config.<abi>.apk       ABI split（原生库）
 *   config.<density>.apk   密度 split
 *   obbassets.apk          OBB 资源（以 split 形式承载，内含 assets/main.obb...）
 *
 * 安装时不需要把任何东西解压到磁盘——直接按 entry 流式读进 PackageInstaller session。
 */
object XapkParser {

    private const val TAG = "XapkParser"
    private const val MANIFEST = "manifest.json"

    fun parse(file: File): XapkInfo {
        try {
            ZipFile(file).use { zip ->
                val apkEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .toList()

                if (apkEntries.isEmpty()) {
                    return failed(file, "xapk 内没有找到任何 .apk 条目")
                }

                val manifestEntry = zip.getEntry(MANIFEST)
                val meta = manifestEntry?.let { readJson(zip, it) }

                // split id -> entryName 的映射（manifest 里的 split_apks 带 id，比文件名可靠）
                val idByFile = mutableMapOf<String, String>()
                meta?.optJSONArray("split_apks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val fn = o.optString("file")
                        val id = o.optString("id")
                        if (fn.isNotEmpty()) idByFile[fn] = id
                    }
                }

                val splits = apkEntries.map { entry ->
                    val id = idByFile[entry.name] ?: entry.name
                    SplitEntry(
                        entryName = entry.name,
                        size = entry.size,
                        role = roleOf(id, entry.name)
                    )
                }

                // ABI：优先看 split id（config.<abi>），其次看 split 文件名
                val declaredAbis = splits.mapNotNull { sp ->
                    AbiCompat.abiFromSplitId(sp.entryName)
                }.toSet()

                // 没有任何 ABI split 时，可能是 universal apk（原生库直接躺在 base 里）。
                // 这种情况才需要真的读一次 base apk 的 lib/ 目录。
                val requiredAbis = if (declaredAbis.isNotEmpty()) {
                    declaredAbis
                } else {
                    detectAbiFromUniversalApk(zip, apkEntries)
                }

                return XapkInfo(
                    file = file,
                    displayName = file.name,
                    packageName = meta?.optString("package_name")?.takeIf { it.isNotEmpty() },
                    appName = meta?.optString("name")?.takeIf { it.isNotEmpty() },
                    versionName = meta?.optString("version_name")?.takeIf { it.isNotEmpty() },
                    versionCode = meta?.optString("version_code")?.takeIf { it.isNotEmpty() },
                    minSdk = meta?.optString("min_sdk_version")?.takeIf { it.isNotEmpty() },
                    splits = splits,
                    requiredAbis = requiredAbis,
                    declaredAbis = declaredAbis,
                    parseError = null
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "解析失败: ${file.absolutePath}", t)
            return failed(file, "解析失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun failed(file: File, why: String) = XapkInfo(
        file = file,
        displayName = file.name,
        packageName = null,
        appName = null,
        versionName = null,
        versionCode = null,
        minSdk = null,
        splits = emptyList(),
        requiredAbis = emptySet(),
        declaredAbis = emptySet(),
        parseError = why
    )

    private fun roleOf(id: String, fileName: String): String {
        val n = id.lowercase()
        return when {
            n == "base" -> "base"
            n.contains("obb") -> "obbassets"
            AbiCompat.abiFromSplitId(n) != null -> "config:${AbiCompat.abiFromSplitId(n)}"
            n.startsWith("config.") -> "config"
            fileName.equals("base.apk", ignoreCase = true) -> "base"
            else -> "other"
        }
    }

    /** 读 xapk 内的小文件（manifest.json 只有几 KB） */
    private fun readJson(zip: ZipFile, entry: ZipEntry): JSONObject? = try {
        zip.getInputStream(entry).use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
    } catch (t: Throwable) {
        Log.w(TAG, "manifest.json 解析失败", t)
        null
    }

    /**
     * 单 apk（universal）场景：把 <u>base apk 单独解出来</u>才能看它内部的 lib/ 目录，
     * 因为嵌套 zip 的中央目录无法从外层流里直接定位。
     * 只对 base 做这件事，且只在没有 ABI split 时才发生。
     */
    private fun detectAbiFromUniversalApk(zip: ZipFile, apkEntries: List<ZipEntry>): Set<String> {
        // 挑最小的那个 apk 来查（universal 包里它就是唯一/主要的那个）
        val candidate = apkEntries.minByOrNull { it.size } ?: return emptySet()
        if (candidate.size > UNIVERSAL_SCAN_LIMIT) return emptySet()

        var tmp: File? = null
        return try {
            tmp = File.createTempFile("abi-probe", ".apk")
            zip.getInputStream(candidate).use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            ZipFile(tmp).use { inner ->
                inner.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") }
                    .mapNotNull { path -> path.split('/').getOrNull(1) }
                    .filter { it.isNotEmpty() }
                    .map { it.lowercase().replace('_', '-') }
                    .toSet()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "探测 universal apk 的 ABI 失败", t)
            emptySet()
        } finally {
            tmp?.delete()
        }
    }

    /** 超过这个大小就不做 universal ABI 探测了，避免为了预检搬运几百 MB */
    private const val UNIVERSAL_SCAN_LIMIT = 400L * 1024 * 1024
}
