package com.xapk.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * 接收 PackageInstaller 的结果回调。
 *
 * 这里必须处理一个容易踩的坑：**`STATUS_PENDING_USER_ACTION`（值 = -1）不是失败**。
 * 非系统应用走 Session API 安装时，系统会先把这个状态连同 `Intent.EXTRA_INTENT`
 * 一起回给我们，要求我们把那个确认界面拉起来给用户点。
 * 不拉起，安装就永远停在这一步（界面上会表现为一个莫名其妙的“失败 status=-1”）。
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.xapk.installer.INSTALL_RESULT"
        const val EXTRA_PACKAGE = "package"

        @Volatile
        private var listener: ((Boolean?, String) -> Unit)? = null

        /**
         * 结果暂存。安装确认界面弹出时本 Activity 会 onPause（listener 被清空），
         * 如果最终结果广播在 Activity 恢复之前到达，就会丢掉，所以先存下来供下次 onResume 取。
         */
        @Volatile
        private var pendingResult: Pair<Boolean, String>? = null

        fun setListener(l: ((Boolean?, String) -> Unit)?) {
            listener = l
        }

        fun consumePending(): Pair<Boolean, String>? {
            val p = pendingResult
            pendingResult = null
            return p
        }

        /** ok == null 表示“还没结束，只是中间状态”，界面不要把它当成最终结果 */
        private fun deliver(ok: Boolean?, message: String) {
            val l = listener
            if (l != null) {
                l(ok, message)
            } else if (ok != null) {
                pendingResult = ok to message
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)

        // 关键分支：系统在等用户点确认
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = extractConfirmIntent(intent)
            if (confirm == null) {
                deliver(false, "系统要求用户确认安装，但没有提供确认界面")
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val started = runCatching { context.startActivity(confirm) }.isSuccess
            if (started) {
                deliver(null, "等待你在系统界面上确认安装…")
            } else {
                deliver(false, "无法拉起系统安装确认界面")
            }
            return
        }

        val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val ok = status == PackageInstaller.STATUS_SUCCESS
        val message = if (ok) {
            "安装成功" + (pkg?.let { "：$it" } ?: "")
        } else {
            "安装失败（status=$status）" + (raw?.let { "：$it" } ?: "")
        }
        deliver(ok, message)
    }

    private fun extractConfirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
