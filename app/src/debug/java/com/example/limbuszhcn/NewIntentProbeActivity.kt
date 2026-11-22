package com.example.limbuszhcn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** Test-only lifecycle probe. No credentials, network or game data. */
class NewIntentProbeActivity : Activity() {
    val events = mutableListOf<String>()
    var consumed = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Lifecycle probe" })
    }
    override fun onPause() { events += "pause"; super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        events += "intent"
        setIntent(intent)
    }
    override fun onResume() {
        super.onResume()
        events += "resume"
        // Same lifecycle contract as GenericIdpActivity: onNewIntent alone is insufficient.
        if (intent.action == CALLBACK) {
            consumed++
            intent.action = Intent.ACTION_MAIN
        }
    }
    companion object { const val CALLBACK = "bybus.test.AUTH_RETURN" }
}
