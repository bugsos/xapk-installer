package com.xapk.installer

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * 一个已安装的应用。
 *
 * @param appName    桌面上看到的显示名；取不到（例如某些无 label 的系统组件）时回退成包名
 * @param isSystem   是否为系统镜像自带的应用，判定依据是 `ApplicationInfo.FLAG_SYSTEM`。
 *                   被用户更新过的系统应用带 `FLAG_UPDATED_SYSTEM_APP`，同时**也带** `FLAG_SYSTEM`，
 *                   所以仍算作系统应用（与 `pm list packages -s` 的口径一致）。
 * @param launchable 桌面上有没有它的图标，即能不能直接启动。判定依据是
 *                   `PackageManager.getLaunchIntentForPackage()` 是否返回非空——只有声明了
 *                   `ACTION_MAIN` + `CATEGORY_LAUNCHER` 入口、且该入口当前处于启用状态的包才有。
 *                   纯后台组件（服务、Provider、overlay、共享库型系统包）一律为 false，
 *                   这也是列表里「打开」按钮显不显的依据。
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val launchable: Boolean
)

object InstalledApps {

    /**
     * 读取设备上已安装的应用，按包名升序返回。
     *
     * ⚠ Android 11（API 30）起官方引入了「包可见性」过滤：targetSdk ≥ 30 的应用若既没声明
     * `QUERY_ALL_PACKAGES` 也没写 `<queries>`，查询其他应用信息的 API 只返回过滤后的子集
     * （官方文档点名举例 `getInstalledApplications()`）。本工具要列的正是完整包名清单，
     * 所以 Manifest 里声明了 `QUERY_ALL_PACKAGES`。
     *
     * 实测记录（MuMu Player Pro / Android 12 / 2026-09-23）：**这台模拟器上过滤没有生效**。
     * 声明与不声明权限 × `getInstalledPackages()` 与 `getInstalledApplications()` 四种组合，
     * 都返回同样的 73 个包（设备侧 `pm list packages` 只列 67 个，差额是 5 个
     * `/system/product/overlay/` 下的刘海屏 overlay 包和 1 个 MuMu priv-app，属 pm 默认不列的类型）。
     * 也就是说本机无法验证该权限的作用，保留声明是依据官方行为、针对**真机**——
     * 真机上不加会漏包。同一个过滤也会作用在 `getLaunchIntentForPackage()` 上：
     * 真机上不可见的包会返回 null，从而被误判成「无启动入口」。
     *
     * 读取会遍历所有包并加载 label，属于耗时操作，必须在后台线程调用。
     */
    fun load(pm: PackageManager): List<InstalledApp> {
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        }

        return infos.mapNotNull { toApp(pm, it) }
            .sortedBy { it.packageName.lowercase() }
    }

    private fun toApp(pm: PackageManager, pi: PackageInfo): InstalledApp? {
        val ai = pi.applicationInfo ?: return null
        val label = runCatching { ai.loadLabel(pm).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return InstalledApp(
            packageName = pi.packageName,
            appName = label ?: pi.packageName,
            versionName = pi.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION") pi.versionCode.toLong()
            },
            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            launchable = pm.getLaunchIntentForPackage(pi.packageName) != null
        )
    }
}
