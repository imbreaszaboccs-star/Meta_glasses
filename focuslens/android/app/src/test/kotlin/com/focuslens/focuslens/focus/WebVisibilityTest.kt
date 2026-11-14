package com.focuslens.focuslens.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVisibilityTest {
    private var t = 1_000L
    private val w = WebVisibility { t }

    @Test fun visibleForWindowThenHidden() {
        assertFalse(w.visible)
        w.seen(true)
        t += WebVisibility.WINDOW_MS - 1; assertTrue(w.visible)
        w.seen(true)                      // heartbeat extends it
        t += WebVisibility.WINDOW_MS - 1; assertTrue(w.visible)
        t += 1; assertFalse(w.visible)
    }

    @Test fun hiddenReportEndsItAtOnce() {
        w.seen(true); w.seen(false)
        assertFalse(w.visible)
    }
}
