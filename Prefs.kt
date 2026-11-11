package com.gametranslator

import android.content.Context

/** เก็บค่าตั้งค่าของแอป (เก็บในที่เก็บส่วนตัวของแอปเท่านั้น) */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var targetPkg: String
        get() = sp.getString("pkg", "") ?: ""
        set(v) { sp.edit().putString("pkg", v).apply() }

    var targetLabel: String
        get() = sp.getString("label", "") ?: ""
        set(v) { sp.edit().putString("label", v).apply() }

    /** 0 = ML Kit (ออฟไลน์), 1 = Gemini, 2 = Claude */
    var engine: Int
        get() = sp.getInt("engine", 0)
        set(v) { sp.edit().putInt("engine", v).apply() }

    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) { sp.edit().putString("geminiKey", v).apply() }

    var geminiModel: String
        get() = sp.getString("geminiModel", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) { sp.edit().putString("geminiModel", v).apply() }

    var claudeKey: String
        get() = sp.getString("claudeKey", "") ?: ""
        set(v) { sp.edit().putString("claudeKey", v).apply() }

    /** ชื่อที่ไม่ต้องแปล 1 ชื่อต่อ 1 บรรทัด */
    var glossary: String
        get() = sp.getString("glossary", "") ?: ""
        set(v) { sp.edit().putString("glossary", v).apply() }

    /** อ่านข้อความตั้งแต่กี่ % จากด้านบนของจอ */
    var readFrom: Int
        get() = sp.getInt("readFrom", 55)
        set(v) { sp.edit().putInt("readFrom", v).apply() }

    /** อ่านข้อความถึงกี่ % จากด้านบนของจอ */
    var readTo: Int
        get() = sp.getInt("readTo", 100)
        set(v) { sp.edit().putInt("readTo", v).apply() }

    /** true = แถบคำแปลอยู่ด้านบน, false = ด้านล่าง */
    var overlayTop: Boolean
        get() = sp.getBoolean("overlayTop", true)
        set(v) { sp.edit().putBoolean("overlayTop", v).apply() }

    var textSp: Int
        get() = sp.getInt("textSp", 16)
        set(v) { sp.edit().putInt("textSp", v).apply() }

    fun glossaryNames(): List<String> =
        glossary.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
