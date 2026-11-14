package com.focuslens.focuslens

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
