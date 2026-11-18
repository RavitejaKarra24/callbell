import CryptoKit
import Darwin
import Foundation

struct IncomingEvent: Decodable {
    let id: String
    let call: String?
    let type: String
    let number: String?
    let name: String?
    let device: String?
    let ts: Double
}

/// UDP listener on port 47823. Uses a BSD socket because Network.framework
/// does not reliably deliver broadcast datagrams.
final class Listener: @unchecked Sendable {
    static let port: UInt16 = 47823

    var onEvent: ((IncomingEvent, String) -> Void)?

    private let queue = DispatchQueue(label: "callbell.udp")
    private let lock = NSLock()
    private var key: SymmetricKey?
    private var fd: Int32 = -1
    private var source: DispatchSourceRead?
    private var seen: [String: Date] = [:] // event id -> first seen (accessed on `queue`)
    private let hostName = Host.current().localizedName ?? "Mac"

    func setCode(_ code: String) {
        lock.lock(); key = Crypto.key(fromCode: code); lock.unlock()
    }

    func start() throws {
        fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { throw err("socket") }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = Self.port.bigEndian
        addr.sin_addr = in_addr(s_addr: 0) // INADDR_ANY
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard rc == 0 else {
            let e = err("bind to port \(Self.port)")
            close(fd); fd = -1
            throw e
        }
        _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)

        let src = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
        src.setEventHandler { [weak self] in self?.drain() }
        src.resume()
        source = src
    }

    private func err(_ what: String) -> NSError {
        let msg = String(cString: strerror(errno))
        return NSError(domain: "CallBell", code: Int(errno),
                       userInfo: [NSLocalizedDescriptionKey: "Could not \(what): \(msg)"])
    }

    private func drain() {
        var buf = [UInt8](repeating: 0, count: 4096)
        while true {
            var from = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(fd, &buf, buf.count, 0, $0, &len)
                }
            }
            if n <= 0 { return }
            handle(Data(buf[0..<n]), from: from)
        }
    }

    private func handle(_ packet: Data, from: sockaddr_in) {
        lock.lock(); let key = self.key; lock.unlock()
        guard let key,
              let plain = Crypto.open(packet, magic: Crypto.eventMagic, key: key),
              let event = try? JSONDecoder().decode(IncomingEvent.self, from: plain)
        else { return } // not ours, or wrong pairing code

        // Always acknowledge so the phone stops retrying (also for duplicates).
        if let ackJSON = try? JSONSerialization.data(withJSONObject: ["id": event.id, "host": hostName]),
           let ack = Crypto.seal(ackJSON, magic: Crypto.ackMagic, key: key) {
            send(ack, to: from)
        }

        let now = Date()
        seen = seen.filter { now.timeIntervalSince($0.value) < 3600 }
        guard seen[event.id] == nil else { return }
        seen[event.id] = now
        // Ignore stale/replayed packets.
        guard abs(event.ts / 1000 - now.timeIntervalSince1970) < 15 * 60 else { return }

        let ip = String(cString: inet_ntoa(from.sin_addr))
        onEvent?(event, ip)
    }

    private func send(_ data: Data, to addr: sockaddr_in) {
        var addr = addr
        data.withUnsafeBytes { raw in
            withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    _ = sendto(fd, raw.baseAddress, raw.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }
}
