package gs.boldthin.balfour.foundersclub

import gs.boldthin.loyalty.screens.BalfourArt

/** Balfour artwork for the native loyalty screens (copies of balfour-founders-club public/ and src/assets files). */
object BalfourScreens {
    @JvmField
    val ART = BalfourArt(
        star = R.drawable.loyalty_screen_star,
        logo = R.drawable.loyalty_screen_logo,
        sansBook = R.font.gotham_book,
        sansBold = R.font.gotham_bold,
        displayBold = R.font.optima_bold,
        archivo = R.font.archivo,
        poppinsItalic = R.font.poppins_semibold_italic,
    )
}
