package com.xapk.installer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.xapk.installer.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: XapkListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = XapkListAdapter { info -> showDetail(info) }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.buttonScan.setOnClickListener { startScan() }
        binding.buttonGrant.setOnClickListener { grantNextMissing() }

        binding.textDeviceAbi.text = AbiCompat.deviceSummary()
        setStatus("就绪，点「扫描 XAPK」开始", null)
    }

    override fun onResume() {
        super.onResume()
        // 安装过程中会跳到系统的确认界面，回来时靠这个回调收结果。
        // ok == null 表示只是中间状态（例如在等用户确认），进度条要留着。
        InstallResultReceiver.setListener { ok, msg ->
            runOnUiThread {
                if (ok != null) binding.progressBar.visibility = View.GONE
                setStatus(msg, ok)
            }
        }
        // 确认界面的广播可能在本 Activity 恢复之前就到了，补收一次
        InstallResultReceiver.consumePending()?.let { (ok, msg) ->
            binding.progressBar.visibility = View.GONE
            setStatus(msg, ok)
        }
        refreshPermissionState()
    }

    override fun onPause() {
        super.onPause()
        InstallResultReceiver.setListener(null)
    }

    // ---------------- 权限 ----------------

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun hasInstallPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    private fun refreshPermissionState() {
        val storeOk = hasStoragePermission()
        val installOk = hasInstallPermission()

        val missing = buildList {
            if (!storeOk) add("文件访问权限")
            if (!installOk) add("安装未知应用")
        }

        if (missing.isEmpty()) {
            binding.permissionBar.visibility = View.GONE
        } else {
            binding.permissionBar.visibility = View.VISIBLE
            binding.textPermission.text = "缺少权限：" + missing.joinToString("、")
        }
        binding.buttonScan.isEnabled = storeOk
    }

    private fun grantNextMissing() {
        if (!hasStoragePermission()) {
            requestStoragePermission()
        } else if (!hasInstallPermission()) {
            requestInstallPermission()
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:$packageName"))
            runCatching { startActivity(appIntent) }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    private fun requestInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
        }
    }

    // ---------------- 扫描 ----------------

    private fun startScan() {
        if (!hasStoragePermission()) {
            grantNextMissing()
            return
        }

        binding.buttonScan.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        setStatus("正在扫描存储…", null)

        thread(name = "xapk-scan") {
            val result = XapkScanner.scan()
            runOnUiThread {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.visibility = View.GONE
                binding.buttonScan.isEnabled = true
                adapter.submit(result.items)
                setStatus(
                    "扫到 ${result.items.size} 个 xapk（遍历 ${result.scannedDirs} 个目录，耗时 ${result.elapsedMs} ms）",
                    null
                )
            }
        }
    }

    // ---------------- 详情与安装 ----------------

    private fun showDetail(info: XapkInfo) {
        val sb = StringBuilder()
        sb.append("文件：").append(info.file.name).append('\n')
        sb.append("大小：").append(XapkInstaller.formatSize(info.file.length())).append('\n')
        sb.append("路径：").append(info.file.absolutePath).append("\n\n")

        if (info.parseError != null) {
            sb.append("解析失败：").append(info.parseError)
            AlertDialog.Builder(this)
                .setTitle("无法解析")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show()
            return
        }

        sb.append("包名：").append(info.packageName ?: "未知").append('\n')
        sb.append("应用：").append(info.appName ?: "未知").append('\n')
        sb.append("版本：").append(info.versionName ?: "?")
            .append(" (").append(info.versionCode ?: "?").append(")\n")
        sb.append("minSdk：").append(info.minSdk ?: "?").append("\n\n")

        sb.append("包含 ").append(info.splits.size).append(" 个 apk：\n")
        info.splits.forEach { s ->
            sb.append("  · ").append(s.entryName)
                .append("  [").append(s.role).append("]  ")
                .append(XapkInstaller.formatSize(s.size)).append('\n')
        }

        val compat = AbiCompat.check(info.requiredAbis)
        sb.append("\nABI 预检：").append(if (compat.compatible) "通过" else "不通过").append('\n')
        sb.append(compat.headline)
        if (compat.detail.isNotEmpty()) {
            sb.append('\n').append(compat.detail)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("安装 ${info.packageName ?: info.file.name}")
            .setMessage(sb.toString())
            .setNegativeButton("取消", null)

        if (compat.compatible) {
            builder.setPositiveButton("安装") { _, _ -> doInstall(info) }
        } else {
            // 预检不通过仍然给一条出路：万一 ABI 探测有偏差，用户还能自己试一次
            builder.setPositiveButton("仍要尝试") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("确认强制尝试？")
                    .setMessage("ABI 预检判定不兼容，系统大概率会直接拒绝（INSTALL_FAILED_NO_MATCHING_ABIS）。要提交安装请求吗？")
                    .setPositiveButton("提交") { _, _ -> doInstall(info) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        builder.show()
    }

    private fun doInstall(info: XapkInfo) {
        if (!hasInstallPermission()) {
            AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("请先允许「安装未知应用」，否则系统会直接拒绝安装请求。")
                .setPositiveButton("去授权") { _, _ -> requestInstallPermission() }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = 100
        binding.progressBar.progress = 0
        setStatus("准备安装…", null)

        XapkInstaller(this).install(
            info = info,
            onProgress = { p ->
                runOnUiThread {
                    binding.progressBar.progress = p.percent
                    setStatus(p.message, null)
                }
            },
            onFinished = { ok, msg ->
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    setStatus(msg, ok)
                }
            }
        )
    }

    private fun setStatus(msg: String, ok: Boolean?) {
        binding.textStatus.text = msg
        binding.textStatus.setTextColor(
            when (ok) {
                true -> Color.parseColor("#1B7F3B")
                false -> Color.parseColor("#B00020")
                null -> Color.parseColor("#666666")
            }
        )
    }

    companion object {
        private const val REQ_STORAGE = 1001
    }
}
