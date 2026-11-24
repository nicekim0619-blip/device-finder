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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
        var maker: String? = null      // 제조사 (예: 애플)
        var kind: String? = null       // 기기 종류 추측 (예: 에어팟)
        val recent = ArrayDeque<Int>()  // 최근 신호 세기들
        val angle: Double = (abs(address.hashCode()) % 360) * Math.PI / 180.0
        /** 신호 세기 → 대략적인 거리(m). -59dBm ≈ 1m 기준 */
        val distance: Double get() = 10.0.pow((-59.0 - rssi) / (10 * 2.2))
        val guess: String get() = listOfNotNull(maker, kind).joinToString(" · ")
        val displayName: String get() = when {
            name.isNotBlank() -> name
            guess.isNotBlank() -> guess
            else -> "정체불명 기기"
        }
    }

    // ───────────────────────── 기기 정체 추측 ─────────────────────────
    /** 블루투스 제조사 번호 → 이름 */
    private val makers = mapOf(
        0x004C to "애플", 0x0075 to "삼성", 0x0006 to "마이크로소프트", 0x00E0 to "구글",
        0x00C4 to "LG", 0x012D to "소니", 0x009E to "보스", 0x0087 to "가민",
        0x027D to "화웨이", 0x038F to "샤오미", 0x0157 to "샤오미(미밴드)", 0x0171 to "아마존",
        0x0059 to "노르딕 칩(소형 기기)", 0x0002 to "인텔", 0x000A to "퀄컴(이어폰·스피커 칩)"
    )

    /** 신호 내용을 보고 제조사와 종류를 추측 */
    private fun guessFromScan(r: ScanResult, d: Dev) {
        val rec = r.scanRecord ?: return
        val mfg = rec.manufacturerSpecificData
        for (i in 0 until (mfg?.size() ?: 0)) {
            val id = mfg!!.keyAt(i)
            val data = mfg.valueAt(i)
            makers[id]?.let { d.maker = it }
            val first = data?.firstOrNull()?.toInt()?.and(0xFF)
            when (id) {
                0x004C -> d.kind = when (first) {          // 애플 신호 종류
                    0x07 -> "에어팟/비츠 이어폰"
                    0x10 -> "아이폰/아이패드/맥"
                    0x12 -> "에어태그 또는 '나의 찾기' 기기"
                    0x09 -> "애플TV/에어플레이"
                    0x0C -> "아이폰/맥 (핸드오프)"
                    0x02 -> "아이비콘(매장 비콘)"
                    else -> d.kind ?: "애플 기기"
                }
                0x0006 -> d.kind = "윈도우 PC/노트북"
                0x0075 -> if (d.kind == null) d.kind = "갤럭시 기기"
            }
        }
        if (d.kind?.startsWith("아이폰") == true) d.phone = true
        rec.serviceUuids?.forEach { u ->
            val s = u.uuid.toString().substring(4, 8).uppercase()
            when (s) {
                "FD5A", "FD59" -> { d.maker = "삼성"; d.kind = "스마트태그" }
                "FEED" -> d.kind = "타일 분실방지 태그"
                "FEAA" -> d.kind = "구글 비콘"
                "FE9F", "FE2C" -> { if (d.maker == null) d.maker = "구글"; if (d.kind == null) d.kind = "안드로이드 기기" }
                "180D" -> d.kind = "심박계/운동 기기"
                "1812" -> d.kind = "키보드/마우스/컨트롤러"
                "FE95" -> { d.maker = "샤오미"; if (d.kind == null) d.kind = "샤오미 IoT 기기" }
                "1819", "FEF5" -> if (d.kind == null) d.kind = "위치 비콘"
            }
        }
    }

    /** 클래식 블루투스 기기 분류 → 종류 */
    @SuppressLint("MissingPermission")
    private fun guessFromClass(dev: BluetoothDevice, d: Dev) {
        val major = try { dev.bluetoothClass?.majorDeviceClass } catch (_: SecurityException) { null } ?: return
        d.kind = when (major) {
            BluetoothClass.Device.Major.PHONE -> { d.phone = true; "핸드폰" }
            BluetoothClass.Device.Major.COMPUTER -> "컴퓨터/노트북"
            BluetoothClass.Device.Major.AUDIO_VIDEO -> "이어폰/스피커/TV"
            BluetoothClass.Device.Major.WEARABLE -> "워치/웨어러블"
            BluetoothClass.Device.Major.PERIPHERAL -> "키보드/마우스/컨트롤러"
            BluetoothClass.Device.Major.HEALTH -> "건강 기기"
            BluetoothClass.Device.Major.TOY -> "장난감"
            BluetoothClass.Device.Major.IMAGING -> "프린터/카메라"
            else -> d.kind
        }
    }

    private val devices = LinkedHashMap<String, Dev>()
    private var trackedAddr: String? = null
    private var scanning = false
    private var lastVibrate = 0L
    private var tickCount = 0
    private var nearOnly = false               // 가까운 기기만 보기
    private val nearLimit = 5.0                // 5m 안쪽만

    // ── 방향 찾기 ──
    private val sensorMgr by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private var heading = 0f                   // 폰이 보고 있는 방향 (0=북쪽)
    private var dirMode = false
    private var dirStart = 0L
    private val BINS = 12                                       // 30도씩 12칸
    private val dirBins = Array(12) { mutableListOf<Int>() }
    private var dirBest: Float? = null         // 기기가 있는 방향 (나침반 각도)
    private lateinit var dirBtn: Button
    private lateinit var dirText: TextView

    private val compass = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val m = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(m, e.values)
            val o = FloatArray(3)
            SensorManager.getOrientation(m, o)
            heading = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
            radar.arrowDeg = dirBest?.let { it - heading }
            radar.headingDeg = heading
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

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
        dirBtn = smallButton("🧭 방향 찾기") { startDirection() }
        targetBox.addView(dirBtn)
        dirText = text(13f, Color.rgb(255, 210, 122)).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        targetBox.addView(dirText)
        targetBox.addView(text(12f, dim).apply {
            text = "폰을 들고 천천히 걸어 보세요. 숫자가 작아지는 쪽에 기기가 있어요.\n(목록에서 다시 누르면 추적 해제)"
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
        root.addView(targetBox)

        val listPanel = panel()
        listPanel.addView(text(15f, Color.WHITE, true).apply { text = "찾은 기기 (누르면 추적)" })
        listPanel.addView(smallButton("🏠 가까운 기기만 보기: 꺼짐") { btn ->
            nearOnly = !nearOnly
            btn.text = if (nearOnly) "🏠 가까운 기기만 보기: 켜짐 (5m 안)" else "🏠 가까운 기기만 보기: 꺼짐"
            renderList()
        })
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

    private fun smallButton(label: String, onClick: (Button) -> Unit) = Button(this).apply {
        text = label; textSize = 14f; setTextColor(green); isAllCaps = false
        background = GradientDrawable().apply {
            setColor(Color.rgb(13, 43, 26)); cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(43, 212, 110))
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) }
        setOnClickListener { onClick(this) }
    }

    // ───────────────────────── 방향 찾기 ─────────────────────────
    // 폰 각도(나침반)로 "어느 쪽을 볼 때 신호가 얼마였는지"를 칸마다 기록해요.
    // 그래서 일정한 속도로 돌 필요 없이, 아무렇게나 돌려도 빈 칸만 채우면 돼요.
    private val needPerBin = 1          // 한 칸마다 신호 1번이면 "확인됨"
    private var dirSamples = 0          // 방향 찾기 중 받은 신호 수

    private fun startDirection() {
        if (dirMode) { finishDirection(); return }     // 진행 중에 다시 누르면 "여기까지로 결정"
        if (!scanning || trackedAddr == null) { dirText.text = "먼저 스캔을 켜고 기기를 골라 주세요."; return }
        if (sensorMgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null) { dirText.text = "이 폰에는 나침반 센서가 없어요."; return }
        dirBins.forEach { it.clear() }
        dirBest = null
        radar.arrowDeg = null
        dirStart = SystemClock.elapsedRealtime()
        dirMode = true
        dirSamples = 0
        // 클래식 검색은 BLE 스캔을 방해해서, 방향 찾는 동안은 잠깐 멈춤
        try { btAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
        dirBtn.text = "✅ 여기까지로 결정"
        dirText.text = "폰을 눕혀서 가슴 앞에 들고 제자리에서 돌아요.\n빠르든 느리든 상관없어요. 레이더 테두리의 빈 칸을 채우면 돼요!"
    }

    /** 0.5초마다: 방향 찾기 진행 확인 */
    private fun updateDirection() {
        if (!dirMode) { radar.coverage = null; return }
        val done = BooleanArray(BINS) { dirBins[it].size >= needPerBin }
        radar.coverage = done
        radar.headingDeg = heading
        val covered = done.count { it }
        if (covered >= BINS) { finishDirection(); return }
        // 절반 넘게 채우면 미리 보기 화살표 (끝나는 건 12칸을 다 채우거나 직접 결정할 때만)
        if (covered >= BINS / 2) {
            val best = bestDirection().first
            dirBest = best
            radar.arrowDeg = best - heading
        }
        val missing = BINS - covered
        val sec = (SystemClock.elapsedRealtime() - dirStart) / 1000
        if (sec >= 8 && dirSamples == 0) {
            dirText.text = "이 기기 신호가 안 들어와요 😢\n이어폰이면 케이스를 열고, 폰이면 블루투스 설정 화면을 켜 두세요."
            return
        }
        dirText.text = "확인 ${covered}/${BINS}칸 · 남은 칸 ${missing}개 · 받은 신호 ${dirSamples}개\n" +
            "레이더 테두리의 회색 칸 쪽으로 몸을 돌려 주세요 (서두르지 않아도 돼요)"
    }

    /** 신호가 제일 센 방향(나침반 각도)과, 제일 센 칸과 제일 약한 칸의 차이(dB) */
    private fun bestDirection(): Pair<Float, Double> {
        val avg = dirBins.map { if (it.isEmpty()) null else it.average() }
        var bestI = 0; var bestV = -999.0; var worstV = 999.0
        for (i in 0 until BINS) {
            val near = listOf(avg[(i + BINS - 1) % BINS], avg[i], avg[i], avg[(i + 1) % BINS]).filterNotNull()
            if (near.isEmpty()) continue
            val v = near.average()
            if (v > bestV) { bestV = v; bestI = i }
            if (v < worstV) worstV = v
        }
        val size = 360f / BINS
        return Pair(bestI * size + size / 2, bestV - worstV)
    }

    private fun finishDirection() {
        dirMode = false
        if (scanning) try { btAdapter?.startDiscovery() } catch (_: SecurityException) {}
        dirBtn.text = "🧭 방향 찾기"
        radar.coverage = null
        val covered = dirBins.count { it.size >= needPerBin }
        if (covered < BINS / 2) {
            dirText.text = "아직 확인한 방향이 ${covered}칸뿐이라 판단이 어려워요 😢\n다시 눌러서 더 돌아 주세요."
            return
        }
        dirBest = bestDirection().first
        dirText.text = "찾았다! 🔴 빨간 화살표 쪽으로 가 보세요.\n(폰을 돌려도 화살표가 그 방향을 계속 가리켜요)"
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
        sensorMgr.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorMgr.registerListener(compass, it, SensorManager.SENSOR_DELAY_UI)
        }
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
        sensorMgr.unregisterListener(compass)
        dirMode = false
        if (::dirBtn.isInitialized) dirBtn.text = "🧭 방향 찾기"
        radar.coverage = null
        scanBtn.text = "🌐 주변 스캔 시작"
        status.text = "멈췄어요."
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onSeen(result.device.address, result.scanRecord?.deviceName ?: safeName(result.device), result.rssi) {
                guessFromScan(result, it)
            }
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
                    onSeen(dev.address, safeName(dev), rssi) { guessFromClass(dev, it) }
                }
                // 클래식 검색은 12초쯤 하고 끝나서, 스캔 중이면 다시 시작
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (scanning && !dirMode) try { btAdapter?.startDiscovery() } catch (_: SecurityException) {}
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String? = try { d.name } catch (_: SecurityException) { null }

    private fun onSeen(addr: String, name: String?, rssi: Int, guess: (Dev) -> Unit) {
        runOnUiThread {
            val now = SystemClock.elapsedRealtime()
            // 이름이 있으면 이름으로 묶기 → 주소가 바뀌어도 같은 기기로 봄
            val key = if (!name.isNullOrBlank()) "N:$name" else addr
            if (!name.isNullOrBlank() && devices.containsKey(addr) && !devices.containsKey(key)) {
                devices.remove(addr)?.let { devices[key] = it }
                if (trackedAddr == addr) trackedAddr = key
            }
            val d = devices.getOrPut(key) { Dev(key, name ?: "", rssi.toDouble(), now) }
            if (!name.isNullOrBlank()) d.name = name
            // 흔들림 줄이기: 최근 10번 신호의 가운데 값을 쓰고, 천천히 따라가게
            d.recent.addLast(rssi)
            if (d.recent.size > 10) d.recent.removeFirst()
            val median = d.recent.sorted()[d.recent.size / 2].toDouble()
            d.rssi = if (d.recent.size == 1) median else d.rssi * 0.85 + median * 0.15
            d.lastSeen = now
            guess(d)
            if (dirMode && key == trackedAddr) { dirBins[((heading / (360f / BINS)).toInt()) % BINS].add(rssi); dirSamples++ }
        }
    }

    // ───────────────────────── 화면 갱신 (0.5초마다) ─────────────────────────
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            // 2분 동안 신호가 없으면 목록에서 빼기
            devices.values.removeAll { now - it.lastSeen > 120_000 && it.address != trackedAddr }
            updateDirection()
            radar.blips = devices.values.filter { !nearOnly || it.distance <= nearLimit || it.address == trackedAddr }.map {
                val age = (now - it.lastSeen) / 1000f
                RadarView.Blip(it.displayName, it.distance, it.angle,
                    (1f - age / 120f).coerceIn(0.25f, 1f), it.address == trackedAddr, it.phone)
            }
            // 목록은 3초마다만 다시 정렬 (순서가 덜 흔들리게)
            if (++tickCount % 6 == 0) renderList()
            renderTarget()
            if (scanning) handler.postDelayed(this, 500)
        }
    }

    private fun fmt(m: Double) = if (m < 1) "약 %.1fm".format(m) else "약 ${m.roundToInt()}m"

    private fun renderList() {
        listBox.removeAllViews()
        val sorted = devices.values.filter { !nearOnly || it.distance <= nearLimit || it.address == trackedAddr }.sortedBy { it.distance }
        if (sorted.isEmpty()) {
            listBox.addView(text(13f, dim).apply {
                text = "아직 없어요. 스캔을 시작해 보세요."; setPadding(0, dp(8), 0, 0)
            })
            return
        }
        for (d in sorted.take(40)) {
            val row = text(15f, if (d.address == trackedAddr) Color.rgb(255, 120, 120) else Color.WHITE).apply {
                val icon = if (d.phone) "📱 " else ""
                val sub = if (d.name.isNotBlank() && d.guess.isNotBlank()) "\n   ${d.guess}" else ""
                val ago = (SystemClock.elapsedRealtime() - d.lastSeen) / 1000
                val seen = if (ago > 5) " · ${ago}초 전" else ""
                text = "$icon${d.displayName}$sub\n   ${fmt(d.distance)} · ${d.rssi.roundToInt()}dBm$seen"
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener {
                    trackedAddr = if (trackedAddr == d.address) null else d.address
                    dirBest = null; dirMode = false; radar.arrowDeg = null
                    dirBtn.text = "🧭 방향 찾기"; radar.coverage = null; dirText.text = ""
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
