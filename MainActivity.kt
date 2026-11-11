package com.gametranslator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_NOTI = 1002
    }

    private lateinit var prefs: Prefs
    private lateinit var appText: TextView
    private lateinit var startBtn: Button
    private lateinit var geminiKeyEt: EditText
    private lateinit var geminiModelEt: EditText
    private lateinit var claudeKeyEt: EditText
    private lateinit var glossaryEt: EditText
    private var askedNoti = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refreshButton()
    }

    override fun onPause() {
        saveAll()
        super.onPause()
    }

    // ---------------------------------------------------------------- UI
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun edit(hint: String, value: String, secret: Boolean = false, multi: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = when {
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multi) {
                minLines = 4
                gravity = Gravity.TOP or Gravity.START
            }
        }

    private fun spinner(items: List<String>, selected: Int, onSel: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onSel(pos)
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

    private fun addSeek(
        root: LinearLayout, title: String, min: Int, maxV: Int, value: Int, onChange: (Int) -> Unit
    ) {
        val tv = label("$title: $value")
        val sb = SeekBar(this)
        sb.max = maxV - min
        sb.progress = value - min
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = "$title: ${p + min}"
                onChange(p + min)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(tv)
        root.addView(sb)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "Game Translator"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "แปลข้อความบนหน้าจอเกมแบบเรียลไทม์ (อังกฤษ → ไทย) ชื่อตัวละครที่ใส่ไว้จะไม่ถูกแปล"
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        })

        // 1) เลือกแอป
        root.addView(label("1) เลือกแอป/เกมที่จะแปล"))
        appText = TextView(this).apply { textSize = 15f }
        updateAppText()
        root.addView(appText)
        root.addView(Button(this).apply {
            text = "เลือกแอป"
            setOnClickListener { pickApp() }
        })

        // 2) พื้นที่อ่าน
        root.addView(label("2) พื้นที่บนจอที่ให้อ่านข้อความ (% จากด้านบน) — ตั้งให้ครอบกล่องบทพูด"))
        addSeek(root, "เริ่มอ่านที่", 0, 90, prefs.readFrom) { prefs.readFrom = it }
        addSeek(root, "อ่านถึง", 10, 100, prefs.readTo) { prefs.readTo = it }

        // 3) ตำแหน่งแถบคำแปล
        root.addView(label("3) ตำแหน่งแถบคำแปล (ควรอยู่คนละฝั่งกับกล่องบทพูด)"))
        root.addView(spinner(listOf("ด้านบนของจอ", "ด้านล่างของจอ"), if (prefs.overlayTop) 0 else 1) {
            prefs.overlayTop = (it == 0)
        })
        addSeek(root, "ขนาดตัวอักษร", 12, 30, prefs.textSp) { prefs.textSp = it }

        // 4) ตัวแปล
        root.addView(label("4) ตัวแปล"))
        root.addView(
            spinner(
                listOf("ML Kit (ออฟไลน์ ฟรี)", "Gemini (ต้องมี API key)", "Claude (ต้องมี API key)"),
                prefs.engine
            ) { prefs.engine = it }
        )
        geminiKeyEt = edit("Gemini API key", prefs.geminiKey, secret = true)
        geminiModelEt = edit("ชื่อรุ่น Gemini", prefs.geminiModel)
        claudeKeyEt = edit("Anthropic (Claude) API key", prefs.claudeKey, secret = true)
        root.addView(geminiKeyEt)
        root.addView(geminiModelEt)
        root.addView(claudeKeyEt)

        // 5) glossary
        root.addView(label("5) ชื่อที่ไม่ต้องแปล (1 ชื่อต่อ 1 บรรทัด)"))
        glossaryEt = edit("เช่น\nAria\nKael", prefs.glossary, multi = true)
        root.addView(glossaryEt)

        startBtn = Button(this).apply {
            textSize = 16f
            setOnClickListener { onStartClick() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })

        root.addView(TextView(this).apply {
            text = "หมายเหตุ: ตอนระบบถามเรื่องการแชร์หน้าจอ (Android 14+) ให้เลือก \"ทั้งหน้าจอ\" " +
                "ไม่ใช่ \"แอปเดียว\" และบางเกมบล็อกการจับภาพหน้าจอ ซึ่งจะแปลไม่ได้"
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun updateAppText() {
        appText.text = if (prefs.targetPkg.isEmpty()) "ยังไม่ได้เลือก"
        else "${prefs.targetLabel}\n(${prefs.targetPkg})"
    }

    private fun refreshButton() {
        startBtn.text = if (TranslatorService.running) "หยุดแปล" else "เริ่มแปล"
    }

    private fun saveAll() {
        if (!::geminiKeyEt.isInitialized) return
        prefs.geminiKey = geminiKeyEt.text.toString().trim()
        prefs.geminiModel = geminiModelEt.text.toString().trim().ifEmpty { "gemini-2.5-flash" }
        prefs.claudeKey = claudeKeyEt.text.toString().trim()
        prefs.glossary = glossaryEt.text.toString()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- เลือกแอป
    private fun pickApp() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        AlertDialog.Builder(this)
            .setTitle("เลือกแอป/เกม")
            .setItems(apps.map { it.first }.toTypedArray()) { _, i ->
                prefs.targetLabel = apps[i].first
                prefs.targetPkg = apps[i].second
                updateAppText()
            }
            .show()
    }

    // ---------------------------------------------------------------- เริ่ม/หยุด
    private fun onStartClick() {
        if (TranslatorService.running) {
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_STOP))
            startBtn.postDelayed({ refreshButton() }, 600)
            return
        }

        saveAll()
        if (prefs.targetPkg.isEmpty()) {
            toast("เลือกแอป/เกมที่จะแปลก่อน")
            return
        }
        if (prefs.readTo - prefs.readFrom < 10) {
            toast("พื้นที่อ่านแคบเกินไป ปรับ \"เริ่มอ่านที่\" และ \"อ่านถึง\" ใหม่")
            return
        }
        if (prefs.engine == 1 && prefs.geminiKey.isEmpty()) {
            toast("ยังไม่ได้ใส่ Gemini API key")
            return
        }
        if (prefs.engine == 2 && prefs.claudeKey.isEmpty()) {
            toast("ยังไม่ได้ใส่ Anthropic API key")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("เปิดสิทธิ์ \"แสดงทับแอปอื่น\" ให้แอปนี้ แล้วกลับมากดเริ่มอีกครั้ง")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (!hasUsageAccess()) {
            toast("เปิดสิทธิ์ \"การเข้าถึงข้อมูลการใช้งาน\" ให้แอปนี้ แล้วกลับมากดเริ่มอีกครั้ง")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNoti &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNoti = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTI)
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTI) onStartClick() // ไม่ว่าอนุญาตหรือไม่ก็ไปต่อ
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, TranslatorService::class.java)
                .setAction(TranslatorService.ACTION_START)
                .putExtra(TranslatorService.EXTRA_CODE, resultCode)
                .putExtra(TranslatorService.EXTRA_DATA, data)
            startForegroundService(i)
            packageManager.getLaunchIntentForPackage(prefs.targetPkg)?.let { startActivity(it) }
            startBtn.postDelayed({ refreshButton() }, 800)
        } else {
            toast("ยกเลิกการแชร์หน้าจอ")
        }
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
