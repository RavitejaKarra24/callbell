import SwiftUI

@main
struct CallBellApp: App {
    @StateObject private var state = AppState.shared

    var body: some Scene {
        MenuBarExtra {
            MenuContent()
                .environmentObject(state)
        } label: {
            HStack(spacing: 3) {
                Image(systemName: state.unseen > 0 ? "phone.fill" : "phone")
                if state.unseen > 0 { Text("\(state.unseen)") }
            }
        }
        .menuBarExtraStyle(.window)
    }
}

struct MenuContent: View {
    @EnvironmentObject var state: AppState
    @State private var showPairing = false

    private let rowHeight: CGFloat = 40
    private let maxListHeight: CGFloat = 440

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            Divider()
            recentCalls
            if showPairing || state.calls.isEmpty {
                Divider()
                pairing
            }
            if let err = state.launchAtLoginError {
                Text(err).font(.caption).foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(14)
        .frame(width: 340)
        .onAppear { state.markSeen() }
    }

    private var header: some View {
        HStack(spacing: 6) {
            Text("CallBell").font(.headline)
            Circle()
                .fill(state.listenerError == nil ? Color.green : Color.red)
                .frame(width: 7, height: 7)
                .help(state.listenerError ?? "Listening for your phone")
            Spacer()
            settingsMenu
        }
    }

    private var settingsMenu: some View {
        Menu {
            Toggle("Play a sound", isOn: $state.soundOn)
            Toggle("Open at login", isOn: Binding(
                get: { state.launchAtLogin },
                set: { state.setLaunchAtLogin($0) }))
            Divider()
            Button(showPairing ? "Hide pairing code" : "Show pairing code") { showPairing.toggle() }
            Button("Preview alert") { state.sendLocalTest() }
            Button("Clear history") { state.clearHistory() }
                .disabled(state.calls.isEmpty)
            Divider()
            Button("Quit CallBell") { NSApplication.shared.terminate(nil) }
        } label: {
            Image(systemName: "gearshape")
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Settings")
    }

    @ViewBuilder private var recentCalls: some View {
        if let err = state.listenerError {
            Text(err).font(.caption).foregroundStyle(.red)
                .fixedSize(horizontal: false, vertical: true)
        }
        if state.calls.isEmpty {
            Text("No calls yet.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 60)
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(state.calls) { call in
                        CallRow(call: call)
                            .frame(height: rowHeight)
                    }
                }
            }
            // A ScrollView has no natural height inside a menu bar window, so size it explicitly.
            .frame(height: min(CGFloat(state.calls.count) * rowHeight, maxListHeight))
        }
    }

    private var pairing: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(state.code)
                    .font(.system(.title3, design: .monospaced).weight(.semibold))
                    .textSelection(.enabled)
                Spacer()
                Button("Copy") { state.copyCode() }
                Button("New") { state.regenerateCode() }
                    .help("Make a new code. The phone will need the new code too.")
            }
            Text(pairingCaption)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var pairingCaption: String {
        if let heard = state.lastHeard {
            return "Pairing code · last heard from \(state.lastHeardFrom ?? "phone") \(heard.formatted(.relative(presentation: .named)))"
        }
        return "Enter this code in the CallBell phone app, then tap “Send a test”."
    }
}

struct CallRow: View {
    let call: CallRecord

    var body: some View {
        HStack(spacing: 10) {
            StatusIcon(status: call.status, size: 26)
            VStack(alignment: .leading, spacing: 1) {
                Text(call.displayName)
                    .font(.callout.weight(call.status == .missed ? .semibold : .regular))
                    .foregroundStyle(call.status == .missed ? Color.red : Color.primary)
                    .lineLimit(1)
                Text([call.subtitle, label].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Text(timeText)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var label: String {
        switch call.status {
        case .ringing: return "Ringing"
        case .missed: return "Missed"
        case .answered: return "Answered"
        case .test: return "Test"
        }
    }

    private var timeText: String {
        Calendar.current.isDateInToday(call.date)
            ? call.date.formatted(date: .omitted, time: .shortened)
            : call.date.formatted(.dateTime.month(.abbreviated).day().hour().minute())
    }
}
