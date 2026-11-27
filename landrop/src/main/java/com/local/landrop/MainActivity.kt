package com.local.landrop

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var server: FileServer? = null
    private lateinit var urlText: TextView
    private lateinit var latestText: TextView
    private lateinit var logText: TextView
    private var lastReceivedString: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupUi()
        checkLegacyStoragePermission()
        startServer()
    }

    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        val tip = TextView(this).apply {
            text = "电脑浏览器打开以下地址（需同一 Wi-Fi 或连接本机热点）："
            textSize = 14f
            setTextColor(Color.parseColor("#4B5563"))
        }

        urlText = TextView(this).apply {
            text = "正在获取局域网地址..."
            textSize = 22f
            setTextColor(Color.parseColor("#2563EB"))
            setPadding(0, 16, 0, 24)
        }

        val refreshBtn = Button(this).apply {
            text = "刷新 IP 地址"
            setOnClickListener { updateIpDisplay() }
        }

        val textHeader = TextView(this).apply {
            text = "最新收到的文本："
            textSize = 14f
            setPadding(0, 32, 0, 8)
            setTextColor(Color.parseColor("#4B5563"))
        }

        latestText = TextView(this).apply {
            text = "暂无"
            textSize = 15f
            setBackgroundColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }

        val copyBtn = Button(this).apply {
            text = "复制上方文本"
            setOnClickListener {
                if (lastReceivedString.isNotEmpty()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("LanDrop", lastReceivedString))
                    Toast.makeText(this@MainActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val logHeader = TextView(this).apply {
            text = "传输记录（文件保存在 Download 目录）："
            textSize = 14f
            setPadding(0, 32, 0, 8)
            setTextColor(Color.parseColor("#4B5563"))
        }

        logText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#374151"))
        }

        val scroll = ScrollView(this).apply { addView(logText) }

        root.addView(tip)
        root.addView(urlText)
        root.addView(refreshBtn)
        root.addView(textHeader)
        root.addView(latestText)
        root.addView(copyBtn)
        root.addView(logHeader)
        root.addView(scroll)

        setContentView(root)
    }

    private fun checkLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    100
                )
            }
        }
    }

    private fun startServer() {
        try {
            server = FileServer(
                context = applicationContext,
                port = 8080,
                onLog = { msg ->
                    runOnUiThread {
                        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        logText.text = "[$time] $msg\n${logText.text}"
                    }
                },
                onTextReceived = { text ->
                    runOnUiThread {
                        lastReceivedString = text
                        latestText.text = text
                    }
                }
            ).apply { start() }
            updateIpDisplay()
        } catch (e: Exception) {
            urlText.text = "服务启动失败: ${e.message}"
        }
    }

    private fun updateIpDisplay() {
        val ip = getLocalIpAddress()
        urlText.text = if (ip != null) "http://$ip:8080" else "未检测到局域网 IP（请检查 Wi-Fi 或热点）"
    }

    private fun getLocalIpAddress(): String? {
        var fallback: String? = null
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback) continue
            for (addr in intf.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (intf.name.startsWith("wlan") || intf.name.startsWith("ap") || intf.name.startsWith("swlan")) {
                        return ip
                    }
                    if (addr.isSiteLocalAddress) fallback = ip
                }
            }
        }
        return fallback
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }
}
