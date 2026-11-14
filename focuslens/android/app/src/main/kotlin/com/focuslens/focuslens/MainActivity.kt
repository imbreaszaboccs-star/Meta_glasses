package com.focuslens.focuslens

import android.content.Intent
import android.os.Bundle
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine

// FragmentActivity for registerForActivityResult (runtime permissions, glasses camera permission).
// The focus block itself lives in FocusLensApp.engine + FocusService, not here.
class MainActivity : FlutterFragmentActivity() {
    private var bridge: FlutterBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        bridge = FlutterBridge(this, flutterEngine.dartExecutor.binaryMessenger, (application as FocusLensApp).engine)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) startIfVoice(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startIfVoice(intent)
    }

    // "Hey Meta, start FocusLens": startFocus returns early when a block already runs.
    private fun startIfVoice(intent: Intent?) {
        if (intent == null || !isVoiceInvocationsIntent(intent)) return
        val engine = (application as FocusLensApp).engine
        engine.foreground = true // the activity is being shown: the phone mic may start
        engine.initSdk()
        engine.startFocus(null)
    }

    override fun onStart() {
        super.onStart()
        (application as FocusLensApp).engine.foreground = true
    }

    override fun onStop() {
        (application as FocusLensApp).engine.foreground = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Returning from Meta AI / system settings: permissions or registration may have changed.
        val engine = (application as FocusLensApp).engine
        engine.initSdk()
        engine.emitStatus()
    }

    override fun onDestroy() {
        bridge?.dispose()
        bridge = null
        super.onDestroy()
    }
}
