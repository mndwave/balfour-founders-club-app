package gs.boldthin.balfour.foundersclub

import gs.boldthin.loyalty.card.CardArt

/** Balfour's member card overlay: the launch logo, and the My ID overlay's colour (bg-forest, --color-forest) under the card page before it paints. */
object BalfourCard {
    @JvmField
    val ART = CardArt(
        splashLogo = R.drawable.splash_logo,
        pageBackground = 0xFF13342B.toInt(),
    )
}
