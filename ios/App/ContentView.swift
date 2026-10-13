import AVFoundation
import SwiftUI

struct ContentView: View {
    @ObservedObject var session = SessionController.shared
    @Environment(\.scenePhase) private var phase
    @State private var micAllowed = AVAudioSession.sharedInstance().recordPermission == .granted
    @State private var showManual = false
    @State private var manualURLs = ""
    @State private var manualToken = ""
    @State private var cameFromKeyboard = false
    @State private var tryText = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("wisper keys").font(.system(size: 28, weight: .bold)).foregroundColor(Color(Theme.text))
                Text("Your PC's voice typing, on your iPhone. The keyboard's mic key sends your voice to your PC.")
                    .font(.subheadline).foregroundColor(Color(Theme.textDim))

                if cameFromKeyboard && session.active {
                    card {
                        Label("Voice session is on", systemImage: "checkmark.circle.fill").foregroundColor(Color(Theme.ok)).font(.headline)
                        Text("Go back to your app (tap ◀ in the top-left corner) and press the mic key again.")
                            .foregroundColor(Color(Theme.text))
                    }
                }

                sessionCard
                step("1. Add the keyboard", done: false,
                     detail: "Settings › General › Keyboard › Keyboards › Add New Keyboard › wisper keys. Then tap it and turn on Allow Full Access (the keyboard needs it to talk to this app; nothing you type leaves your phone).",
                     action: "Open Settings") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                step("2. Allow the microphone", done: micAllowed,
                     detail: micAllowed ? "Allowed." : "This app does the listening; the keyboard can't use the mic on iOS.",
                     action: micAllowed ? nil : "Allow microphone") {
                    AVAudioSession.sharedInstance().requestRecordPermission { ok in DispatchQueue.main.async { micAllowed = ok } }
                }
                pairCard
                settingsCard

                Text("Try it").font(.headline).foregroundColor(Color(Theme.text)).padding(.top, 6)
                TextField("Type or dictate here…", text: $tryText, axis: .vertical)
                    .lineLimit(3...6)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color(Theme.card)))
                    .foregroundColor(Color(Theme.text))
                Text("Tips: drag on the space bar to move the cursor, swipe left from delete to remove whole words, hold a key for numbers and accents. Dictating needs your PC on with wisper running; away from home, use Tailscale.")
                    .font(.footnote).foregroundColor(Color(Theme.textDim))
            }
            .padding(18)
        }
        .background(Color(Theme.bg).ignoresSafeArea())
        .preferredColorScheme(.dark)
        .onOpenURL { url in
            if let p = Pairing.parse(url) {
                session.pair(p)
            } else if url.host == "session" {
                cameFromKeyboard = true
                session.start()
            }
        }
        .onChange(of: phase) { p in
            if p == .active { micAllowed = AVAudioSession.sharedInstance().recordPermission == .granted }
            if p != .active { cameFromKeyboard = false }
        }
    }

    private var sessionCard: some View {
        card {
            HStack {
                Circle().fill(Color(session.active ? Theme.rec : Theme.textFaint)).frame(width: 10, height: 10)
                Text(session.active ? "Voice session on" : "Voice session off").font(.headline).foregroundColor(Color(Theme.text))
                Spacer()
                Button(session.active ? "Stop" : "Start") { session.active ? session.stop() : session.start() }
                    .buttonStyle(.borderedProminent).tint(Color(session.active ? Theme.keyDown : Theme.accent))
            }
            Text(session.active
                 ? "The mic stays ready in the background (orange dot) and ends after \(session.idleMinutes == 0 ? "you stop it" : "\(session.idleMinutes) min without dictating")."
                 : "The keyboard starts it for you: press its mic key and this app opens once.")
                .font(.subheadline).foregroundColor(Color(Theme.textDim))
            if !session.error.isEmpty { Text(session.error).font(.subheadline).foregroundColor(Color(Theme.warn)) }
            if !session.lastText.isEmpty { Text("Last: \(session.lastText)").font(.footnote).foregroundColor(Color(Theme.textDim)).lineLimit(2) }
        }
    }

    private var pairCard: some View {
        let pc = session.pairing?.pc.isEmpty == false ? session.pairing!.pc : "your PC"
        let detail: String
        switch session.status {
        case .ready: detail = "Connected to \(pc)\(session.detail.isEmpty ? "" : " · \(session.detail)")."
        case .connecting: detail = "Connecting to \(pc)…"
        case .offline: detail = session.active ? "Paired with \(pc), but it isn't answering. Is the PC on, wisper running and the phone on the same Wi-Fi (or Tailscale)?" : "Paired with \(pc). It connects when the voice session starts."
        case .notPaired: detail = "On your PC run  python -I tools\\pair.py  in the wisper folder, then point the iPhone camera at the code and open it in wisper keys."
        }
        return card {
            stepTitle("3. Pair with your PC", done: session.status == .ready)
            Text(detail).font(.subheadline).foregroundColor(Color(Theme.textDim))
            Button(showManual ? "Hide" : "Enter it by hand") {
                showManual.toggle()
                manualURLs = session.pairing?.urls.joined(separator: "\n") ?? ""
                manualToken = session.pairing?.token ?? ""
            }.foregroundColor(Color(Theme.accentLight))
            if showManual {
                TextField("ws://192.168.0.10:47822 (one per line)", text: $manualURLs, axis: .vertical)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .padding(10).background(RoundedRectangle(cornerRadius: 8).fill(Color(Theme.bg)))
                TextField("token", text: $manualToken)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .padding(10).background(RoundedRectangle(cornerRadius: 8).fill(Color(Theme.bg)))
                Button("Save") {
                    let urls = manualURLs.split(whereSeparator: { $0 == "\n" || $0 == " " || $0 == "," }).map(String.init)
                        .filter { $0.hasPrefix("ws://") || $0.hasPrefix("wss://") }
                    guard !urls.isEmpty, !manualToken.isEmpty else { return }
                    session.pair(Pairing(urls: urls, token: manualToken.trimmingCharacters(in: .whitespaces), pc: session.pairing?.pc ?? ""))
                    showManual = false
                }.buttonStyle(.borderedProminent).tint(Color(Theme.accent))
            }
        }
    }

    private var settingsCard: some View {
        card {
            stepTitle("Session length", done: nil)
            Picker("End the session after", selection: $session.idleMinutes) {
                Text("5 min").tag(5)
                Text("15 min").tag(15)
                Text("1 hour").tag(60)
                Text("Never").tag(0)
            }.pickerStyle(.segmented)
            Text("How long the mic stays ready without dictating. Longer means fewer trips to this app.")
                .font(.footnote).foregroundColor(Color(Theme.textDim))
        }
    }

    private func stepTitle(_ title: String, done: Bool?) -> some View {
        HStack(spacing: 10) {
            if let done { Circle().fill(Color(done ? Theme.ok : Theme.textFaint)).frame(width: 10, height: 10) }
            Text(title).font(.headline).foregroundColor(Color(Theme.text))
        }
    }

    private func step(_ title: String, done: Bool, detail: String, action: String?, run: @escaping () -> Void) -> some View {
        card {
            stepTitle(title, done: done)
            Text(detail).font(.subheadline).foregroundColor(Color(Theme.textDim))
            if let action {
                Button(action, action: run).buttonStyle(.borderedProminent).tint(Color(Theme.accent))
            }
        }
    }

    private func card<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8, content: content)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color(Theme.card)))
    }
}
