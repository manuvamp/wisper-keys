import Foundation
import UIKit

/// How the keyboard and the app talk. iOS doesn't let a keyboard use the microphone, so the app records (it keeps a
/// "voice session" running in the background) and the keyboard just says start/stop and shows what comes back.
///
/// Both sides share an App Group folder with three small JSON files and ping each other with Darwin notifications:
///   keyboard -> app:  command.json  {id, cmd: start|stop|cancel, before}      + "app.wisperkeys.cmd"
///   app -> keyboard:  session.json  {alive, heartbeat, pc, status, detail}
///                     dictation.json {id, state: recording|busy|done|failed, partial, text, error} + "app.wisperkeys.update"
enum Shared {
    static let appGroup = "group.app.wisperkeys"
    static let sessionURL = URL(string: "wisperkeys://session")!
    static let cmdNote = "app.wisperkeys.cmd"
    static let updateNote = "app.wisperkeys.update"

    static var container: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
    }

    static func read<T: Decodable>(_ type: T.Type, _ name: String) -> T? {
        guard let url = container?.appendingPathComponent(name), let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    static func write<T: Encodable>(_ value: T, _ name: String) {
        guard let url = container?.appendingPathComponent(name), let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }

    static func post(_ name: String) {
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(),
                                             CFNotificationName(name as CFString), nil, nil, true)
    }
}

struct SessionState: Codable {
    var alive: Bool
    var heartbeat: Double          // seconds since 1970; the app refreshes it every 2 s while the session runs
    var pc: String
    var status: String             // ready | connecting | offline | notPaired
    var detail: String

    /// The app is really there (it can be killed without getting the chance to say so).
    var isLive: Bool { alive && Date().timeIntervalSince1970 - heartbeat < 6 }
}

struct Command: Codable {
    var id: Int
    var cmd: String                // start | stop | cancel
    var before: String
}

struct DictationState: Codable {
    var id: Int
    var state: String              // recording | busy | done | failed | cancelled
    var partial: String
    var text: String
    var error: String
}

/// Calls `handler` on the main queue whenever another process posts the Darwin notification `name`.
final class DarwinObserver {
    private let name: String
    private let handler: () -> Void

    init(_ name: String, handler: @escaping () -> Void) {
        self.name = name
        self.handler = handler
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), Unmanaged.passUnretained(self).toOpaque(),
                                        { _, observer, _, _, _ in
                                            guard let observer else { return }
                                            let me = Unmanaged<DarwinObserver>.fromOpaque(observer).takeUnretainedValue()
                                            DispatchQueue.main.async { me.handler() }
                                        }, name as CFString, nil, .deliverImmediately)
    }

    deinit {
        CFNotificationCenterRemoveObserver(CFNotificationCenterGetDarwinNotifyCenter(), Unmanaged.passUnretained(self).toOpaque(),
                                           CFNotificationName(name as CFString), nil)
    }
}

/// Dark theme, the same as the Android keyboard. Recording red matches wisper's "Listening" pill on the PC.
enum Theme {
    static let bg = UIColor(hex: 0x101013)
    static let key = UIColor(hex: 0x2B2B31)
    static let keyDown = UIColor(hex: 0x41414A)
    static let mod = UIColor(hex: 0x1D1D22)
    static let modDown = UIColor(hex: 0x33333B)
    static let popup = UIColor(hex: 0x3A3A43)
    static let text = UIColor(hex: 0xF1F1F4)
    static let textDim = UIColor(hex: 0x8D8D98)
    static let textFaint = UIColor(hex: 0x4A4A52)
    static let accent = UIColor(hex: 0x5B7CFA)
    static let accentDown = UIColor(hex: 0x4A68DC)
    static let accentLight = UIColor(hex: 0x9DB1FF)
    static let rec = UIColor(hex: 0xEF4444)
    static let ok = UIColor(hex: 0x22C55E)
    static let warn = UIColor(hex: 0xF59E0B)
    static let card = UIColor(hex: 0x19191D)
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}
