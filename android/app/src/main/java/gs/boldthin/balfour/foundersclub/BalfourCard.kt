package gs.boldthin.balfour.foundersclub

import gs.boldthin.loyalty.card.CardArt

/** Balfour's member card overlay: the My ID overlay's colour (bg-forest, --color-forest) under the card page before it paints. */
object BalfourCard {
    @JvmField
    val ART = CardArt(pageBackground = 0xFF13342B.toInt())
}
