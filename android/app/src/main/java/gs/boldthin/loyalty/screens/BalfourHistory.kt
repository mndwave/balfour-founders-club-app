package gs.boldthin.loyalty.screens

import androidx.annotation.DrawableRes
import androidx.annotation.FontRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// LOYALTY-NATIVE-TIERS-HISTORY-2026-10-20 (healer:4d9ec5cb, parent healer:6c9a3aed): native copy of
// balfour-founders-club src/app/(app)/history/page.tsx, measure for measure (CSS px == dp), shown settled (no
// entrance animation). Same rules as the Randalls screens: a faithful copy of the web page, no new visible element,
// data only from boldthings-services, no Capacitor dependency (moves unchanged into the native shell).
// loyalty-screen: history@2 (v2 = the Randalls pill ScrollFade; Balfour wraps its pills, so this layout is unchanged)

/** The brand's artwork and fonts (copies of the files the web page loads). */
data class BalfourArt(
    @DrawableRes val star: Int,
    @DrawableRes val logo: Int,
    @FontRes val sansBook: Int,
    @FontRes val sansBold: Int,
    @FontRes val displayBold: Int,
    @FontRes val archivo: Int,
    @FontRes val poppinsItalic: Int,
)

/** Colours from balfour-founders-club src/app/globals.css (@theme). */
internal object BfColour {
    val Cream = Color(0xFFFBF6ED)
    val Forest = Color(0xFF13342B)
    val Rose = Color(0xFFAC777F)
    val RoseDeep = Color(0xFF96636B)
    val Copper = Color(0xFFBB8459)
    val Ink = Color(0xFF212322)
    val Muted = Color(0xFF7E7E7E)
    val Stroke = Color(0xFFDBDBDB)
    val Sand = Color(0xFFEEDFD0)
    val MutedInk = Color(0xFF636363)
    val CopperInk = Color(0xFF865A37)
    val RoseInk = Color(0xFF87545B)
    val White = Color(0xFFFFFFFF)
    val Credit = Color(0xFF9A9A96)
}

/** Text styles in CSS px: the WebView does not scale with the system font size, so neither do these. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal class BfType(art: BalfourArt, private val density: Density) {
    // No Gotham Medium file: Bold doubles as weight 500 (as the web page's font declaration does).
    private val sansFamily = FontFamily(
        Font(art.sansBook, FontWeight.Normal),
        Font(art.sansBold, FontWeight(500)),
        Font(art.sansBold, FontWeight.Bold),
    )
    private val displayFamily = FontFamily(Font(art.displayBold, FontWeight.Bold))
    private val archivoFamily = FontFamily(
        listOf(300, 400).map { w ->
            Font(art.archivo, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )
    private val poppinsFamily = FontFamily(Font(art.poppinsItalic, FontWeight(600), FontStyle.Italic))

    private fun px(v: Float) = (v / density.fontScale).sp

    /**
     * Where the WebView (Blink) puts the baseline in a line box, matched in Compose. The phone's Blink rounds the face's
     * ascent and descent (hhea, per 1000) to whole device px (blinkAscentDescent) and floors the half-leading; Compose
     * centres its integer font metrics.
     * Compose grows the descent by ceil(extra x (1 - topRatio)), so topRatio lands on Blink's baseline, to a device px.
     * Same rule as randalls-rewards-app ScreenKit.blinkLine (render gate, healer:4b3d7c53).
     */
    private fun blinkLine(size: Float, line: Float?, m: Pair<Float, Float>): LineHeightStyle {
        if (line == null) return LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        val (ascent, descent) = m
        val d = density.density
        val (ra, rd) = blinkAscentDescent(ascent * size * d, descent * size * d)
        val baselineDev = ra + kotlin.math.floor((line * d - ra - rd) / 2f)
        val ai = kotlin.math.ceil(ascent * size * d)
        val di = kotlin.math.ceil(descent * size * d)
        val lineDp = kotlin.math.ceil(line * d)
        val extra = lineDp - ai - di
        if (extra <= 0f) return LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        val growDescent = lineDp - di - baselineDev
        return LineHeightStyle(LineHeightStyle.Alignment((1f - (growDescent - 0.5f) / extra).coerceIn(0f, 1f)), LineHeightStyle.Trim.None)
    }

    /** hhea ascent / descent per 1000 of the face a family draws with at a weight (gotham_book / gotham_bold differ). */
    private fun metrics(family: FontFamily, weight: Int): Pair<Float, Float> = when (family) {
        sansFamily -> if (weight >= 500) 0.960f to 0.240f else 0.800f to 0.200f
        displayFamily -> 0.885f to 0.264f
        archivoFamily -> 0.878f to 0.210f
        else -> 1.050f to 0.350f
    }

    private fun base(family: FontFamily, size: Float, line: Float?, color: Color, weight: Int, tracking: Float, align: TextAlign, italic: Boolean = false) = TextStyle(
        color = color,
        fontFamily = family,
        fontWeight = FontWeight(weight),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontSize = px(size),
        lineHeight = if (line != null) px(line) else androidx.compose.ui.unit.TextUnit.Unspecified,
        letterSpacing = px(tracking),
        textAlign = align,
        lineHeightStyle = blinkLine(size, line, metrics(family, weight)),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        // Fractional glyph advances, as the WebView draws them (TextMotion.Static rounds each advance: a device line
        // came out ~0.6 px per character narrower than the page, randalls-rewards-app 619a030).
        textMotion = TextMotion.Animated,
        // CSS wraps greedily (first fit); Compose defaults to an optimal-fit paragraph and moved "(Mon-Thu)" a line down.
        lineBreak = LineBreak.Simple,
    )

    /** Tailwind text-xs = 12/16, text-sm = 14/20, text-base = 16/24: pass the CSS size and line height. */
    fun sans(size: Float, line: Float, color: Color, weight: Int = 400, tracking: Float = 0f, align: TextAlign = TextAlign.Start) =
        base(sansFamily, size, line, color, weight, tracking, align)

    fun display(size: Float, line: Float, color: Color, tracking: Float = 0f) =
        base(displayFamily, size, line, color, 700, tracking, TextAlign.Start)

    fun archivo(size: Float, line: Float?, color: Color, weight: Int = 400, tracking: Float = 0f) =
        base(archivoFamily, size, line, color, weight, tracking, TextAlign.Start)

    fun poppins(size: Float, color: Color, tracking: Float = 0f) =
        base(poppinsFamily, size, null, color, 600, tracking, TextAlign.Start, italic = true)
}

sealed class BfReceiptState {
    object Loading : BfReceiptState()
    data class Loaded(val receipt: Receipt) : BfReceiptState()
    /** The fetch failed: the web page then shows the "No receipt details available." block. */
    object Failed : BfReceiptState()
}

enum class BfReceiptPrompt { HIDDEN, PROMPT, SAVED }

data class BalfourHistoryState(
    /** null while the first fetch is out: the page then shows "Loading your history…". */
    val cash: List<CashTx>?,
    val filter: HistoryFilter = HistoryFilter.ALL,
    val receiptPrompt: BfReceiptPrompt = BfReceiptPrompt.HIDDEN,
    val expanded: Set<String> = emptySet(),
    val receipts: Map<String, BfReceiptState> = emptyMap(),
    /** The member whose avatar the bar shows (initials; the uploaded photo is the shell's to load). */
    val avatarInitials: String = "BF",
    val avatarPair: Int = 0,
    /** The member's uploaded photo (profile.avatar_url), when they have one: the web shows it instead of the initials. */
    val avatarPhoto: androidx.compose.ui.graphics.ImageBitmap? = null,
)

private val InkSoft = BfColour.Ink.copy(alpha = 0.12f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BalfourHistory(
    state: BalfourHistoryState,
    copy: SiteCopy,
    art: BalfourArt,
    onFilter: (HistoryFilter) -> Unit,
    onToggleReceipt: (orderId: String) -> Unit,
    onRate: (orderId: String, stars: Int) -> Unit,
    onTurnOnReceipts: () -> Unit,
    onQuery: (mailto: String) -> Unit,
    /** The web header's hamburger and avatar menu: the shell hands them to the hosted page underneath (no tab bar on this page). */
    onMenu: () -> Unit = {},
    onAvatar: () -> Unit = {},
) {
    val density = LocalDensity.current
    val type = remember(art, density) { BfType(art, density) }
    val groups = remember(state.cash, state.filter) { state.cash?.let { BalfourHistoryModel.groups(it, state.filter) } }

    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val scroll = rememberScrollState()
    // AppTopBar slides away while scrolling down and returns on any scroll up (lib/useHideOnScroll.ts): hide after 80 px, 6 px dead zone.
    var hidden by remember { mutableStateOf(false) }
    LaunchedEffect(scroll, density) {
        var lastY = 0f
        snapshotFlow { scroll.value }.collect { px ->
            val y = px / density.density
            if (y <= 80f) {
                hidden = false
                lastY = y
            } else if (abs(y - lastY) >= 6f) {
                hidden = y > lastY
                lastY = y
            }
        }
    }
    val slide by animateFloatAsState(if (hidden) 1f else 0f, tween(250, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)), label = "app-bar")

    // The bar floats over the page as on the web (fixed top-0); the content scrolls under it and keeps the web's 5rem gap.
    Box(Modifier.fillMaxSize().background(BfColour.Cream)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(start = 24.dp, end = 24.dp, top = statusTop + 80.dp, bottom = 24.dp)
                    .heightIn(min = viewport - statusTop - 104.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CssText(
                        copy.get("history.title", "Your History"),
                        style = type.display(32f, 38.4f, BfColour.Forest, tracking = 2.72f),
                    )
                    CssText(
                        copy.get("history.subtitle", "Everything you've used and every receipt, in one place."),
                        style = type.sans(16f, 26f, BfColour.Ink),
                    )
                }
                if (state.receiptPrompt != BfReceiptPrompt.HIDDEN) ReceiptPromptCard(state.receiptPrompt, type, onTurnOnReceipts)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryFilter.values().forEach { FilterPill(it, it == state.filter, type) { onFilter(it) } }
                }
                when {
                    groups == null -> CssText(
                        copy.get("history.loading", "Loading your history…"),
                        style = type.sans(14f, 20f, BfColour.MutedInk),
                    )
                    groups.isEmpty() -> CssText(
                        copy.get("history.empty", "Nothing here yet — your visits and receipts will appear as soon as you use a benefit."),
                        style = type.sans(14f, 20f, BfColour.MutedInk),
                    )
                    else -> Column {
                        groups.forEachIndexed { gi, g -> MonthSection(g, gi, state, art, type, onToggleReceipt, onRate, onQuery) }
                    }
                }
                Spacer(Modifier.weight(1f))
                PoweredBy(type)
            }
        }
        Box(Modifier.graphicsLayer { translationY = -slide * (statusTop + 65.dp).toPx() }) {
            AppBar(state, art, type, onMenu, onAvatar)
        }
    }
}

/** AppTopBar on a phone: cream block under the status bar, a 64 px row with the wordmark centred and the avatar menu right, a stroke hairline. */
@Composable
private fun AppBar(state: BalfourHistoryState, art: BalfourArt, type: BfType, onMenu: () -> Unit, onAvatar: () -> Unit) {
    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(statusTop + 65.dp)
            .drawBehind {
                drawRect(BfColour.Cream)
                drawRect(BfColour.Stroke, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            },
    ) {
        Box(Modifier.padding(top = statusTop).fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
            val logo = painterResource(art.logo)
            val ratio = logo.intrinsicSize.let { if (it.height > 0f) it.width / it.height else 1f }
            Image(
                logo,
                null,
                Modifier.height(32.dp).aspectRatio(ratio),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcIn), // brightness-0: a black silhouette
            )
            // DashboardSidebar's mobile trigger: 44 px square, 16 px from the left, 24 px icon in forest (lg:hidden).
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .size(44.dp)
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(24.dp)) {
                    val s = size.width / 24f
                    drawPath(
                        Path().apply { moveTo(4f * s, 6f * s); lineTo(20f * s, 6f * s); moveTo(4f * s, 12f * s); lineTo(20f * s, 12f * s); moveTo(4f * s, 18f * s); lineTo(20f * s, 18f * s) },
                        BfColour.Forest, style = Stroke(2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(end = 28.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onAvatar), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(state.avatarInitials, state.avatarPair, state.avatarPhoto, type)
                    Spacer(Modifier.size(8.dp))
                    Canvas(Modifier.size(20.dp)) {
                        val s = size.width / 24f
                        drawPath(
                            Path().apply { moveTo(6f * s, 9f * s); lineTo(12f * s, 15f * s); lineTo(18f * s, 9f * s) },
                            BfColour.MutedInk, style = Stroke(2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Avatar(initials: String, pair: Int, photo: androidx.compose.ui.graphics.ImageBitmap?, type: BfType) {
    val (bg, fg) = when (pair) {
        0 -> BfColour.Forest to BfColour.Cream
        1 -> BfColour.Rose to BfColour.Ink
        2 -> BfColour.RoseDeep to BfColour.Cream
        3 -> BfColour.Copper to BfColour.Ink
        else -> BfColour.Sand to BfColour.Ink
    }
    Box(
        Modifier
            .size(40.dp)
            .drawBehind {
                // 0 1px 4px rgba(0,0,0,.25) lift, then the 1px cream ring
                drawIntoCanvas40Shadow()
                drawCircle(BfColour.Cream.copy(alpha = 0.85f), radius = size.minDimension / 2f + 1.dp.toPx())
            }
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            androidx.compose.foundation.Image(photo, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            CssText(
                initials,
                style = type.display(21f, 21f, fg).copy(textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 0.84.dp),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoCanvas40Shadow() {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        // CSS blur 4px is a Gaussian with sigma 2; Skia's shadow-layer radius r has sigma 0.57735 r + 0.5.
        setShadowLayer(((4f / 2f) - 0.5f) / 0.57735f * density, 0f, 1.dp.toPx(), android.graphics.Color.argb(64, 0, 0, 0))
    }
    drawContext.canvas.nativeCanvas.drawCircle(size.width / 2f, size.height / 2f, size.minDimension / 2f, paint)
}

@Composable
private fun ReceiptPromptCard(stage: BfReceiptPrompt, type: BfType, onTurnOn: () -> Unit) {
    // rounded-lg bg-forest p-4, inner relative min-h-11 with absolute inset-0 content: the row is exactly 44 high and a wrapped
    // subtitle overflows it centred (never grows the banner); the outer flex gap-4 puts 16 before the button, inner gap-3 12 after the icon
    Box(Modifier.fillMaxWidth().background(BfColour.Forest, RoundedCornerShape(8.dp)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(BfColour.Cream.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(20.dp)) {
                    val s = size.width / 24f
                    val st = Stroke(if (stage == BfReceiptPrompt.SAVED) 1.8f * s else 1.6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    if (stage == BfReceiptPrompt.SAVED) {
                        drawPath(Path().apply { moveTo(20f * s, 6f * s); lineTo(9f * s, 17f * s); lineTo(4f * s, 12f * s) }, BfColour.Cream, style = st)
                    } else {
                        drawPath(Path().apply {
                            moveTo(14f * s, 2f * s); lineTo(6f * s, 2f * s)
                            cubicTo(4.9f * s, 2f * s, 4f * s, 2.9f * s, 4f * s, 4f * s); lineTo(4f * s, 20f * s)
                            cubicTo(4f * s, 21.1f * s, 4.9f * s, 22f * s, 6f * s, 22f * s); lineTo(18f * s, 22f * s)
                            cubicTo(19.1f * s, 22f * s, 20f * s, 21.1f * s, 20f * s, 20f * s); lineTo(20f * s, 8f * s); close()
                            moveTo(14f * s, 2f * s); lineTo(14f * s, 8f * s); lineTo(20f * s, 8f * s)
                            moveTo(16f * s, 13f * s); lineTo(8f * s, 13f * s)
                            moveTo(16f * s, 17f * s); lineTo(8f * s, 17f * s)
                        }, BfColour.Cream, style = st)
                    }
                }
            }
            if (stage == BfReceiptPrompt.SAVED) {
                CssText("Receipts turned on — manage anytime in Account.", style = type.sans(14f, 20f, BfColour.Cream), modifier = Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f).wrapContentHeight(Alignment.CenterVertically, unbounded = true)) {
                    CssText("Get email receipts", style = type.sans(14f, 20f, BfColour.Cream, weight = 500))
                    CssText("Turn on to get a receipt after every visit.", style = type.sans(12f, 16f, BfColour.Cream.copy(alpha = 0.60f)), modifier = Modifier.padding(top = 2.dp))
                }
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BfColour.RoseDeep)
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTurnOn)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CssText("Turn on", style = type.sans(14f, 20f, BfColour.Cream, weight = 500, align = TextAlign.Center))
                }
            }
        }
    }
}

@Composable
private fun FilterPill(filter: HistoryFilter, active: Boolean, type: BfType, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .height(44.dp)
            .clip(shape)
            .background(if (active) BfColour.RoseDeep else BfColour.White, shape)
            .border(1.dp, if (active) Color.Transparent else BfColour.Stroke, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CssText(
            filter.label,
            // CSS letter-spacing trails the last glyph, so the page pill is text + 32 + 1.75 wide; Compose only counts part of it:
            // device frame diff showed each pill 1.2 css px narrower without this, 0.7 wider with the full 1.75 (healer:fae57e3f).
            modifier = Modifier.padding(end = 1.2.dp),
            style = type.sans(14f, 20f, if (active) BfColour.Cream else BfColour.MutedInk, weight = 500, tracking = 1.75f),
            maxLines = 1,
        )
    }
}

@Composable
private fun MonthSection(
    group: MonthGroup,
    index: Int,
    state: BalfourHistoryState,
    art: BalfourArt,
    type: BfType,
    onToggleReceipt: (String) -> Unit,
    onRate: (String, Int) -> Unit,
    onQuery: (String) -> Unit,
) {
    // border-t pt-4 (not on the first month), month header row, then divide-y rows
    Column(
        Modifier.fillMaxWidth().let {
            if (index > 0) it.drawBehind { drawRect(BfColour.Stroke, size = Size(size.width, 1.dp.toPx())) }.padding(top = 17.dp) else it
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CssText(group.label, style = type.sans(16f, 24f, BfColour.Forest, weight = 500))
            CssText(group.netLabel, style = type.sans(16f, 24f, if (group.net >= 0) BfColour.CopperInk else BfColour.RoseInk, weight = 500))
        }
        group.rows.forEachIndexed { i, row ->
            Column(
                Modifier.fillMaxWidth().let {
                    if (i > 0) it.drawBehind { drawRect(BfColour.Stroke, size = Size(size.width, 1.dp.toPx())) }.padding(top = 1.dp) else it
                },
            ) {
                TransactionRow(row, state, art, type, onToggleReceipt, onRate, onQuery)
            }
        }
    }
}

@Composable
private fun TransactionRow(
    row: LedgerRow,
    state: BalfourHistoryState,
    art: BalfourArt,
    type: BfType,
    onToggleReceipt: (String) -> Unit,
    onRate: (String, Int) -> Unit,
    onQuery: (String) -> Unit,
) {
    val orderId = row.orderId
    val expanded = orderId != null && orderId in state.expanded
    val dot = when (row.kind) {
        RowKind.CORRECTION -> BfColour.Muted
        RowKind.EARNED -> BfColour.Copper
        RowKind.SPENT -> BfColour.Rose
    }
    val amount = when (row.kind) {
        RowKind.CORRECTION -> BfColour.MutedInk
        RowKind.EARNED -> BfColour.CopperInk
        RowKind.SPENT -> BfColour.RoseInk
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .let { if (orderId != null) it.clickable(remember { MutableInteractionSource() }, indication = null) { onToggleReceipt(orderId) } else it }
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Column(Modifier.weight(1f)) {
                CssText(row.venue, style = type.sans(16f, 24f, BfColour.Forest, weight = 500))
                CssText(row.description, style = type.sans(14f, 20f, BfColour.MutedInk))
                CssText(row.dateLabel, style = type.sans(14f, 20f, BfColour.MutedInk))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CssText(row.amount, style = type.sans(16f, 24f, amount, weight = 500))
                if (orderId != null) {
                    Canvas(Modifier.size(16.dp)) {
                        val s = size.width / 24f
                        rotate(if (expanded) 180f else 0f) {
                            drawPath(
                                Path().apply { moveTo(6f * s, 9f * s); lineTo(12f * s, 15f * s); lineTo(18f * s, 9f * s) },
                                BfColour.MutedInk, style = Stroke(2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
            }
        }
        if (expanded && orderId != null) {
            // mb-3 ml-6 overflow-hidden rounded-lg border border-stroke
            Box(Modifier.padding(start = 24.dp, bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, BfColour.Stroke, RoundedCornerShape(8.dp))) {
                ReceiptBody(state.receipts[orderId] ?: BfReceiptState.Loading, orderId, art, type, onRate, onQuery)
            }
        }
    }
}

private fun Modifier.bottomRule(): Modifier = drawBehind {
    drawRect(BfColour.Stroke, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
}

private fun Modifier.topRule(): Modifier = drawBehind { drawRect(BfColour.Stroke, size = Size(size.width, 1.dp.toPx())) }

private fun qty(item: ReceiptItem) = if (item.quantity > 1) "${item.quantity}× " else ""

@Composable
private fun ReceiptBody(
    rs: BfReceiptState,
    orderId: String,
    art: BalfourArt,
    type: BfType,
    onRate: (String, Int) -> Unit,
    onQuery: (String) -> Unit,
) {
    val small = type.sans(12f, 16f, BfColour.MutedInk)
    when (rs) {
        BfReceiptState.Loading -> CssText("Loading…", style = small, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
        is BfReceiptState.Loaded -> {
            val r = rs.receipt
            if (!r.active) {
                CssText("Receipts are coming soon.", style = small, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
            } else if (r.items.isNotEmpty()) {
                Column {
                    r.items.forEach { item ->
                        when (item.itemType) {
                            "modifier" -> Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                                CssText("↳", style = small, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                                CssText(item.name, style = small, modifier = Modifier.weight(1f))
                            }
                            "service_charge" -> Row(Modifier.fillMaxWidth().bottomRule().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CssText("Service charge", style = type.sans(12f, 16f, BfColour.MutedInk, weight = 500), modifier = Modifier.weight(1f))
                                CssText("£${item.priceGbp}", style = type.sans(12f, 16f, BfColour.MutedInk, weight = 500))
                            }
                            "excluded" -> Row(Modifier.fillMaxWidth().bottomRule().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                    CssText(qty(item) + item.name, style = type.sans(12f, 16f, BfColour.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    CssText("Not eligible for rewards", style = small, modifier = Modifier.padding(top = 2.dp))
                                }
                                CssText("£${item.priceGbp}", style = type.sans(12f, 16f, BfColour.Ink, weight = 500))
                            }
                            else -> Row(Modifier.fillMaxWidth().bottomRule().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                CssText(qty(item) + item.name, style = type.sans(12f, 16f, BfColour.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
                                CssText("£${item.priceGbp}", style = type.sans(12f, 16f, BfColour.Ink, weight = 500))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().topRule().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (r.source == "pos") StarRating(orderId, r.memberRating, art, type, onRate) else Spacer(Modifier.size(0.dp))
                        CssText(
                            "Query this transaction",
                            style = type.sans(12f, 16f, BfColour.RoseInk),
                            softWrap = false,
                            modifier = Modifier.padding(start = 12.dp).clickable(remember { MutableInteractionSource() }, indication = null) { onQuery(BalfourHistoryModel.queryMailto(r, orderId)) },
                        )
                    }
                    r.receiptCode?.let {
                        CssText("REF $it", style = type.sans(12f, 16f, BfColour.MutedInk, tracking = 0.3f), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
                    }
                }
            } else {
                NoReceipt(orderId, type, small, onQuery)
            }
        }
        BfReceiptState.Failed -> NoReceipt(orderId, type, small, onQuery)
    }
}

@Composable
private fun NoReceipt(orderId: String, type: BfType, small: TextStyle, onQuery: (String) -> Unit) {
    Column {
        CssText("No receipt details available.", style = small, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
        CssText(
            "Query this transaction",
            style = type.sans(12f, 16f, BfColour.RoseInk),
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) { onQuery(BalfourHistoryModel.queryMailto(null, orderId)) },
        )
    }
}

/** StarRatingWidget in the ledger: idle label "Rate this visit" left of five 12 px stars (p-[3px], gap 3), copper when filled. */
@Composable
private fun StarRating(orderId: String, initial: Int?, art: BalfourArt, type: BfType, onRate: (String, Int) -> Unit) {
    var rating by remember(orderId) { mutableStateOf(initial) }
    var ack by remember(orderId) { mutableStateOf(false) }
    LaunchedEffect(ack) { if (ack) { delay(2200); ack = false } }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        // The label box is as wide as the longest of its two strings, both centred in it.
        Box(contentAlignment = Alignment.Center) {
            CssText("Rate this visit", style = type.archivo(12f, 16f, if (ack) Color.Transparent else BfColour.MutedInk, tracking = 0.3f), maxLines = 1)
            CssText("Thank you!", style = type.archivo(12f, 16f, if (ack) BfColour.MutedInk else Color.Transparent, tracking = 0.3f), maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            for (n in 1..5) {
                Box(
                    Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                        rating = n; ack = true; onRate(orderId, n)
                    }.padding(3.dp),
                ) {
                    val filled = n <= (rating ?: 0)
                    Image(painterResource(art.star), null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(if (filled) BfColour.Copper else InkSoft))
                }
            }
        }
    }
}

/** The BoldThings credit under every page: Archivo "powered by" and Poppins italic BOLDTHINGS, 10 px, py-4. */
@Composable
private fun PoweredBy(type: BfType) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicText("powered by", style = type.archivo(10f, null, BfColour.Credit.copy(alpha = 0.5f), weight = 300))
        BasicText("BOLDTHINGS", style = type.poppins(10f, BfColour.Credit.copy(alpha = 0.7f), tracking = 0.8f))
    }
}
