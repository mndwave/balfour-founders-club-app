import XCTest
@testable import LoyaltyCard

/// Vectors from qrcode.react (nayuki qrcodegen, level M, boostEcl default), the encoder the web card uses;
/// the first is the same vector as the Android QrMatrixTest.
final class QrCodeTests: XCTestCase {
    struct Vector: Decodable { let value: String; let rows: [String] }

    func testMatchesWebEncoder() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "qr-vectors", withExtension: "json"))
        let vectors = try JSONDecoder().decode([Vector].self, from: Data(contentsOf: url))
        XCTAssertGreaterThan(vectors.count, 4)
        for v in vectors {
            let qr = QrCode.encode(v.value)
            let rows = (0..<qr.size).map { y in (0..<qr.size).map { x in qr.isDark(x, y) ? "1" : "0" }.joined() }
            XCTAssertEqual(rows, v.rows, v.value)
        }
    }
}

final class CardSnapshotTests: XCTestCase {
    private let token = "session-token-abcdefgh"

    private func payload(_ value: String, rotating: Bool, displayName: String? = "Oyster") -> String {
        """
        {"tenant":"randalls","member":{"id":"m1","member_number":"100234","first_name":"Ann","last_name":"Le Brun"},
         "tier":{"name":"oyster","display_name":\(displayName.map { "\"\($0)\"" } ?? "null"),"colour_hex":null},
         "stars_balance":12,"cash_balance":4.5,"point_balance":0,
         "qr":{"value":"\(value)","prefix":"RWRDS","rotating":\(rotating),"fallback_member_number":"RWRDS100234"},
         "fetched_at":"2026-10-20T07:00:00Z"}
        """
    }

    func testRotatingTokenIsKeptApartFromThePrefix() throws {
        let c = try XCTUnwrap(CardSnapshot.fromCardPayload(payload("RWRDSK7Q2M9X4", rotating: true), sessionToken: token, now: 42, defaultPrefix: "RWRDS"))
        XCTAssertEqual(c.qrToken, "K7Q2M9X4")
        XCTAssertEqual(c.scanValue, "RWRDSK7Q2M9X4")
        XCTAssertEqual(c.name, "Ann Le Brun")
        XCTAssertEqual(c.tier, "Oyster")
        XCTAssertEqual(c.tag, "abcdefgh")
        XCTAssertEqual(c.syncedAt, 42)
        XCTAssertEqual(c.stars, 12)
    }

    func testMemberNumberScanValueStoresNoToken() throws {
        let c = try XCTUnwrap(CardSnapshot.fromCardPayload(payload("RWRDS100234", rotating: false, displayName: nil), sessionToken: token, now: 1, defaultPrefix: "RWRDS"))
        XCTAssertNil(c.qrToken)
        XCTAssertEqual(c.scanValue, "RWRDS100234")
        XCTAssertEqual(c.tier, "oyster")
    }

    func testRoundTripsTheWebSnapshotShape() throws {
        let web = """
        {"v":1,"tag":"abcdefgh","member_number":"100234","qr_prefix":"RWRDS","qr_token":null,
         "name":"Ann","tier":"Oyster","cash_balance":0,"stars":3,"synced_at":7}
        """
        let c = try XCTUnwrap(CardSnapshot.fromJSON(web, defaultPrefix: "RWRDS"))
        XCTAssertEqual(c, CardSnapshot.fromJSON(c.toJSON(), defaultPrefix: "RWRDS"))
        XCTAssertNil(CardSnapshot.fromJSON(#"{"v":2,"member_number":"1"}"#, defaultPrefix: "RWRDS"))
    }

    /// Balfour: no scan prefix; the rotating token is the whole scan value, the member number stays apart.
    func testBalfourRotatingTokenHasNoPrefix() throws {
        let raw = """
        {"tenant":"balfour","member":{"member_number":"BF100234","first_name":null,"last_name":null},
         "tier":{"name":"founder","display_name":"Founder"},"stars_balance":0,"cash_balance":0,
         "qr":{"value":"48213907","prefix":"","rotating":true,"fallback_member_number":"BF100234"}}
        """
        let c = try XCTUnwrap(CardSnapshot.fromCardPayload(raw, sessionToken: token, now: 5, defaultPrefix: CardBrand.balfour.defaultQrPrefix))
        XCTAssertEqual(c.scanValue, "48213907")
        XCTAssertEqual(c.memberNumber, "BF100234")
        XCTAssertEqual(c.name, "")
        let fallback = CardSnapshot(tag: c.tag, memberNumber: "BF100234", qrPrefix: "", qrToken: nil, name: "", tier: "", cashBalance: 0, stars: 0, syncedAt: 0)
        XCTAssertEqual(fallback.scanValue, "BF100234")
    }

    func testStoreIgnoresAnotherMembersSnapshot() throws {
        let d = try XCTUnwrap(UserDefaults(suiteName: "loyalty-card-test"))
        d.removePersistentDomain(forName: "loyalty-card-test")
        let store = CardStore(brand: .balfour, defaults: d)
        d.set(token, forKey: "CapacitorStorage.balfour_member_token")
        let mine = CardSnapshot(tag: "abcdefgh", memberNumber: "100234", qrPrefix: "RWRDS", qrToken: nil, name: "Ann", tier: "Oyster", cashBalance: 0, stars: 0, syncedAt: 1)
        store.save(mine)
        XCTAssertEqual(store.load(), mine)
        d.set("other-session-zzzzzzzz", forKey: "CapacitorStorage.balfour_member_token")
        XCTAssertNil(store.load())
    }
}

final class CardTypeTests: XCTestCase {
    /// The member number lays out with WebKit's ui-monospace metrics, not CoreText's (RENDER-GATE-TEXT-ORIGIN-2026-10-24):
    /// if the system monospaced font's name ever stops matching, the number silently moves back up.
    func testMonospacedLinesUseWebKitMetrics() {
        let m = WebKitLineMetrics.of(CardType.mono(12))
        XCTAssertEqual(m.ascent, 11.598, accuracy: 0.001, "\(CardType.mono(12).fontName) \(CTFontCopyFamilyName(CardType.mono(12)) as String)")
        XCTAssertEqual(m.ascent + m.descent, 14.133, accuracy: 0.001)
    }
}
