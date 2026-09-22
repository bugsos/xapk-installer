package com.xapk.installer

import java.io.File

/**
 * 一个 xapk 内参与安装的 apk 条目。
 *
 * @param entryName xapk（zip）内的条目名，同时也是写入 PackageInstaller session 时用的名字
 * @param size      未压缩大小
 * @param role      语义角色：base / config:<abi> / obbassets / other
 */
data class SplitEntry(
    val entryName: String,
    val size: Long,
    val role: String
)

/**
 * 一个已解析的 xapk 文件。
 *
 * [parseError] 非空表示 manifest.json 解析失败（例如不是合法 xapk），
 * 此时其余字段可能为 null，UI 仍应把文件列出来并显示原因。
 */
data class XapkInfo(
    val file: File,
    val displayName: String,
    val packageName: String?,
    val appName: String?,
    val versionName: String?,
    val versionCode: String?,
    val minSdk: String?,
    val splits: List<SplitEntry>,
    /** 该包对 ABI 的要求；空集表示未声明（通用包） */
    val requiredAbis: Set<String>,
    /** 声明可用的 ABI（用于展示，不等于 requiredAbis） */
    val declaredAbis: Set<String>,
    val parseError: String?
) {
    val totalSize: Long get() = splits.sumOf { it.size }
}
