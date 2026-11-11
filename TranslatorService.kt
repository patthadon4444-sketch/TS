package com.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * บริการหลัก: จับภาพหน้าจอ (MediaProjection) -> OCR (ML Kit) -> แปล -> แสดง overlay
 * ทำงานเฉพาะตอนที่แอปที่ผู้ใช้เลือกอยู่หน้าจอ (ตรวจด้วย UsageStats)
 */
class TranslatorService : Service() {

    companion object {
        const val ACTION_START = "com.gametranslator.START"
        const val ACTION_STOP = "com.gametranslator.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "translator"
        private const val NOTI_ID = 1
        private const val OVERLAY_MARGIN_DP = 24

        @Volatile
        var running = false
    }

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    @Volatile private var reader: ImageReader? = null
    private var vdisplay: VirtualDisplay? = null
    private var screenW = 0
    private var screenH = 0

    private var overlay: TextView? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    @Volatile private var overlayHeightPx = 0

    private var translators: Translators? = null
    private var tracker: ForegroundTracker? = null
    private var names: List<String> = emptyList()
    private var loopJob: Job? = null
    private var released = false
    private var lastStatus = ""

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // สถานะของลูป (ใช้ใน coroutine เป็นหลัก)
    @Volatile private var lastThumb: IntArray? = null
    private var lastText = ""
    private var lastActive = false
    private val cache = HashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> {
                // ต้องเรียก startForeground ทุกครั้งที่ถูกสั่งด้วย startForegroundService
                goForeground("กำลังเริ่ม...")
                if (!running) start(intent)
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- เริ่ม/หยุด
    private fun start(intent: Intent) {
        val data = readData(intent)
        if (data == null) {
            shutdown()
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data)
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post { shutdown() }
                }
            }
            proj.registerCallback(cb, main) // ต้องลงทะเบียนก่อนสร้าง VirtualDisplay (Android 14+)
            projection = proj
            projectionCallback = cb
            running = true

            setupDisplay()
            createOverlay()

            names = prefs.glossaryNames()
            tracker = ForegroundTracker(getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
            val tr = Translators(prefs)
            translators = tr

            loopJob = scope.launch {
                try {
                    if (prefs.engine == 0) setStatus("กำลังเตรียมโมเดลแปล (ครั้งแรกต้องใช้เน็ต)...")
                    tr.prepare()
                    setStatus("พร้อมแล้ว • แปลเมื่อเปิด ${prefs.targetLabel}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showError(e)
                }
                while (isActive) {
                    try {
                        cycle(tr)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        showError(e)
                        delay(2000)
                    }
                    delay(600)
                }
            }
        } catch (e: Exception) {
            setStatus("เริ่มไม่สำเร็จ: ${e.message}")
            shutdown()
        }
    }

    @Suppress("DEPRECATION")
    private fun readData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DATA)

    private fun releaseAll() {
        if (released) return
        released = true
        running = false
        loopJob?.cancel()
        runCatching { vdisplay?.release() }
        runCatching { reader?.close() }
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        runCatching { projection?.stop() }
        overlay?.let { v -> runCatching { wm.removeView(v) } }
        overlay = null
    }

    private fun shutdown() {
        releaseAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseAll()
        translators?.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!running) return
        // หมุนจอ: ปรับขนาดการจับภาพและความกว้างของแถบคำแปล
        setupDisplay()
        val lp = overlayLp
        val v = overlay
        if (lp != null && v != null) {
            lp.width = (screenW * 0.9f).toInt()
            runCatching { wm.updateViewLayout(v, lp) }
        }
        lastThumb = null
    }

    // ---------------------------------------------------------------- วนลูปหลัก
    private suspend fun cycle(tr: Translators) {
        val fg = tracker?.current()
        if (fg != prefs.targetPkg) {
            // ไม่ได้อยู่ในแอปที่เลือก -> ซ่อนคำแปลและพัก
            if (lastActive) {
                lastActive = false
                lastText = ""
                lastThumb = null
                hideOverlay()
                setStatus("รอเปิด ${prefs.targetLabel}")
            }
            return
        }
        if (!lastActive) {
            lastActive = true
            setStatus("กำลังแปล ${prefs.targetLabel}")
        }

        val bmp = capture() ?: return // ไม่มีเฟรมใหม่ = หน้าจอไม่เปลี่ยน
        try {
            val t = thumb(bmp)
            val prev = lastThumb
            if (prev != null && diff(prev, t) < 2.0) return
            lastThumb = t

            val text = recognize(bmp)
            if (text == lastText) return
            lastText = text
            if (text.length < 3) {
                hideOverlay()
                return
            }

            var result = cache[text]
            if (result == null) {
                result = tr.translate(text, names)
                if (cache.size > 300) cache.clear()
                cache[text] = result
            }
            if (result.isNotBlank()) showOverlay(result) else hideOverlay()
        } finally {
            bmp.recycle()
        }
    }

    private suspend fun recognize(bmp: Bitmap): String {
        val r = recognizer.process(InputImage.fromBitmap(bmp, 0)).awaitResult()
        return r.text.replace(Regex("\\s+"), " ").trim()
    }

    // ---------------------------------------------------------------- จับภาพ
    @Suppress("DEPRECATION")
    private fun updateScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
    }

    private fun setupDisplay() {
        updateScreenSize()
        val dpi = resources.displayMetrics.densityDpi
        reader?.close()
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        val vd = vdisplay
        if (vd == null) {
            vdisplay = projection?.createVirtualDisplay(
                "game-translator", screenW, screenH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
        } else {
            vd.resize(screenW, screenH, dpi)
            vd.surface = r.surface
        }
    }

    /** คืนภาพเฉพาะส่วนที่ตั้งให้อ่าน (หรือ null ถ้าไม่มีเฟรมใหม่) */
    private fun capture(): Bitmap? {
        val r = reader ?: return null
        val img = r.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val w = img.width
            val h = img.height
            val rowPixels = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)

            // ถมสีดำทับตำแหน่งแถบคำแปลของเราเอง กันไม่ให้ OCR อ่านคำแปลกลับเข้าไป
            val oh = overlayHeightPx
            if (oh > 0) {
                val margin = dp(OVERLAY_MARGIN_DP + 4)
                val rect = if (prefs.overlayTop) Rect(0, 0, w, margin + oh) else Rect(0, h - margin - oh, w, h)
                Canvas(full).drawRect(rect, Paint().apply { color = Color.BLACK })
            }

            val y0 = (h * prefs.readFrom / 100f).toInt().coerceIn(0, h - 1)
            val y1 = (h * prefs.readTo / 100f).toInt().coerceIn(y0 + 1, h)
            if (y1 - y0 < 20) {
                full.recycle()
                return null
            }
            val crop = Bitmap.createBitmap(full, 0, y0, w, y1 - y0)
            if (crop !== full) full.recycle()
            return crop
        } finally {
            img.close()
        }
    }

    private fun thumb(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 64, 36, true)
        val px = IntArray(64 * 36)
        s.getPixels(px, 0, 64, 0, 0, 64, 36)
        if (s !== b) s.recycle()
        return IntArray(px.size) {
            val c = px[it]
            (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
        }
    }

    private fun diff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toDouble() / a.size
    }

    // ---------------------------------------------------------------- overlay
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun createOverlay() {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(235, 0, 0, 0))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            textSize = prefs.textSp.toFloat()
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val lp = WindowManager.LayoutParams(
            (screenW * 0.9f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = (if (prefs.overlayTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        lp.y = dp(OVERLAY_MARGIN_DP)
        lp.alpha = 0.8f // Android 12+ ยอมให้แตะทะลุได้ถ้าความทึบไม่เกิน 0.8
        wm.addView(tv, lp)
        overlay = tv
        overlayLp = lp
    }

    private fun showOverlay(text: String) {
        main.post {
            val v = overlay ?: return@post
            v.text = text
            v.visibility = View.VISIBLE
            v.post { overlayHeightPx = v.height }
        }
    }

    private fun hideOverlay() {
        main.post {
            overlay?.visibility = View.GONE
            overlayHeightPx = 0
        }
    }

    private fun showError(e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        setStatus("ผิดพลาด: $msg")
        showOverlay("⚠ $msg")
    }

    // ---------------------------------------------------------------- notification
    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Game Translator", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Game Translator")
            .setContentText(text)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete), "หยุด", stopPi
                ).build()
            )
            .build()
    }

    private fun goForeground(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    private fun setStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTI_ID, notification(text))
        }
    }
}

/** ตรวจว่าตอนนี้แอปไหนอยู่หน้าจอ (ต้องเปิดสิทธิ์ Usage Access) */
private class ForegroundTracker(private val usm: UsageStatsManager) {
    private var since = System.currentTimeMillis() - 3_600_000L
    private var current: String? = null

    @Suppress("DEPRECATION")
    fun current(): String? {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(since, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) current = e.packageName
        }
        since = now - 1000
        return current
    }
}
