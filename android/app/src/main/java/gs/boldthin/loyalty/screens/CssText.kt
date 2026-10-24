package gs.boldthin.loyalty.screens

import android.content.res.Resources
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.ResourceFont
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * BasicText laid out as the WebView lays the page out. Blink shapes a whole run, so a font's kern pairs against the
 * space glyph apply ("k " in Archivo is -0.035 em); Minikin shapes word by word with the space apart and drops them,
 * so every word after one drifted right (0.49 px after "drink" at 14 px). Each pair from [SpaceKerning] (generated
 * from the site's web fonts by scripts/render-gate/space-kerning.mjs) goes back on the space as letter spacing: the
 * space is invisible, so only what follows it moves, by exactly the kern (RENDER-GATE-SPACE-KERNING-2026-10-24,
 * healer:4b3d7c53). Screens draw their text with this, never BasicText directly.
 */
@Composable
internal fun CssText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    val resources = LocalContext.current.resources
    val kerned = remember(text, style) { spaceKerned(text, style, resources) }
    BasicText(kerned, modifier, style, overflow = overflow, softWrap = softWrap, maxLines = maxLines)
}

internal fun spaceKerned(text: String, style: TextStyle, resources: Resources): AnnotatedString {
    if (' ' !in text) return AnnotatedString(text)
    val weight = style.fontWeight?.weight ?: 400
    val font = (style.fontFamily as? FontListFontFamily)?.fonts?.minByOrNull { abs(it.weight.weight - weight) } as? ResourceFont
        ?: return AnnotatedString(text)
    val weights = SpaceKerning.TABLE[resources.getResourceEntryName(font.resId)] ?: return AnnotatedString(text)
    val kern = weights.entries.minByOrNull { abs(it.key - weight) }?.value ?: return AnnotatedString(text)
    val size = style.fontSize.value
    val tracking = if (style.letterSpacing.isSpecified) style.letterSpacing.value else 0f
    val spans = text.indices.mapNotNull { i ->
        if (text[i] != ' ') return@mapNotNull null
        val em = (text.getOrNull(i - 1)?.let { kern.after[it] } ?: 0f) + (text.getOrNull(i + 1)?.let { kern.before[it] } ?: 0f)
        if (em == 0f) null else AnnotatedString.Range(SpanStyle(letterSpacing = (tracking + em * size).sp), i, i + 1)
    }
    return AnnotatedString(text, spans)
}

/**
 * Blink's line-box ascent / descent for a font at a device-px size (zoom for DSF): each rounded to a whole device px, and
 * with subpixel text a descent rounded down takes 1 px from the ascent (FontMetrics::AscentDescentWithHacks).
 */
internal fun blinkAscentDescent(ascentPx: Float, descentPx: Float): Pair<Float, Float> {
    val a = kotlin.math.round(ascentPx)
    val d = kotlin.math.round(descentPx)
    return if (d < descentPx && a >= 1f) (a - 1f) to (d + 1f) else a to d
}
