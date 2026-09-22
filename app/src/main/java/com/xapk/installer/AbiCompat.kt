package com.xapk.installer

import android.os.Build
import java.util.Locale

/**
 * ABI 兼容性预检。
 *
 * 存在的意义：MuMu Player Pro 之类的纯 64 位环境（abilist32 为空、无 /system/bin/linker）
 * 装 armeabi-v7a 包时，系统只会抛一句 INSTALL_FAILED_NO_MATCHING_ABIS，
 * 用户得自己反推原因。这里提前把结论算出来。
 */
object AbiCompat {

    /** 归一化 ABI 名：armeabi_v7a -> armeabi-v7a，arm64_v8a -> arm64-v8a */
    fun normalizeAbi(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace('_', '-')

    /**
     * 从 split 的 id/文件名里提取 ABI。
     * XAPK 规范里 ABI split 固定叫 `config.<abi>`，例如 `config.armeabi_v7a`、`config.arm64_v8a`。
     */
    fun abiFromSplitId(idOrName: String): String? {
        val n = idOrName.trim().lowercase(Locale.ROOT).replace('_', '-')
        return when {
            n.contains("arm64-v8a") -> "arm64-v8a"
            n.contains("armeabi-v7a") -> "armeabi-v7a"
            n.contains("x86-64") -> "x86_64"
            n.contains("x86") -> "x86"
            else -> null
        }
    }

    data class Result(
        val compatible: Boolean,
        /** 一句话结论，直接展示给用户 */
        val headline: String,
        /** 补充说明（原因/建议），可能为空 */
        val detail: String
    )

    fun check(requiredAbis: Set<String>): Result {
        if (requiredAbis.isEmpty()) {
            return Result(true, "通用包（未声明 ABI 限制）", "")
        }

        val deviceAll = Build.SUPPORTED_ABIS.toList()
        val device32 = Build.SUPPORTED_32_BIT_ABIS.toSet()
        val device64 = Build.SUPPORTED_64_BIT_ABIS.toSet()

        val matched = requiredAbis.filter { it in deviceAll }
        if (matched.isNotEmpty()) {
            return Result(
                true,
                "ABI 匹配：${matched.joinToString(" / ")}",
                "本机支持：${deviceAll.joinToString(" / ")}"
            )
        }

        // 不匹配——判断最可能的原因
        val wanted32 = requiredAbis.filter { it in REQUIRED_32 }
        val wanted64 = requiredAbis.filter { it in REQUIRED_64 }
        val detail = when {
            wanted32.isNotEmpty() && wanted64.isEmpty() && device32.isEmpty() ->
                "本机是纯 64 位环境（SUPPORTED_32_BIT_ABIS 为空，系统里没有 /system/bin/linker），" +
                    "无法运行任何 32 位原生库。只能换该应用的 arm64-v8a 版本。"

            wanted64.isNotEmpty() && wanted32.isEmpty() && device64.isEmpty() ->
                "本机不支持 64 位原生库，只能换 32 位版本。"

            else ->
                "包声明的 ABI 与本机支持列表没有交集，系统会拒绝安装（INSTALL_FAILED_NO_MATCHING_ABIS）。"
        }
        return Result(false, "ABI 不兼容：包需要 ${requiredAbis.joinToString(" / ")}", detail)
    }

    private val REQUIRED_32 = setOf("armeabi-v7a", "armeabi", "x86")
    private val REQUIRED_64 = setOf("arm64-v8a", "x86_64")

    /** 供 UI 显示的设备 ABI 概览 */
    fun deviceSummary(): String {
        val s = StringBuilder()
        s.append("本机 ABI：").append(Build.SUPPORTED_ABIS.joinToString(" / "))
        s.append("\n32 位：").append(
            if (Build.SUPPORTED_32_BIT_ABIS.isEmpty()) "不支持" else Build.SUPPORTED_32_BIT_ABIS.joinToString(" / ")
        )
        s.append("\n64 位：").append(
            if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) "不支持" else Build.SUPPORTED_64_BIT_ABIS.joinToString(" / ")
        )
        return s.toString()
    }
}
