package gs.boldthin.loyalty.card

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** What the card overlay draws natively: the launch-splash logo, and the colour under the card page before it paints. */
data class CardArt(
    /** The native launch-screen logo (the Android 12 splash icon: a 288 dp canvas with the logo centred on it). */
    @DrawableRes val splashLogo: Int,
    /** The card page's body colour (the My ID overlay bg-forest), painted before the page's first frame. */
    val pageBackground: Int,
)

// Offline cold launch: the OS launch screen (cream, logo 156 dp, screen-centred) hands over to this layer with no
// visible change, the logo holds for a beat, then one fade reveals the card. No ring: the offline launch has no
// network to wait on, so the calm handover is the whole animation (Kyle 2026-10-26: same care as the Randalls offline launch).
private val Cream = Color(0xFFFBF6ED)

// The splash_logo drawable is the Android 12 icon canvas (288 dp square, logo centred), so draw it at that size:
// squeezing it into the logo's own 156 dp width shrinks the logo and jumps it against the OS launch screen.
private const val IconCanvas = 288f

private const val HoldMs = 700
private const val FadeMs = 500

@Composable
fun CardLaunchSplash(art: CardArt, onDone: () -> Unit) {
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(HoldMs.toLong())
        fade.animateTo(0f, tween(FadeMs, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)))
        onDone()
    }
    Box(Modifier.fillMaxSize().alpha(fade.value).background(Cream).blockTouches(), contentAlignment = Alignment.Center) {
        Image(painterResource(art.splashLogo), contentDescription = null, modifier = Modifier.size(IconCanvas.dp))
    }
}

private fun Modifier.blockTouches(): Modifier = this.then(
    Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
)
