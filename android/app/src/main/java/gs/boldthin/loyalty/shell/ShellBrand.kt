package gs.boldthin.loyalty.shell

// LOYALTY-NATIVE-SHELL-2026-10-20 (healer:1c19eae5): what the shell needs to know about one brand. Hosts, paths and
// cookie names are the contract's (boldthings-services definitions/loyalty_screens: screens.toml web paths, README
// WebView table, parity.toml registry_url). Calls go only to boldthings-services and the brand's hosted web app.

data class ShellBrand(
    /** The hosted client web app, no trailing slash. */
    val webBase: String,
    /** boldthings-services GET …/loyalty/portal/screens for this tenant. */
    val registryUrl: String,
    /** Hosted page path -> screen id (screens.toml [screens.<id>.web]). */
    val screenPaths: Map<String, String>,
    /** The page background, painted under the WebView before its first frame (no white flash). */
    val background: Int,
    /** CSS selector of the hosted page's own menu button (its mobile hamburger), clicked when a native screen hands the menu over. */
    val menuTrigger: String = ".mobile-nav-trigger",
) {
    /** The screen id a hosted path shows, ignoring query, fragment and a trailing slash. */
    fun screenFor(path: String): String? {
        val p = path.substringBefore('#').substringBefore('?').trimEnd('/').ifEmpty { "/" }
        return screenPaths[p]
    }

    companion object {
        @JvmField
        val RANDALLS = ShellBrand(
            webBase = "https://rewards.randalls.je",
            registryUrl = "https://api.boldthin.gs/loyalty/portal/screens",
            screenPaths = mapOf("/qr" to "card", "/tiers" to "tiers", "/history" to "history"),
            background = 0xFFF6F5F1.toInt(),
        )

        @JvmField
        val BALFOUR = ShellBrand(
            webBase = "https://founders.balfourwinery.com",
            registryUrl = "https://api.boldthin.gs/balfour/loyalty/portal/screens",
            screenPaths = mapOf("/my-id" to "card", "/history" to "history"),
            background = 0xFFFBF6ED.toInt(),
            menuTrigger = ".sidebar-nav-trigger",
        )
    }
}
