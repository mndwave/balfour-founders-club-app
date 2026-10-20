import Foundation

// LOYALTY-NATIVE-CARD-IOS-2026-10-20 (healer:596fd32f, port of healer:716e5320's Android card; same core as
// randalls-rewards-app ios/LoyaltyCard). Kyle 2026-10-20: a faithful copy of the web member card; the only gain
// is that it is fast and works offline.
// No new visible element, no app-icon shortcut.

/// Where one brand's card comes from and where it is kept on the device.
public struct CardBrand {
    /// boldthings-services base; never seq1-healer (Kyle 2026-10-20, zero tolerance).
    public let apiBase: String
    /// GET path of the compact card payload (boldthings-services loyalty_native.rs get_card).
    public let cardPath: String
    /// Capacitor Preferences key holding the member's portal session token (mirrored by the web app).
    public let sessionKey: String
    /// Capacitor Preferences key of the card snapshot (written by this card).
    public let cacheKey: String
    /// GET path of the Apple Wallet pass; the web WalletButtons open it with ?token= in the system browser.
    public let appleWalletPath: String
    /// Scan prefix to assume while no snapshot has recorded one.
    public let defaultQrPrefix: String

    public static let balfour = CardBrand(
        apiBase: "https://api.boldthin.gs",
        cardPath: "/balfour/loyalty/portal/card",
        sessionKey: "balfour_member_token",
        cacheKey: "bf_offline_card_v1",
        appleWalletPath: "/balfour/loyalty/portal/wallet/apple",
        // Zonal reads the bare BF number / rotating token: wallet_qr_prefix is empty for balfour.
        defaultQrPrefix: ""
    )
}

/// The member card as kept on the device. Same JSON shape (v1) as randalls-rewards src/lib/offline-card.ts.
/// Balfour's web app does not write one; this card writes it itself after every live fetch (CardStore, key
/// bf_offline_card_v1) and the app also refreshes it in the background at launch (MemberCardOverlay.prewarm).
public struct CardSnapshot: Equatable {
    /// Tail of the session token: a snapshot that is not this device's member is ignored.
    public var tag: String
    public var memberNumber: String
    public var qrPrefix: String
    /// Latest rotating scan token; nil means the member number is the scan value.
    public var qrToken: String?
    public var name: String
    public var tier: String
    public var cashBalance: Double
    public var stars: Int64
    /// When the card data was last fetched live (epoch ms).
    public var syncedAt: Int64

    /// Exactly what the web card encodes: prefix + rotating token, else prefix + member number.
    public var scanValue: String { qrPrefix + (qrToken ?? memberNumber) }

    public static let tagLength = 8

    public static func tag(of sessionToken: String) -> String { String(sessionToken.suffix(tagLength)) }

    public func toJSON() -> String {
        let o: [String: Any] = [
            "v": 1, "tag": tag, "member_number": memberNumber, "qr_prefix": qrPrefix,
            "qr_token": qrToken ?? NSNull(), "name": name, "tier": tier,
            "cash_balance": cashBalance, "stars": stars, "synced_at": syncedAt,
        ]
        let data = (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])) ?? Data()
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// Parses a v1 snapshot; nil for anything malformed or from another version.
    public static func fromJSON(_ raw: String?, defaultPrefix: String) -> CardSnapshot? {
        guard let o = object(raw), (o["v"] as? NSNumber)?.intValue == 1,
              let number = string(o["member_number"]) else { return nil }
        return CardSnapshot(
            tag: string(o["tag"]) ?? "",
            memberNumber: number,
            qrPrefix: string(o["qr_prefix"]) ?? defaultPrefix,
            qrToken: string(o["qr_token"]),
            name: string(o["name"]) ?? "",
            tier: string(o["tier"]) ?? "",
            cashBalance: (o["cash_balance"] as? NSNumber)?.doubleValue ?? 0,
            stars: (o["stars"] as? NSNumber)?.int64Value ?? 0,
            syncedAt: (o["synced_at"] as? NSNumber)?.int64Value ?? 0
        )
    }

    /// Maps GET /loyalty/portal/card onto the snapshot. `qr.value` is the full scan value the till reads;
    /// when it is not the rotating token it is prefix + member number and qr_token stays nil, as the web
    /// app stores it.
    public static func fromCardPayload(_ raw: String, sessionToken: String, now: Int64, defaultPrefix: String) -> CardSnapshot? {
        guard let o = object(raw), let member = o["member"] as? [String: Any],
              let number = string(member["member_number"]), let qr = o["qr"] as? [String: Any] else { return nil }
        let prefix = string(qr["prefix"]) ?? defaultPrefix
        let value = string(qr["value"]) ?? ""
        let rotating = (qr["rotating"] as? NSNumber)?.boolValue ?? false
        let token = rotating && value.hasPrefix(prefix) && value.count > prefix.count ? String(value.dropFirst(prefix.count)) : nil
        let tier = o["tier"] as? [String: Any]
        let name = "\(string(member["first_name"]) ?? "") \(string(member["last_name"]) ?? "")".trimmingCharacters(in: .whitespaces)
        return CardSnapshot(
            tag: tag(of: sessionToken),
            memberNumber: number,
            qrPrefix: prefix,
            qrToken: token,
            name: name,
            tier: string(tier?["display_name"]) ?? string(tier?["name"]) ?? "",
            cashBalance: (o["cash_balance"] as? NSNumber)?.doubleValue ?? 0,
            stars: (o["stars_balance"] as? NSNumber)?.int64Value ?? 0,
            syncedAt: now
        )
    }

    private static func object(_ raw: String?) -> [String: Any]? {
        guard let raw = raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let data = raw.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    /// A non-empty string, else nil (JSON null, missing, blank).
    private static func string(_ v: Any?) -> String? {
        guard let s = v as? String, !s.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return s
    }
}

/// The card snapshot and session token on the device. Both live in UserDefaults under Capacitor
/// Preferences' "CapacitorStorage." prefix, where the web app mirrors the token (native.ts mirrorSessionToken)
/// and signs both out together.
public struct CardStore {
    public static let prefix = "CapacitorStorage."
    private let brand: CardBrand
    private let defaults: UserDefaults

    public init(brand: CardBrand, defaults: UserDefaults = .standard) {
        self.brand = brand
        self.defaults = defaults
    }

    public func sessionToken() -> String? {
        guard let t = defaults.string(forKey: CardStore.prefix + brand.sessionKey), !t.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return t
    }

    /// The snapshot, only if it belongs to the member signed in on this device.
    public func load() -> CardSnapshot? {
        guard let token = sessionToken(),
              let card = CardSnapshot.fromJSON(defaults.string(forKey: CardStore.prefix + brand.cacheKey), defaultPrefix: brand.defaultQrPrefix),
              card.tag == CardSnapshot.tag(of: token) else { return nil }
        return card
    }

    public func save(_ card: CardSnapshot) {
        defaults.set(card.toJSON(), forKey: CardStore.prefix + brand.cacheKey)
    }
}

/// GET /loyalty/portal/card from boldthings-services. Short timeouts: the card is already on screen from
/// the cache, this only freshens it.
public struct CardClient {
    public enum Result: Equatable {
        case ok(CardSnapshot)
        /// The session is no longer valid; the snapshot must not be refreshed with it.
        case signedOut
        /// The server answered with an error: not an offline state (the web page does not say so either).
        case failed
        /// No answer at all (no signal, timeout): the card says it is the saved one.
        case unreachable
    }

    public static let timeout: TimeInterval = 4
    private let brand: CardBrand
    private let session: URLSession

    public init(brand: CardBrand) {
        self.brand = brand
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = CardClient.timeout
        config.timeoutIntervalForResource = CardClient.timeout
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)
    }

    public func fetch(sessionToken: String, completion: @escaping (Result) -> Void) {
        guard let url = URL(string: brand.apiBase + brand.cardPath) else { return completion(.failed) }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: CardClient.timeout)
        req.setValue("Bearer \(sessionToken)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        session.dataTask(with: req) { data, response, error in
            if error != nil { return completion(.unreachable) }
            switch (response as? HTTPURLResponse)?.statusCode {
            case 200:
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                let body = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                completion(CardSnapshot.fromCardPayload(body, sessionToken: sessionToken, now: now, defaultPrefix: brand.defaultQrPrefix).map { .ok($0) } ?? .failed)
            case 401: completion(.signedOut)
            default: completion(.failed)
            }
        }.resume()
    }

    /// The Apple Wallet pass URL the web WalletButtons open (the pass is served to the system browser).
    public func appleWalletURL(sessionToken: String) -> URL? {
        var c = URLComponents(string: brand.apiBase + brand.appleWalletPath)
        c?.queryItems = [URLQueryItem(name: "token", value: sessionToken)]
        return c?.url
    }
}
