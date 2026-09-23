package com.xapk.installer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.xapk.installer.databinding.ItemAppBinding

/**
 * @param onOpen 点「打开」的回调，交给 Activity 去真正 startActivity。
 */
class AppListAdapter(private val onOpen: (InstalledApp) -> Unit) :
    RecyclerView.Adapter<AppListAdapter.Holder>() {

    private val items = mutableListOf<InstalledApp>()

    fun submit(list: List<InstalledApp>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(private val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root) {

        fun bind(app: InstalledApp) {
            b.textPackageName.text = app.packageName
            val ver = app.versionName?.let { "$it (${app.versionCode})" }
                ?: "versionCode ${app.versionCode}"
            b.textAppMeta.text =
                if (app.appName == app.packageName) ver else "${app.appName} · $ver"

            if (app.launchable) {
                b.buttonOpen.visibility = View.VISIBLE
                b.textNoLaunch.visibility = View.GONE
                b.buttonOpen.setOnClickListener { onOpen(app) }
            } else {
                // 复用时必须摘掉监听器，否则滚动后点别的行会启动上一个包
                b.buttonOpen.setOnClickListener(null)
                b.buttonOpen.visibility = View.GONE
                b.textNoLaunch.visibility = View.VISIBLE
            }
        }
    }
}
