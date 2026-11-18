import CryptoKit
import Foundation

/// Must match android/.../Crypto.kt:
///   3-byte magic | 12-byte nonce | AES-256-GCM ciphertext | 16-byte tag  (magic is AAD)
///   key = SHA-256("callbell-v1:" + normalized pairing code)
enum Crypto {
    static let eventMagic = Data("CB1".utf8)
    static let ackMagic = Data("CBA".utf8)

    static func normalize(_ code: String) -> String {
        String(code.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) })
    }

    static func key(fromCode code: String) -> SymmetricKey {
        SymmetricKey(data: SHA256.hash(data: Data("callbell-v1:\(normalize(code))".utf8)))
    }

    static func seal(_ plain: Data, magic: Data, key: SymmetricKey) -> Data? {
        guard let box = try? AES.GCM.seal(plain, using: key, authenticating: magic) else { return nil }
        let nonce = box.nonce.withUnsafeBytes { Data($0) }
        return magic + nonce + box.ciphertext + box.tag
    }

    static func open(_ packet: Data, magic: Data, key: SymmetricKey) -> Data? {
        let p = Data(packet) // zero-based indices
        guard p.count >= magic.count + 12 + 16, p.prefix(magic.count) == magic else { return nil }
        let nonceEnd = magic.count + 12
        guard
            let nonce = try? AES.GCM.Nonce(data: p.subdata(in: magic.count..<nonceEnd)),
            let box = try? AES.GCM.SealedBox(
                nonce: nonce,
                ciphertext: p.subdata(in: nonceEnd..<(p.count - 16)),
                tag: p.subdata(in: (p.count - 16)..<p.count))
        else { return nil }
        return try? AES.GCM.open(box, using: key, authenticating: magic)
    }

    /// 8 characters without look-alikes (no 0/O, 1/I/L), shown as ABCD-EFGH.
    static func newCode() -> String {
        let alphabet = Array("ABCDEFGHJKMNPQRSTUVWXYZ23456789")
        var rng = SystemRandomNumberGenerator()
        let chars = (0..<8).map { _ in alphabet[Int(rng.next(upperBound: UInt(alphabet.count)))] }
        return String(chars[0..<4]) + "-" + String(chars[4..<8])
    }
}
