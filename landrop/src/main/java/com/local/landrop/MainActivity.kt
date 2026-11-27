package com.local.landrop

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
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

class MainActivity : AppCompatActivity() {

    private var server: FileServer? = null
    private lateinit var urlText: TextView
    private lateinit var textSection: LinearLayout
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
            setPadding(48, 48, 48, 36)
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

        // 最新收到的文本模块（未收到文本时整体隐藏）
        textSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val textHeader = TextView(this).apply {
            text = "最新收到的文本："
            textSize = 14f
            setPadding(0, 32, 0, 8)
            setTextColor(Color.parseColor("#4B5563"))
        }

        latestText = TextView(this).apply {
            text = ""
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

        textSection.addView(textHeader)
        textSection.addView(latestText)
        textSection.addView(copyBtn)

        // 传输记录标题（Download 蓝色带下划线，点击打开文件管理器的 Download 文件夹）
        val headerFullText = "传输记录（文件保存在 Download 目录）："
        val spannable = SpannableString(headerFullText)
        val linkWord = "Download"
        val startIdx = headerFullText.indexOf(linkWord)
        if (startIdx >= 0) {
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        openDownloadFolder()
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = Color.parseColor("#2563EB")
                        ds.isUnderlineText = true
                    }
                },
                startIdx,
                startIdx + linkWord.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val logHeader = TextView(this).apply {
            text = spannable
            textSize = 14f
            setPadding(0, 32, 0, 8)
            setTextColor(Color.parseColor("#4B5563"))
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }

        logText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#374151"))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(logText)
        }

        // 页面底部居中显示浅色版本号
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
        val versionText = TextView(this).apply {
            text = "v$versionName"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 20, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(tip)
        root.addView(urlText)
        root.addView(refreshBtn)
        root.addView(textSection)
        root.addView(logHeader)
        root.addView(scroll)
        root.addView(versionText)

        setContentView(root)
    }

    private fun openDownloadFolder() {
        val intents = listOf(
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                    "vnd.android.document/directory"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // try next
            }
        }
        Toast.makeText(this, "未找到可用的文件管理器", Toast.LENGTH_SHORT).show()
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
                onLog = { time, msg ->
                    runOnUiThread {
                        logText.text = "[$time] $msg\n${logText.text}"
                    }
                },
                onTextReceived = { text ->
                    runOnUiThread {
                        textSection.visibility = View.VISIBLE
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
