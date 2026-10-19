package com.brother.netgate.noroot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 单个应用的控制项（按包名存储） */
class AppInfo(
    val pkg: String,
    val label: String,
    val icon: Drawable,
    var wifi: Boolean,
    var data: Boolean
)

class MainActivity : Activity() {

    private lateinit var sp: SharedPreferences
    private val apps = ArrayList<AppInfo>()
    private lateinit var adapter: AppAdapter
    private val REQ_VPN = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sp = getSharedPreferences("netgate", MODE_PRIVATE)
        adapter = AppAdapter(this, apps)
        findViewById<ListView>(R.id.listApps).adapter = adapter
        findViewById<Button>(R.id.btnPower).setOnClickListener { toggle() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearAll() }
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val on = FirewallVpn.alive
        findViewById<Button>(R.id.btnPower).text = if (on) "停止拦截" else "启动拦截"
        findViewById<TextView>(R.id.tvStatus).text =
            if (on) "拦截中·按当前网络类型生效" else "未启动（VPN模式·免Root）"
    }

    /** 启动走系统VPN授权，停止直接关服务 */
    private fun toggle() {
        if (FirewallVpn.alive) {
            stopService(Intent(this, FirewallVpn::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 300)
        } else {
            val prep = VpnService.prepare(this)
            if (prep != null) startActivityForResult(prep, REQ_VPN)
            else startVpn()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) startVpn()
        else if (requestCode == REQ_VPN) {
            Toast.makeText(this, "未授权VPN，无法启动拦截", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpn() {
        startService(Intent(this, FirewallVpn::class.java))
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 400)
    }

    /** 后台加载全部已安装应用 */
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val items = pm.getInstalledApplications(0)
                .filter { it.packageName != packageName }
                .map {
                    AppInfo(
                        it.packageName,
                        pm.getApplicationLabel(it).toString(),
                        pm.getApplicationIcon(it),
                        sp.getBoolean("wifi|${it.packageName}", false),
                        sp.getBoolean("data|${it.packageName}", false)
                    )
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                apps.clear(); apps.addAll(items); adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun clearAll() {
        sp.edit().clear().apply()
        apps.forEach { it.wifi = false; it.data = false }
        adapter.notifyDataSetChanged()
        FirewallVpn.ping(this)
        findViewById<TextView>(R.id.tvStatus).text = "已清除全部限制"
    }
}

/** 应用列表适配器：图标 + 名称 + WiFi开关 + 数据开关 */
class AppAdapter(
    private val ctx: Context,
    private val items: List<AppInfo>
) : BaseAdapter() {

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].pkg.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false)
        val a = items[position]

        v.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(a.icon)
        v.findViewById<TextView>(R.id.tvLabel).text = a.label
        v.findViewById<TextView>(R.id.tvSub).text = a.pkg

        // 复用行先解绑监听，防止开关串位
        val swW = v.findViewById<Switch>(R.id.swWifi)
        swW.setOnCheckedChangeListener(null)
        swW.isChecked = a.wifi
        swW.setOnCheckedChangeListener { _, on -> a.wifi = on; save(a) }

        val swD = v.findViewById<Switch>(R.id.swData)
        swD.setOnCheckedChangeListener(null)
        swD.isChecked = a.data
        swD.setOnCheckedChangeListener { _, on -> a.data = on; save(a) }
        return v
    }

    private fun save(a: AppInfo) {
        ctx.getSharedPreferences("netgate", Context.MODE_PRIVATE).edit()
            .putBoolean("wifi|${a.pkg}", a.wifi)
            .putBoolean("data|${a.pkg}", a.data)
            .apply()
        FirewallVpn.ping(ctx)   // 服务在跑就立即按新配置重建
    }
}