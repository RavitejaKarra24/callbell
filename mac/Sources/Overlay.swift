import AppKit
import SwiftUI

/// A small always-on-top card in the top-right corner. Unlike a system notification
/// it is not hidden by Focus / Do Not Disturb, and it shows over full-screen apps.
@MainActor
final class OverlayController {
    var onDismiss: (() -> Void)?

    private let model = OverlayModel()
    private var panel: NSPanel?
    private var hideWork: DispatchWorkItem?

    func isShowing(callId: String) -> Bool {
        panel?.isVisible == true && model.record?.id == callId
    }

    func show(_ record: CallRecord, autoHide: TimeInterval?) {
        model.record = record
        let panel = self.panel ?? makePanel()
        self.panel = panel
        if let content = panel.contentView {
            let size = content.fittingSize
            panel.setContentSize(size)
            if let screen = NSScreen.main {
                let f = screen.visibleFrame
                panel.setFrameOrigin(NSPoint(x: f.maxX - size.width - 16, y: f.maxY - size.height - 12))
            }
        }
        panel.orderFrontRegardless()

        hideWork?.cancel()
        if let autoHide {
            let work = DispatchWorkItem { [weak self] in self?.hide(userInitiated: false) }
            hideWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + autoHide, execute: work)
        }
    }

    func hide(userInitiated: Bool) {
        hideWork?.cancel()
        panel?.orderOut(nil)
        if userInitiated { onDismiss?() }
    }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 340, height: 90),
                            styleMask: [.borderless, .nonactivatingPanel],
                            backing: .buffered, defer: false)
        panel.isFloatingPanel = true
        panel.level = .statusBar
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        panel.hidesOnDeactivate = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.contentView = NSHostingView(rootView: OverlayView(model: model) { [weak self] in
            self?.hide(userInitiated: true)
        })
        return panel
    }
}

@MainActor
final class OverlayModel: ObservableObject {
    @Published var record: CallRecord?
}

struct OverlayView: View {
    @ObservedObject var model: OverlayModel
    let close: () -> Void

    var body: some View {
        if let r = model.record {
            HStack(alignment: .center, spacing: 12) {
                StatusIcon(status: r.status, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title(r))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(r.status.tint)
                        .textCase(.uppercase)
                    Text(r.status == .test ? "Connected to \(r.device ?? "phone")" : r.displayName)
                        .font(.title3.weight(.semibold))
                        .lineLimit(1)
                    if let sub = detail(r) {
                        Text(sub)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                Button(action: close) {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.secondary)
                        .frame(width: 22, height: 22)
                        .background(Circle().fill(.quaternary))
                }
                .buttonStyle(.plain)
                .help("Dismiss")
            }
            .padding(14)
            .frame(width: 340)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(.regularMaterial)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(r.status.tint.opacity(0.5), lineWidth: 1)
            )
            .onTapGesture(perform: close)
        }
    }

    private func title(_ r: CallRecord) -> String {
        switch r.status {
        case .ringing: return "Phone is ringing"
        case .missed: return "Missed call"
        case .answered: return "Call answered"
        case .test: return "CallBell test"
        }
    }

    private func detail(_ r: CallRecord) -> String? {
        let time = r.date.formatted(date: .omitted, time: .shortened)
        switch r.status {
        case .test: return "Calls will show up here."
        default:
            if let sub = r.subtitle { return "\(sub) · \(time)" }
            return time
        }
    }
}

extension CallRecord.Status {
    var tint: Color {
        switch self {
        case .ringing: return .blue
        case .missed: return .red
        case .answered: return .green
        case .test: return .teal
        }
    }

    var symbol: String {
        switch self {
        case .ringing: return "phone.fill"
        case .missed: return "phone.down.fill"
        case .answered: return "phone.fill"
        case .test: return "checkmark"
        }
    }
}

struct StatusIcon: View {
    let status: CallRecord.Status
    let size: CGFloat

    var body: some View {
        Image(systemName: status.symbol)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Circle().fill(status.tint))
    }
}
