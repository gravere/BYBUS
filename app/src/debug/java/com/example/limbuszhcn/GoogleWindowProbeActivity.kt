package com.example.limbuszhcn

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.lody.virtual.helper.compat.MicrogActivityInsets

/** Window geometry fixture matching pinned Companion's transparent, non-floating bottom sheet. */
class GoogleWindowProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val dp = resources.displayMetrics.density
        val sheet = intent.getBooleanExtra("sheet", true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        }
        val title = TextView(this).apply { text = "窗口避让检查"; textSize = 20f }
        root.addView(title)
        root.addView(EditText(this).apply {
            hint = "输入测试"; isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(-1, (56 * dp).toInt())
        })
        if (!sheet) root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(Button(this).apply {
            text = "窗口操作"
            setOnClickListener { title.text = "操作已收到" }
        }, LinearLayout.LayoutParams(-1, (48 * dp).toInt()))
        setContentView(root)
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = if (sheet) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        MicrogActivityInsets.applyToLegacyRoot(findViewById(android.R.id.content), window.decorView)
    }
}
