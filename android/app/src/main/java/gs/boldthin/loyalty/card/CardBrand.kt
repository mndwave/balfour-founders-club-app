package gs.boldthin.loyalty.card

// LOYALTY-NATIVE-CARD-BALFOUR-2026-10-20 (healer:716e5320, sub-task of healer:cdefd3a3): the native member
// card for Balfour, ported from randalls-rewards-app. Kyle 2026-10-20: a faithful copy of the web card
// (/my-id), no new visible element; the only gain is that it is fast and works offline. This package has
// no Capacitor dependency so it moves unchanged into the native shell that replaces Capacitor
// (healer:1c19eae5); until then MainActivity shows it whenever the live site cannot paint.

/** Where one brand's card comes from and where it is kept on the device. */
data class CardBrand(
    /** boldthings-services base; never seq1-healer (Kyle 2026-10-20, zero tolerance). */
    val apiBase: String,
    /** GET path of the compact card payload (boldthings-services loyalty_native.rs get_card). */
    val cardPath: String,
    /** Capacitor Preferences key holding the member's portal session token (mirrored by the web app). */
    val sessionKey: String,
    /** Capacitor Preferences key of the card snapshot (written by this card). */
    val cacheKey: String,
    /** GET path returning {saveUrl} for the Google Wallet pass (the web WalletButtons call). */
    val googleWalletPath: String,
    /** Scan prefix to assume while no snapshot has recorded one. */
    val defaultQrPrefix: String,
) {
    companion object {
        @JvmField
        val BALFOUR = CardBrand(
            apiBase = "https://api.boldthin.gs",
            cardPath = "/balfour/loyalty/portal/card",
            sessionKey = "balfour_member_token",
            cacheKey = "bf_offline_card_v1",
            googleWalletPath = "/balfour/loyalty/portal/wallet/google",
            // Zonal reads the bare BF number / rotating token: wallet_qr_prefix is empty for balfour.
            defaultQrPrefix = "",
        )
    }
}
