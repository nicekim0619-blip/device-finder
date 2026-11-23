package com.hi.devicefinder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity() {

    /** 찾은 기기 하나 */
    class Dev(val address: String, var name: String, var rssi: Double, var lastSeen: Long) {
        var phone = false
        val angle: Double = (abs(address.hashCode()) % 360) * Math.PI / 180.0
        /** 신호 세기 → 대략적인 거리(m). -59dBm ≈ 1m 기준 */
        val distance: Double get() = 10.0.pow((-59.0 - rssi) / (10 * 2.2))
        val displayName: String get() = name.ifBlank { "이름 없는 기기" }
    }

    private val devices = LinkedHashMap<String, Dev>()
    private var trackedAddr: String? = null
    private var scanning = false
    private var lastVibrate = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    // 화면 부품
    private lateinit var radar: RadarView
    private lateinit var scanBtn: Button
    private lateinit var status: TextView
    private lateinit var targetBox: LinearLayout
    private lateinit var tName: TextView
    private lateinit var tDist: TextView
    private lateinit var tTemp: TextView
    private lateinit var listBox: LinearLayout

    private val green = Color.rgb(125, 255, 170)
    private val dim = Color.rgb(111, 174, 134)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    // ───────────────────────── 화면 만들기 ─────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.rgb(10, 31, 19)); cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.rgb(29, 90, 53))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.rgb(5, 17, 11))
        }
        root.addView(text(24f, Color.rgb(77, 255, 143), true).apply {
            text = "📡 기기 탐지기"; gravity = Gravity.CENTER
        })
        root.addView(text(13f, dim).apply {
            text = "주변 블루투스 기기를 찾아 거리를 알려줘요"; gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(10))
        })

        radar = RadarView(this)
        root.addView(radar, LinearLayout.LayoutParams(-1, -2))

        scanBtn = Button(this).apply {
            text = "🌐 주변 스캔 시작"; textSize = 17f; setTextColor(green); isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.rgb(13, 43, 26)); cornerRadius = dp(12).toFloat()
                setStroke(dp(2), Color.rgb(43, 212, 110))
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(14) }
            setOnClickListener { if (scanning) stopScan() else startScanFlow() }
        }
        root.addView(scanBtn)

        status = text(13f, Color.rgb(255, 210, 122)).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(status)

        targetBox = panel().apply { gravity = Gravity.CENTER_HORIZONTAL; visibility = android.view.View.GONE }
        tName = text(18f, Color.WHITE, true)
        tDist = text(40f, Color.WHITE, true)
        tTemp = text(17f, Color.WHITE, true)
        targetBox.addView(tName); targetBox.addView(tDist); targetBox.addView(tTemp)
        targetBox.addView(text(12f, dim).apply {
            text = "폰을 들고 천천히 걸어 보세요. 숫자가 작아지는 쪽에 기기가 있어요.\n(목록에서 다시 누르면 추적 해제)"
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
        root.addView(targetBox)

        val listPanel = panel()
        listPanel.addView(text(15f, Color.WHITE, true).apply { text = "찾은 기기 (누르면 추적)" })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listPanel.addView(listBox)
        root.addView(listPanel)

        root.addView(panel().apply {
            addView(text(12f, dim).apply {
                text = "🟢 일반 기기   🟡 핸드폰   🔴 추적 중\n" +
                    "⚠️ 신호 세기로 거리만 대략 알 수 있어요. 레이더 점의 각도는 진짜 방향이 아니에요.\n" +
                    "📱 다른 폰을 찾으려면 그 폰에서 블루투스 설정 화면을 켜 두세요."
            })
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(5, 17, 11)); addView(root)
        })
        renderList()
    }

    // ───────────────────────── 권한 & 시작 ─────────────────────────
    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startScanFlow() {
        val adapter = btAdapter
        if (adapter == null) { status.text = "이 폰에는 블루투스가 없어요."; return }
        if (!hasPerms()) { requestPermissions(neededPerms(), 1); return }
        if (!adapter.isEnabled) {
            status.text = "블루투스를 켜 주세요."
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: SecurityException) {}
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            status.text = "위치(GPS)를 켜 주세요. 안드로이드는 위치가 꺼져 있으면 블루투스 검색이 안 돼요."
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        startScan()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasPerms()) startScanFlow()
        else status.text = "권한을 허용해야 기기를 찾을 수 있어요.\n설정 → 애플리케이션 → 기기 탐지기 → 권한에서 '근처 기기'와 '위치'를 허용해 주세요."
    }

    // ───────────────────────── 스캔 ─────────────────────────
    @SuppressLint("MissingPermission")
    private fun startScan() {
        val adapter = btAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: run { status.text = "스캔을 시작할 수 없어요."; return }
        try {
            // 1) BLE 스캔: 이어폰, 워치, 태그 등
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, bleCallback)
            // 2) 클래식 검색: '검색 가능' 상태의 핸드폰 등
            registerReceiver(classicReceiver, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            })
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            status.text = "권한 문제로 스캔을 못 했어요: ${e.message}"; return
        }
        scanning = true
        radar.sweeping = true
        scanBtn.text = "■ 스캔 멈추기"
        status.text = "스캔 중... 주변 기기가 레이더에 나타나요."
        handler.post(tick)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            btAdapter?.bluetoothLeScanner?.stopScan(bleCallback)
            btAdapter?.cancelDiscovery()
        } catch (_: SecurityException) {}
        try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(tick)
        radar.sweeping = false
        scanBtn.text = "🌐 주변 스캔 시작"
        status.text = "멈췄어요."
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onSeen(result.device.address, result.scanRecord?.deviceName ?: safeName(result.device), result.rssi, isPhone(result.device))
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { status.text = "스캔 실패 (오류 $errorCode). 잠깐 기다렸다가 다시 눌러 보세요." }
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (rssi == Short.MIN_VALUE.toInt()) return
                    onSeen(dev.address, safeName(dev), rssi, isPhone(dev))
                }
                // 클래식 검색은 12초쯤 하고 끝나서, 스캔 중이면 다시 시작
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (scanning) try { btAdapter?.startDiscovery() } catch (_: SecurityException) {}
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String? = try { d.name } catch (_: SecurityException) { null }

    @SuppressLint("MissingPermission")
    private fun isPhone(d: BluetoothDevice): Boolean = try {
        d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
    } catch (_: SecurityException) { false }

    private fun onSeen(addr: String, name: String?, rssi: Int, phone: Boolean) {
        runOnUiThread {
            val now = SystemClock.elapsedRealtime()
            val d = devices.getOrPut(addr) { Dev(addr, name ?: "", rssi.toDouble(), now) }
            if (!name.isNullOrBlank()) d.name = name
            d.rssi = d.rssi * 0.6 + rssi * 0.4   // 흔들림 줄이기
            d.lastSeen = now
            if (phone) d.phone = true
        }
    }

    // ───────────────────────── 화면 갱신 (0.5초마다) ─────────────────────────
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            devices.values.removeAll { now - it.lastSeen > 30_000 && it.address != trackedAddr }
            radar.blips = devices.values.map {
                val age = (now - it.lastSeen) / 1000f
                RadarView.Blip(it.displayName, it.distance, it.angle,
                    (1f - age / 30f).coerceIn(0.25f, 1f), it.address == trackedAddr, it.phone)
            }
            renderList()
            renderTarget()
            if (scanning) handler.postDelayed(this, 500)
        }
    }

    private fun fmt(m: Double) = if (m < 1) "약 %.1fm".format(m) else "약 ${m.roundToInt()}m"

    private fun renderList() {
        listBox.removeAllViews()
        val sorted = devices.values.sortedBy { it.distance }
        if (sorted.isEmpty()) {
            listBox.addView(text(13f, dim).apply {
                text = "아직 없어요. 스캔을 시작해 보세요."; setPadding(0, dp(8), 0, 0)
            })
            return
        }
        for (d in sorted.take(40)) {
            val row = text(15f, if (d.address == trackedAddr) Color.rgb(255, 120, 120) else Color.WHITE).apply {
                val icon = if (d.phone) "📱 " else ""
                text = "$icon${d.displayName}\n   ${fmt(d.distance)} · ${d.rssi.roundToInt()}dBm"
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener {
                    trackedAddr = if (trackedAddr == d.address) null else d.address
                    renderTarget(); renderList()
                }
            }
            listBox.addView(row)
        }
    }

    private fun renderTarget() {
        val d = trackedAddr?.let { devices[it] }
        if (d == null) { targetBox.visibility = android.view.View.GONE; return }
        targetBox.visibility = android.view.View.VISIBLE
        tName.text = d.displayName
        tDist.text = fmt(d.distance)
        val heat = ((d.rssi + 100) / 60).coerceIn(0.0, 1.0)   // -100 ~ -40 dBm
        val (label, col) = when {
            heat > .8 -> "🔥 바로 옆이에요!" to Color.rgb(255, 58, 58)
            heat > .6 -> "♨️ 뜨거워요" to Color.rgb(255, 122, 58)
            heat > .4 -> "🌤 따뜻해요" to Color.rgb(255, 210, 58)
            heat > .2 -> "❄️ 차가워요" to Color.rgb(106, 180, 255)
            else -> "🧊 아주 멀어요" to Color.rgb(58, 123, 255)
        }
        tTemp.text = label; tTemp.setTextColor(col); tDist.setTextColor(col)
        val now = SystemClock.elapsedRealtime()
        if (heat > .8 && now - lastVibrate > 1500) {
            lastVibrate = now
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }
}
