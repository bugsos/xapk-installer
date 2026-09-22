package com.xapk.installer

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xapk.installer.databinding.ItemXapkBinding

class XapkListAdapter(
    private val onClick: (XapkInfo) -> Unit
) : RecyclerView.Adapter<XapkListAdapter.Holder>() {

    private val items = mutableListOf<XapkInfo>()

    fun submit(list: List<XapkInfo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemXapkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(private val b: ItemXapkBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(info: XapkInfo) {
            b.textFileName.text = info.file.name
            b.textSize.text = XapkInstaller.formatSize(info.file.length())

            if (info.parseError != null) {
                b.textMeta.text = "无法解析：${info.parseError}"
                b.textAbi.text = "—"
                b.textAbi.setTextColor(Color.parseColor("#B00020"))
                b.root.setOnClickListener { onClick(info) }
                return
            }

            val pkg = info.packageName ?: "未知包名"
            val ver = info.versionName?.let { v ->
                info.versionCode?.let { c -> "$v ($c)" } ?: v
            } ?: "未知版本"
            b.textMeta.text = "$pkg\n$ver · minSdk ${info.minSdk ?: "?"} · ${info.splits.size} 个 split"

            val compat = AbiCompat.check(info.requiredAbis)
            val abiText = if (info.requiredAbis.isEmpty()) {
                "ABI 通用"
            } else {
                info.requiredAbis.joinToString(" / ")
            }
            b.textAbi.text = if (compat.compatible) "✓ $abiText" else "✗ $abiText 不兼容"
            b.textAbi.setTextColor(
                Color.parseColor(if (compat.compatible) "#1B7F3B" else "#B00020")
            )

            b.root.setOnClickListener { onClick(info) }
        }
    }
}
