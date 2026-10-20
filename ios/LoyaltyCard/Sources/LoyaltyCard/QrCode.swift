import Foundation

/// The QR modules exactly as the web card draws them: a line-for-line port of the nayuki qrcodegen that
/// qrcode.react embeds (smallest version for the level, boostEcl on, mask chosen by its own penalty
/// score, numeric/alphanumeric/byte segment as makeSegments picks it). CoreImage's CIQRCodeGenerator
/// chooses other masks, so it is not used. QrCodeTests checks the result against vectors from qrcode.react.
public struct QrCode {
    public enum Ecc: Int { case low = 0, medium, quartile, high
        var formatBits: Int { [1, 0, 3, 2][rawValue] }
    }

    public let size: Int
    private var modules: [Bool]
    private var isFunction: [Bool]
    private let version: Int
    private let ecc: Ecc

    public func isDark(_ x: Int, _ y: Int) -> Bool { modules[y * size + x] }

    /// qrcode.react's useQRCode: makeSegments(value), encodeSegments(segs, level), boostEcl true.
    public static func encode(_ text: String, ecc: Ecc = .medium) -> QrCode {
        encodeSegments(QrSegment.makeSegments(text), ecc: ecc)
    }

    static func encodeSegments(_ segs: [QrSegment], ecc minEcc: Ecc) -> QrCode {
        var version = 1
        var used = 0
        while true {
            let capacity = numDataCodewords(version, minEcc) * 8
            let bits = QrSegment.totalBits(segs, version)
            if let bits = bits, bits <= capacity { used = bits; break }
            precondition(version < 40, "Data too long")
            version += 1
        }
        var ecc = minEcc
        for e in [Ecc.medium, .quartile, .high] where used <= numDataCodewords(version, e) * 8 { ecc = e }
        var bb: [UInt8] = []
        for s in segs {
            appendBits(s.mode.bits, 4, &bb)
            appendBits(s.numChars, s.mode.charCountBits(version), &bb)
            bb += s.data
        }
        let capacity = numDataCodewords(version, ecc) * 8
        appendBits(0, min(4, capacity - bb.count), &bb)
        appendBits(0, (8 - bb.count % 8) % 8, &bb)
        var pad = 0xEC
        while bb.count < capacity { appendBits(pad, 8, &bb); pad ^= 0xEC ^ 0x11 }
        var codewords = [Int](repeating: 0, count: bb.count / 8)
        for (i, b) in bb.enumerated() { codewords[i >> 3] |= Int(b) << (7 - (i & 7)) }
        return QrCode(version: version, ecc: ecc, data: codewords)
    }

    private init(version: Int, ecc: Ecc, data: [Int]) {
        self.version = version
        self.ecc = ecc
        size = version * 4 + 17
        modules = [Bool](repeating: false, count: size * size)
        isFunction = modules
        drawFunctionPatterns()
        drawCodewords(addEccAndInterleave(data))
        var best = 0
        var minPenalty = Int.max
        for m in 0..<8 {
            applyMask(m)
            drawFormatBits(m)
            let p = penaltyScore()
            if p < minPenalty { best = m; minPenalty = p }
            applyMask(m)
        }
        applyMask(best)
        drawFormatBits(best)
        isFunction = []
    }

    // MARK: function patterns

    private mutating func setFunction(_ x: Int, _ y: Int, _ dark: Bool) {
        modules[y * size + x] = dark
        isFunction[y * size + x] = true
    }

    private mutating func drawFunctionPatterns() {
        for i in 0..<size {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3); drawFinder(size - 4, 3); drawFinder(3, size - 4)
        let pos = alignmentPositions()
        let n = pos.count
        for i in 0..<n { for j in 0..<n where !(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) {
            drawAlignment(pos[i], pos[j])
        } }
        drawFormatBits(0)
        drawVersion()
    }

    private mutating func drawFormatBits(_ mask: Int) {
        let data = ecc.formatBits << 3 | mask
        var rem = data
        for _ in 0..<10 { rem = (rem << 1) ^ ((rem >> 9) * 0x537) }
        let bits = (data << 10 | rem) ^ 0x5412
        for i in 0...5 { setFunction(8, i, bit(bits, i)) }
        setFunction(8, 7, bit(bits, 6))
        setFunction(8, 8, bit(bits, 7))
        setFunction(7, 8, bit(bits, 8))
        for i in 9..<15 { setFunction(14 - i, 8, bit(bits, i)) }
        for i in 0..<8 { setFunction(size - 1 - i, 8, bit(bits, i)) }
        for i in 8..<15 { setFunction(8, size - 15 + i, bit(bits, i)) }
        setFunction(8, size - 8, true)
    }

    private mutating func drawVersion() {
        guard version >= 7 else { return }
        var rem = version
        for _ in 0..<12 { rem = (rem << 1) ^ ((rem >> 11) * 0x1F25) }
        let bits = version << 12 | rem
        for i in 0..<18 {
            let a = size - 11 + i % 3, b = i / 3
            setFunction(a, b, bit(bits, i))
            setFunction(b, a, bit(bits, i))
        }
    }

    private mutating func drawFinder(_ x: Int, _ y: Int) {
        for dy in -4...4 { for dx in -4...4 {
            let d = max(abs(dx), abs(dy))
            let xx = x + dx, yy = y + dy
            if 0 <= xx && xx < size && 0 <= yy && yy < size { setFunction(xx, yy, d != 2 && d != 4) }
        } }
    }

    private mutating func drawAlignment(_ x: Int, _ y: Int) {
        for dy in -2...2 { for dx in -2...2 { setFunction(x + dx, y + dy, max(abs(dx), abs(dy)) != 1) } }
    }

    private func alignmentPositions() -> [Int] {
        if version == 1 { return [] }
        let num = version / 7 + 2
        let step = version == 32 ? 26 : Int((Double(version * 4 + 4) / Double(num * 2 - 2)).rounded(.up)) * 2
        var result = [6]
        var pos = size - 7
        while result.count < num { result.insert(pos, at: 1); pos -= step }
        return result
    }

    // MARK: codewords and masking

    private func addEccAndInterleave(_ data: [Int]) -> [Int] {
        let numBlocks = QrCode.blocks[ecc.rawValue][version]
        let eccLen = QrCode.eccPerBlock[ecc.rawValue][version]
        let raw = QrCode.numRawDataModules(version) / 8
        let numShort = numBlocks - raw % numBlocks
        let shortLen = raw / numBlocks
        let div = QrCode.rsDivisor(eccLen)
        var blocks: [[Int]] = []
        var k = 0
        for i in 0..<numBlocks {
            var dat = Array(data[k..<(k + shortLen - eccLen + (i < numShort ? 0 : 1))])
            k += dat.count
            let e = QrCode.rsRemainder(dat, div)
            if i < numShort { dat.append(0) }
            blocks.append(dat + e)
        }
        var result: [Int] = []
        for i in 0..<blocks[0].count {
            for (j, b) in blocks.enumerated() where i != shortLen - eccLen || j >= numShort { result.append(b[i]) }
        }
        return result
    }

    private mutating func drawCodewords(_ data: [Int]) {
        var i = 0
        var right = size - 1
        while right >= 1 {
            if right == 6 { right = 5 }
            for vert in 0..<size {
                for j in 0..<2 {
                    let x = right - j
                    let upward = (right + 1) & 2 == 0
                    let y = upward ? size - 1 - vert : vert
                    if !isFunction[y * size + x] && i < data.count * 8 {
                        modules[y * size + x] = bit(data[i >> 3], 7 - (i & 7))
                        i += 1
                    }
                }
            }
            right -= 2
        }
    }

    private mutating func applyMask(_ mask: Int) {
        for y in 0..<size { for x in 0..<size {
            let invert: Bool
            switch mask {
            case 0: invert = (x + y) % 2 == 0
            case 1: invert = y % 2 == 0
            case 2: invert = x % 3 == 0
            case 3: invert = (x + y) % 3 == 0
            case 4: invert = (x / 3 + y / 2) % 2 == 0
            case 5: invert = x * y % 2 + x * y % 3 == 0
            case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0
            default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if !isFunction[y * size + x] && invert { modules[y * size + x].toggle() }
        } }
    }

    // qrcodegen getPenaltyScore(), line for line.
    private func penaltyScore() -> Int {
        var result = 0
        for vertical in [false, true] {
            for line in 0..<size {
                var runColor = false
                var run = 0
                var history = [Int](repeating: 0, count: 7)
                for i in 0..<size {
                    let c = vertical ? isDark(line, i) : isDark(i, line)
                    if c == runColor {
                        run += 1
                        if run == 5 { result += 3 } else if run > 5 { result += 1 }
                    } else {
                        addHistory(run, &history)
                        if !runColor { result += countPatterns(history) * 40 }
                        runColor = c
                        run = 1
                    }
                }
                result += terminateAndCount(runColor, run, &history) * 40
            }
        }
        for y in 0..<(size - 1) { for x in 0..<(size - 1) {
            let c = isDark(x, y)
            if c == isDark(x + 1, y) && c == isDark(x, y + 1) && c == isDark(x + 1, y + 1) { result += 3 }
        } }
        let dark = modules.reduce(0) { $0 + ($1 ? 1 : 0) }
        let total = size * size
        let k = Int((Double(abs(dark * 20 - total * 10)) / Double(total)).rounded(.up)) - 1
        return result + k * 10
    }

    private func countPatterns(_ h: [Int]) -> Int {
        let n = h[1]
        let core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
        return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) + (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0)
    }

    private func terminateAndCount(_ runColor: Bool, _ runLength: Int, _ h: inout [Int]) -> Int {
        var len = runLength
        if runColor { addHistory(len, &h); len = 0 }
        len += size
        addHistory(len, &h)
        return countPatterns(h)
    }

    private func addHistory(_ runLength: Int, _ h: inout [Int]) {
        let len = h[0] == 0 ? runLength + size : runLength
        h.removeLast()
        h.insert(len, at: 0)
    }

    // MARK: tables

    static func numRawDataModules(_ ver: Int) -> Int {
        var r = (16 * ver + 128) * ver + 64
        if ver >= 2 {
            let n = ver / 7 + 2
            r -= (25 * n - 10) * n - 55
            if ver >= 7 { r -= 36 }
        }
        return r
    }

    static func numDataCodewords(_ ver: Int, _ e: Ecc) -> Int {
        numRawDataModules(ver) / 8 - eccPerBlock[e.rawValue][ver] * blocks[e.rawValue][ver]
    }

    static func rsDivisor(_ degree: Int) -> [Int] {
        var r = [Int](repeating: 0, count: degree - 1) + [1]
        var root = 1
        for _ in 0..<degree {
            for j in 0..<r.count {
                r[j] = rsMultiply(r[j], root)
                if j + 1 < r.count { r[j] ^= r[j + 1] }
            }
            root = rsMultiply(root, 2)
        }
        return r
    }

    static func rsRemainder(_ data: [Int], _ div: [Int]) -> [Int] {
        var r = [Int](repeating: 0, count: div.count)
        for b in data {
            let f = b ^ r.removeFirst()
            r.append(0)
            for (i, c) in div.enumerated() { r[i] ^= rsMultiply(c, f) }
        }
        return r
    }

    static func rsMultiply(_ x: Int, _ y: Int) -> Int {
        var z = 0
        for i in stride(from: 7, through: 0, by: -1) {
            z = (z << 1) ^ ((z >> 7) * 0x11D)
            z ^= ((y >> i) & 1) * x
        }
        return z
    }

    static let eccPerBlock: [[Int]] = [
        [-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
        [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28],
        [-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
        [-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
    ]

    static let blocks: [[Int]] = [
        [-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25],
        [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49],
        [-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68],
        [-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81],
    ]
}

/// qrcodegen QrSegment: the one segment makeSegments picks for the whole text.
struct QrSegment {
    struct Mode {
        let bits: Int
        let counts: [Int]
        func charCountBits(_ ver: Int) -> Int { counts[(ver + 7) / 17] }
        static let numeric = Mode(bits: 1, counts: [10, 12, 14])
        static let alphanumeric = Mode(bits: 2, counts: [9, 11, 13])
        static let byte = Mode(bits: 4, counts: [8, 16, 16])
    }

    let mode: Mode
    let numChars: Int
    let data: [UInt8]

    static let alphanumericCharset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:")

    static func makeSegments(_ text: String) -> [QrSegment] {
        if text.isEmpty { return [] }
        let chars = Array(text)
        if chars.allSatisfy({ $0.isASCII && $0.isNumber }) {
            var bb: [UInt8] = []
            var i = 0
            while i < chars.count {
                let n = min(chars.count - i, 3)
                appendBits(Int(String(chars[i..<(i + n)]))!, n * 3 + 1, &bb)
                i += n
            }
            return [QrSegment(mode: .numeric, numChars: chars.count, data: bb)]
        }
        if chars.allSatisfy({ alphanumericCharset.contains($0) }) {
            var bb: [UInt8] = []
            var i = 0
            while i + 2 <= chars.count {
                appendBits(alphanumericCharset.firstIndex(of: chars[i])! * 45 + alphanumericCharset.firstIndex(of: chars[i + 1])!, 11, &bb)
                i += 2
            }
            if i < chars.count { appendBits(alphanumericCharset.firstIndex(of: chars[i])!, 6, &bb) }
            return [QrSegment(mode: .alphanumeric, numChars: chars.count, data: bb)]
        }
        let bytes = Array(text.utf8)
        var bb: [UInt8] = []
        for b in bytes { appendBits(Int(b), 8, &bb) }
        return [QrSegment(mode: .byte, numChars: bytes.count, data: bb)]
    }

    /// Nil where qrcodegen returns Infinity (too many characters for the count field).
    static func totalBits(_ segs: [QrSegment], _ ver: Int) -> Int? {
        var r = 0
        for s in segs {
            let cc = s.mode.charCountBits(ver)
            if s.numChars >= 1 << cc { return nil }
            r += 4 + cc + s.data.count
        }
        return r
    }
}

private func appendBits(_ val: Int, _ len: Int, _ bb: inout [UInt8]) {
    var i = len - 1
    while i >= 0 { bb.append(UInt8((val >> i) & 1)); i -= 1 }
}

private func bit(_ x: Int, _ i: Int) -> Bool { (x >> i) & 1 != 0 }
