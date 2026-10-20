package gs.boldthin.loyalty.card

import androidx.annotation.DrawableRes
import androidx.annotation.FontRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

/** Balfour's own artwork and font (the same files the web page loads). */
data class CardArt(
    /** The Balfour Winery logo (src/assets/images/logo.webp); drawn white, as `brightness-0 invert`. */
    @DrawableRes val logo: Int,
    /** src/assets/images/pattern.webp, covering the page. */
    @DrawableRes val pattern: Int,
    @DrawableRes val googleWalletBadge: Int,
    /** font-display: Optima bold, for the FOUNDER label. */
    @FontRes val displayFont: Int,
    /** font-sans: Gotham Book, the page body font. */
    @FontRes val sansFont: Int,
)

// loyalty-screen: card@1 (the /my-id page; emit-screens-json.sh reads this marker for the build manifest)
// Colours from balfour-founders-club src/app/globals.css.
private val Forest = Color(0xFF13342B)
private val Cream = Color(0xFFFBF6ED)
private val Ink = Color(0xFF212322)

// Fallbacks of the CMS-bound copy keys the web card shows (member_card.founder_label / member_card.staff_prompt).
private const val FOUNDER_LABEL = "FOUNDER"
private const val STAFF_PROMPT = "Show this to a member of staff"

/**
 * Native copy of balfour-founders-club src/components/MyIdOverlay.tsx + MyIdCardContent.tsx, measure for
 * measure (CSS px == dp in the WebView). Shown settled, as the web overlay paints once its spring has run.
 */
@Composable
fun BalfourMemberCard(
    card: CardSnapshot,
    offline: Boolean,
    art: CardArt,
    onClose: () -> Unit,
    onAddToGoogleWallet: () -> Unit,
) {
    val density = LocalDensity.current
    val type = remember(art, density) { CardType(art, density) }
    val scanValue = card.scanValue
    val qr = remember(scanValue) { QrMatrix.encode(scanValue) }
    val clipboard = LocalClipboardManager.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val logo = painterResource(art.logo)
    val logoRatio = logo.intrinsicSize.let { if (it.height > 0f) it.width / it.height else 1f }

    Box(Modifier.fillMaxSize().background(Forest)) {
        Image(
            painter = painterResource(art.pattern),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(Modifier.fillMaxSize()) {
            // Header: shrink-0, px-6 pt-6 pb-4 (native myid-header-safe-top: safe-area-inset-top + 1.5rem).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = statusTop + 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp))
                Image(
                    painter = logo,
                    contentDescription = "Balfour Winery",
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.height(48.dp).aspectRatio(logoRatio),
                )
                CloseButton(onClose)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val areaH = maxHeight
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // flex-1 items-center justify-center over the scroll area; content is max-w-sm px-6 py-24 gap-6.
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = areaH),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            Modifier.widthIn(max = 384.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            BasicText(FOUNDER_LABEL, style = type.display(14f, 20f, 3.92f, Cream))
                            QrCardBox(qr, card.memberNumber, type) { clipboard.setText(AnnotatedString(card.memberNumber)) }
                            BasicText(STAFF_PROMPT, style = type.sans(16f, 26f, 0f, Cream))
                            if (offline) {
                                BasicText(
                                    "NO CONNECTION · SAVED CARD",
                                    style = type.sans(12f, 16f, 1.2f, Cream.copy(alpha = 0.7f)),
                                )
                            }
                            // WalletButtons on Android: the Google Wallet badge only, h-10 (min-width 100).
                            Image(
                                painter = painterResource(art.googleWalletBadge),
                                contentDescription = "Add to Google Wallet",
                                modifier = Modifier
                                    .height(40.dp)
                                    .aspectRatio(283f / 50f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onAddToGoogleWallet),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** MemberQrCode: `flex flex-col items-center bg-white p-4 w-full rounded-lg`, QR 288, member number mt-4 below it. */
@Composable
private fun QrCardBox(qr: QrMatrix, memberNumber: String, type: CardType, onCopy: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .requiredSize(288.dp)
                .semantics { contentDescription = "Your member QR code" },
        ) { drawQr(qr) }
        Box(
            Modifier
                .padding(top = 16.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onCopy),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(memberNumber, style = type.mono(12f, 16f, 2f, Ink.copy(alpha = 0.7f)))
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    // h-11 w-11 button, 16x16 svg of a 14x14 X, cream 1.5 round strokes.
    Box(
        Modifier
            .size(44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val u = size.width / 14f
            val w = 1.5f * u
            drawLine(Cream, Offset(1 * u, 1 * u), Offset(13 * u, 13 * u), w, StrokeCap.Round)
            drawLine(Cream, Offset(13 * u, 1 * u), Offset(1 * u, 13 * u), w, StrokeCap.Round)
        }
    }
}

/** Modules snapped to device pixels, as Chrome paints the SVG's shape-rendering="crispEdges". */
private fun DrawScope.drawQr(qr: QrMatrix) {
    val n = qr.size
    val m = size.width / n
    drawRect(Color.White)
    for (y in 0 until n) {
        val top = (y * m).roundToInt().toFloat()
        val bottom = ((y + 1) * m).roundToInt().toFloat()
        var x = 0
        while (x < n) {
            if (!qr.isDark(x, y)) { x++; continue }
            var end = x
            while (end < n && qr.isDark(end, y)) end++
            val left = (x * m).roundToInt().toFloat()
            val right = (end * m).roundToInt().toFloat()
            drawRect(Ink, Offset(left, top), Size(right - left, bottom - top))
            x = end
        }
    }
}

/** Text styles in CSS px: the WebView does not scale with the system font size, so neither does the card. */
private class CardType(art: CardArt, private val density: Density) {
    private val displayFamily = FontFamily(Font(art.displayFont, FontWeight.Bold))
    private val sansFamily = FontFamily(Font(art.sansFont, FontWeight.Normal))

    private fun px(v: Float) = (v / density.fontScale).sp

    private fun style(family: FontFamily, weight: FontWeight, size: Float, line: Float, tracking: Float, color: Color) = TextStyle(
        color = color,
        fontFamily = family,
        fontWeight = weight,
        fontSize = px(size),
        lineHeight = px(line),
        letterSpacing = px(tracking),
        textAlign = TextAlign.Center,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        textMotion = TextMotion.Animated,
    )

    fun display(size: Float, line: Float, tracking: Float, color: Color) = style(displayFamily, FontWeight.Bold, size, line, tracking, color)
    fun sans(size: Float, line: Float, tracking: Float, color: Color) = style(sansFamily, FontWeight.Normal, size, line, tracking, color)
    fun mono(size: Float, line: Float, tracking: Float, color: Color) = style(FontFamily.Monospace, FontWeight.Normal, size, line, tracking, color)
}
