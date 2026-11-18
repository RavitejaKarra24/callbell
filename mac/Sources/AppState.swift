import AppKit
import ServiceManagement
import SwiftUI

struct CallRecord: Codable, Identifiable, Equatable {
    enum Status: String, Codable { case ringing, missed, answered, test }

    var id: String // call id from the phone
    var name: String?
    var number: String?
    var device: String?
    var status: Status
    var date: Date

    var displayName: String { name ?? number ?? "Unknown caller" }
    /// Number shown under the name, only when a name exists.
    var subtitle: String? { name != nil ? number : nil }
}

@MainActor
final class AppState: ObservableObject {
    static let shared = AppState()

    @Published private(set) var code: String
    @Published private(set) var calls: [CallRecord]
    @Published var unseen = 0
    @Published private(set) var listenerError: String?
    @Published private(set) var lastHeard: Date?
    @Published private(set) var lastHeardFrom: String?
    @Published var soundOn: Bool { didSet { defaults.set(soundOn, forKey: "soundOn") } }
    @Published private(set) var launchAtLogin = SMAppService.mainApp.status == .enabled
    @Published private(set) var launchAtLoginError: String?

    let overlay = OverlayController()
    private let listener = Listener()
    private let defaults = UserDefaults.standard
    private let maxHistory = 30

    private init() {
        let defaults = UserDefaults.standard
        if let saved = defaults.string(forKey: "code"), Crypto.normalize(saved).count == 8 {
            code = saved
        } else {
            let fresh = Crypto.newCode()
            defaults.set(fresh, forKey: "code")
            code = fresh
        }
        if let data = defaults.data(forKey: "calls"),
           let decoded = try? JSONDecoder().decode([CallRecord].self, from: data) {
            calls = decoded
        } else {
            calls = []
        }
        soundOn = defaults.object(forKey: "soundOn") as? Bool ?? true
        unseen = defaults.integer(forKey: "unseen")

        overlay.onDismiss = { [weak self] in self?.markSeen() }
        listener.setCode(code)
        listener.onEvent = { event, ip in
            Task { @MainActor in AppState.shared.handle(event, from: ip) }
        }
        do {
            try listener.start()
        } catch {
            listenerError = error.localizedDescription
        }
    }

    // MARK: - Events from the phone

    func handle(_ e: IncomingEvent, from ip: String) {
        lastHeard = Date()
        lastHeardFrom = e.device.map { "\($0) (\(ip))" } ?? ip

        guard let status = CallRecord.Status(rawValue: e.type) else { return }
        var record = CallRecord(id: e.call ?? e.id, name: e.name, number: e.number,
                                device: e.device, status: status, date: Date())

        if status == .test {
            overlay.show(record, autoHide: 6)
            play("Glass")
            return
        }

        // Update an existing entry for the same call, keeping its original time.
        if let i = calls.firstIndex(where: { $0.id == record.id }) {
            let previous = calls[i]
            record.date = previous.date
            record.name = record.name ?? previous.name
            record.number = record.number ?? previous.number
            calls.remove(at: i)
            calls.insert(record, at: 0)
            if previous.status == .missed && status != .missed { return }
        } else {
            calls.insert(record, at: 0)
        }
        calls = Array(calls.prefix(maxHistory))
        save()

        switch status {
        case .ringing:
            overlay.show(record, autoHide: nil)
            play("Glass")
        case .missed:
            unseen += 1
            defaults.set(unseen, forKey: "unseen")
            let wasShowingRing = overlay.isShowing(callId: record.id)
            overlay.show(record, autoHide: nil) // stays until dismissed
            if !wasShowingRing { play("Glass") }
        case .answered:
            if overlay.isShowing(callId: record.id) { overlay.show(record, autoHide: 4) }
        case .test:
            break
        }
    }

    private func play(_ name: String) {
        guard soundOn else { return }
        NSSound(named: NSSound.Name(name))?.play()
    }

    // MARK: - User actions

    func markSeen() {
        unseen = 0
        defaults.set(0, forKey: "unseen")
    }

    func regenerateCode() {
        code = Crypto.newCode()
        defaults.set(code, forKey: "code")
        listener.setCode(code)
    }

    func copyCode() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(code, forType: .string)
    }

    func clearHistory() {
        calls = []
        markSeen()
        save()
    }

    func setLaunchAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            launchAtLoginError = nil
        } catch {
            launchAtLoginError = "Couldn't change login item. Move CallBell to /Applications and try again."
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }

    func sendLocalTest() {
        let e = IncomingEvent(id: UUID().uuidString, call: nil, type: "test", number: nil,
                              name: nil, device: "This Mac", ts: Date().timeIntervalSince1970 * 1000)
        overlay.show(CallRecord(id: e.id, name: nil, number: nil, device: e.device, status: .test, date: Date()),
                     autoHide: 6)
        play("Glass")
    }

    private func save() {
        if let data = try? JSONEncoder().encode(calls) { defaults.set(data, forKey: "calls") }
    }
}
