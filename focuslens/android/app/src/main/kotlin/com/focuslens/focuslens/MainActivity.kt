package com.focuslens.focuslens

import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine

// FragmentActivity kvůli registerForActivityResult (runtime Bluetooth permission).
class MainActivity : FlutterFragmentActivity() {
    private var bridge: GlassesBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        bridge = GlassesBridge(this, flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun onDestroy() {
        bridge?.dispose()
        super.onDestroy()
    }
}
