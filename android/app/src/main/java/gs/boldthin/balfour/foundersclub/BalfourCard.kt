package gs.boldthin.balfour.foundersclub

import gs.boldthin.loyalty.card.CardArt

/** Balfour artwork for the native member card: copies of balfour-founders-club src/assets/images files and
 * public/badges/add-to-google-wallet.svg, and the exact font files the site serves. */
object BalfourCard {
    @JvmField
    val ART = CardArt(
        logo = R.drawable.loyalty_screen_logo,
        pattern = R.drawable.loyalty_card_pattern,
        googleWalletBadge = R.drawable.loyalty_card_google_wallet,
        displayFont = R.font.optima_bold,
        sansFont = R.font.gotham_book,
    )
}
