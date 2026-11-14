package com.focuslens.focuslens.focus

import android.graphics.Bitmap
import com.meta.wearable.dat.display.views.Alignment
import com.meta.wearable.dat.display.views.ContentScope
import com.meta.wearable.dat.display.views.CornerRadius
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.FlexBoxScope
import com.meta.wearable.dat.display.views.ImageSize
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle

/**
 * HUD screens (task.md §6). The panel is additive (black = transparent), 600×600, and every send
 * replaces the whole screen, so each screen is a pure function of state.
 * Rule: at most three lines of content, never a paragraph.
 */
sealed class HudScreen {
    /** Fully transparent; [dot] = the quiet L1 attention dot (top right). */
    data class Idle(val dot: Boolean = false) : HudScreen()
    data class Thinking(val text: String) : HudScreen()
    /** [index] is 1-based position in the local history, [total] its size. */
    data class Hint(val text: String, val index: Int, val total: Int, val formula: Bitmap? = null, val crop: Bitmap? = null, val goal: String = "", val tex: String = "", val recap: String = "") : HudScreen()
    /** A micro-step quest: [index] is 0-based; [feedback] = what the last check said (not done yet). */
    data class Quest(val step: String, val index: Int, val total: Int, val goal: String, val feedback: String?, val crop: Bitmap?, val formula: Bitmap?, val tex: String = "") : HudScreen()
    /** Cards panel (swipe up): [selected] indexes [rows]; at most 4 rows are drawn around it. */
    data class Blocks(val rows: List<BlockRow>, val selected: Int) : HudScreen()
    data class BlockDetail(val title: String, val step: String, val correction: String, val formula: Bitmap?, val tex: String = "") : HudScreen()
    /** [passive]: a quiet confirmation (session start, mic on/off). Shown where a HUD already is, never worth attaching the DAT display for. */
    data class Message(val title: String, val body: String?, val passive: Boolean = false) : HudScreen()
    /** Proactive offer; pinch = yes, LEFT/BACK = no, hides itself after a few seconds. */
    data class Nudge(val title: String, val kind: NudgeKind) : HudScreen()
}

data class BlockRow(val title: String, val time: String)

object Hud {
    fun render(screen: HudScreen): ContentScope.() -> Unit = when (screen) {
        is HudScreen.Idle -> idle(screen)
        is HudScreen.Thinking -> centered(screen.text, null)
        is HudScreen.Hint -> hint(screen)
        is HudScreen.Quest -> quest(screen)
        is HudScreen.Blocks -> blocks(screen)
        is HudScreen.BlockDetail -> detail(screen)
        is HudScreen.Message -> centered(screen.title, screen.body ?: Strings.footerClose)
        is HudScreen.Nudge -> centered(screen.title, Strings.footerNudge)
    }

    private fun idle(screen: HudScreen.Idle): ContentScope.() -> Unit = {
        if (screen.dot) flexBox(direction = Direction.ROW, padding = 24) {
            flexBox(direction = Direction.COLUMN, flexGrow = 1f) {}
            text("●", style = TextStyle.META, color = TextColor.SECONDARY)
        }
    }

    private fun hint(screen: HudScreen.Hint): ContentScope.() -> Unit = {
        val words = screen.text.trim().split(Regex("\\s+")).size
        flexBox(direction = Direction.COLUMN, gap = 16, padding = 28, background = FlexBoxBackground.CARD) {
            if (screen.goal.isNotBlank()) text(Strings.anchor(screen.goal), style = TextStyle.META, color = TextColor.SECONDARY)
            if (screen.recap.isNotBlank()) text(screen.recap, style = TextStyle.BODY, color = TextColor.SECONDARY)
            text(screen.text, style = if (words <= 12 && screen.formula == null && screen.crop == null) TextStyle.HEADING else TextStyle.BODY)
            pictures(screen.crop, screen.formula)
            flexBox(direction = Direction.ROW, gap = 12, crossAlignment = Alignment.CENTER) {
                if (screen.total > 1) text("${screen.index}/${screen.total}", style = TextStyle.META, color = TextColor.SECONDARY)
                text(Strings.footerNext, style = TextStyle.META, color = TextColor.SECONDARY)
            }
        }
    }

    private fun quest(screen: HudScreen.Quest): ContentScope.() -> Unit = {
        val words = screen.step.trim().split(Regex("\\s+")).size
        val shown = screen.index + 1
        flexBox(direction = Direction.COLUMN, gap = 12, padding = 28, background = FlexBoxBackground.CARD) {
            if (screen.goal.isNotBlank()) text(Strings.anchor(screen.goal), style = TextStyle.META, color = TextColor.SECONDARY)
            text(screen.step, style = if (words <= 10 && screen.crop == null && screen.formula == null) TextStyle.HEADING else TextStyle.BODY)
            if (screen.feedback != null) text(screen.feedback, style = TextStyle.BODY)
            pictures(screen.crop, screen.formula)
            text("▰".repeat(shown) + "▱".repeat(screen.total - shown) + " $shown/${screen.total}", style = TextStyle.META, color = TextColor.SECONDARY)
            text(Strings.footerQuest, style = TextStyle.META, color = TextColor.SECONDARY)
        }
    }

    /** Crop and/or formula under the text. Both only when they fit the 600 px panel (≈300 px of pictures), else the crop alone. */
    private fun FlexBoxScope.pictures(crop: Bitmap?, formula: Bitmap?) {
        val fh = formula?.let { it.height * 544f / it.width } ?: 0f
        val both = crop != null && formula != null && crop.height + fh <= 300f
        if (crop != null) image(bitmap = crop, sizePreset = ImageSize.FILL, cornerRadius = CornerRadius.MEDIUM)
        if (formula != null && (crop == null || both)) image(bitmap = formula, sizePreset = ImageSize.FILL, cornerRadius = CornerRadius.MEDIUM)
    }

    private fun blocks(screen: HudScreen.Blocks): ContentScope.() -> Unit = {
        flexBox(direction = Direction.COLUMN, gap = 10, padding = 28, background = FlexBoxBackground.CARD) {
            if (screen.rows.isEmpty()) text(Strings.noBlocks, style = TextStyle.BODY)
            val start = (screen.selected - 1).coerceIn(0, maxOf(0, screen.rows.size - 4))
            for (i in start until minOf(screen.rows.size, start + 4)) {
                val line = "${screen.rows[i].title} · ${screen.rows[i].time}"
                if (i == screen.selected) text("▶ $line", style = TextStyle.HEADING)
                else text("    $line", style = TextStyle.META, color = TextColor.SECONDARY)
            }
            text(Strings.footerBlocks, style = TextStyle.META, color = TextColor.SECONDARY)
        }
    }

    private fun detail(screen: HudScreen.BlockDetail): ContentScope.() -> Unit = {
        flexBox(direction = Direction.COLUMN, gap = 12, padding = 28, background = FlexBoxBackground.CARD) {
            text(screen.title, style = TextStyle.HEADING)
            text(screen.step, style = TextStyle.BODY)
            screen.formula?.let { image(bitmap = it, sizePreset = ImageSize.FILL, cornerRadius = CornerRadius.MEDIUM) }
            if (screen.correction.isNotEmpty()) text(screen.correction, style = TextStyle.META, color = TextColor.SECONDARY)
            text(Strings.footerBack, style = TextStyle.META, color = TextColor.SECONDARY)
        }
    }

    private fun centered(title: String, body: String?): ContentScope.() -> Unit = {
        flexBox(direction = Direction.COLUMN, gap = 10, padding = 28, background = FlexBoxBackground.CARD) {
            text(title, style = TextStyle.HEADING)
            if (!body.isNullOrEmpty()) text(body, style = TextStyle.META, color = TextColor.SECONDARY)
        }
    }
}
