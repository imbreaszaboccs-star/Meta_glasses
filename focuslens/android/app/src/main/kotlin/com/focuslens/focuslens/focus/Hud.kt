package com.focuslens.focuslens.focus

import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle

/**
 * HUD screens (task.md §6). The panel is additive (black = transparent), 600×600, and every send
 * replaces the whole screen, so each screen is a pure function of state.
 * Rule: at most three lines of content, never a paragraph.
 */
sealed class HudScreen {
    /** Minimal idle marker. [compact] = just a dot once the user has learned the gesture. */
    data class Idle(val compact: Boolean, val paused: Boolean) : HudScreen()
    data class Thinking(val text: String) : HudScreen()
    /** [index] is 1-based position in the local history, [total] its size. */
    data class Hint(val text: String, val index: Int, val total: Int) : HudScreen()
    data class Message(val title: String, val body: String?) : HudScreen()
}

object Hud {
    fun render(screen: HudScreen): ContentScope.() -> Unit = when (screen) {
        is HudScreen.Idle -> idle(screen)
        is HudScreen.Thinking -> centered(screen.text, null)
        is HudScreen.Hint -> hint(screen)
        is HudScreen.Message -> centered(screen.title, screen.body ?: Strings.footerClose)
    }

    private fun idle(screen: HudScreen.Idle): ContentScope.() -> Unit = {
        flexBox(direction = Direction.COLUMN, gap = 8, padding = 24) {
            flexBox(direction = Direction.COLUMN, flexGrow = 1f) {}
            when {
                screen.paused -> {
                    text(Strings.paused, style = TextStyle.BODY)
                    text(Strings.pausedHint, style = TextStyle.META, color = TextColor.SECONDARY)
                }
                screen.compact -> text("●", style = TextStyle.META, color = TextColor.SECONDARY)
                else -> text("● ${Strings.appName} · ${Strings.idleHint}", style = TextStyle.META, color = TextColor.SECONDARY)
            }
        }
    }

    private fun hint(screen: HudScreen.Hint): ContentScope.() -> Unit = {
        val words = screen.text.trim().split(Regex("\\s+")).size
        flexBox(direction = Direction.COLUMN, gap = 16, padding = 28, background = FlexBoxBackground.CARD) {
            text(screen.text, style = if (words <= 12) TextStyle.HEADING else TextStyle.BODY)
            flexBox(direction = Direction.ROW, gap = 12, crossAlignment = Alignment.CENTER) {
                if (screen.total > 1) text("${screen.index}/${screen.total}", style = TextStyle.META, color = TextColor.SECONDARY)
                text(Strings.footerNext, style = TextStyle.META, color = TextColor.SECONDARY)
            }
        }
    }

    private fun centered(title: String, body: String?): ContentScope.() -> Unit = {
        flexBox(direction = Direction.COLUMN, gap = 10, padding = 28, background = FlexBoxBackground.CARD) {
            text(title, style = TextStyle.HEADING)
            if (body != null) text(body, style = TextStyle.META, color = TextColor.SECONDARY)
        }
    }
}
