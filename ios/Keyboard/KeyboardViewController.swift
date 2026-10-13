import SwiftUI
import UIKit

/// Lets the keyboard play the standard key click (follows the iPhone's Keyboard Clicks setting).
final class ClickInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

/// The bar above the keys: link status, or while you talk: cancel, the text so far, done. Undo after a dictation.
final class StripView: UIView {
    let dot = UIView()
    let label = UILabel()
    let cancel = UIButton(type: .system)
    let done = UIButton(type: .system)
    let undo = UIButton(type: .system)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = Theme.bg
        dot.layer.cornerRadius = 4
        label.font = .systemFont(ofSize: 14)
        label.textColor = Theme.textDim
        label.lineBreakMode = .byTruncatingHead       // keep the newest words in view
        cancel.setImage(UIImage(systemName: "xmark"), for: .normal)
        cancel.tintColor = Theme.text
        done.setImage(UIImage(systemName: "checkmark.circle.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 22)), for: .normal)
        done.tintColor = Theme.rec
        undo.setTitle(" Undo", for: .normal)
        undo.setImage(UIImage(systemName: "arrow.uturn.backward"), for: .normal)
        undo.tintColor = Theme.text
        undo.titleLabel?.font = .systemFont(ofSize: 13)
        undo.backgroundColor = Theme.key
        undo.layer.cornerRadius = 15
        undo.contentEdgeInsets = UIEdgeInsets(top: 0, left: 10, bottom: 0, right: 12)
        for v in [dot, label, cancel, done, undo] {
            v.translatesAutoresizingMaskIntoConstraints = false
            addSubview(v)
        }
        NSLayoutConstraint.activate([
            cancel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 4),
            cancel.centerYAnchor.constraint(equalTo: centerYAnchor),
            cancel.widthAnchor.constraint(equalToConstant: 40),
            cancel.heightAnchor.constraint(equalToConstant: 40),
            dot.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            dot.centerYAnchor.constraint(equalTo: centerYAnchor),
            dot.widthAnchor.constraint(equalToConstant: 8),
            dot.heightAnchor.constraint(equalToConstant: 8),
            label.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 46),
            label.centerYAnchor.constraint(equalTo: centerYAnchor),
            label.trailingAnchor.constraint(lessThanOrEqualTo: undo.leadingAnchor, constant: -8),
            undo.trailingAnchor.constraint(equalTo: done.leadingAnchor, constant: -4),
            undo.centerYAnchor.constraint(equalTo: centerYAnchor),
            undo.heightAnchor.constraint(equalToConstant: 30),
            done.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -6),
            done.centerYAnchor.constraint(equalTo: centerYAnchor),
            done.widthAnchor.constraint(equalToConstant: 44),
            done.heightAnchor.constraint(equalToConstant: 40),
        ])
        show(status: "", color: Theme.textFaint)
    }
    required init?(coder: NSCoder) { fatalError() }

    func show(status: String, color: UIColor) {
        cancel.isHidden = true
        done.isHidden = true
        dot.isHidden = false
        dot.backgroundColor = color
        label.textColor = Theme.textDim
        label.text = status
    }

    func showTalking(_ partial: String, recording: Bool) {
        cancel.isHidden = false
        done.isHidden = !recording
        dot.isHidden = true
        undo.isHidden = true
        label.textColor = partial.isEmpty ? Theme.textDim : Theme.text
        label.text = partial.isEmpty ? (recording ? "Listening…" : "Transcribing…") : partial
    }
}

final class KeyboardViewController: UIInputViewController, SurfaceDelegate {
    private let strip = StripView()
    private let surface = KeyboardSurface()
    private let overlay = CalloutOverlay()
    private var linkPad: UIHostingController<AnyView>?
    private var heightConstraint: NSLayoutConstraint?
    private let haptic = UIImpactFeedbackGenerator(style: .light)
    private var proxy: UITextDocumentProxy { textDocumentProxy }

    private var autoSpace = false
    private var lastWasSpace = false
    private var lastShiftTap = Date.distantPast

    // dictation (the app does the listening; see Shared.swift)
    private enum Dict { case idle, recording, busy }
    private var dict = Dict.idle
    private var dictId = 0
    private var micDownAt = Date()
    private var startedOnThisPress = false
    private var lastInsert: String?
    private var updates: DarwinObserver?
    private var poll: Timer?
    private var sessionTimer: Timer?
    private var live = false

    override func loadView() {
        let v = ClickInputView(frame: .zero, inputViewStyle: .keyboard)
        v.allowsSelfSizing = true
        inputView = v
        view = v
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Theme.bg
        surface.delegate = self
        surface.overlay = overlay
        for v in [strip, surface, overlay] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        NSLayoutConstraint.activate([
            strip.topAnchor.constraint(equalTo: view.topAnchor),
            strip.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            strip.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            strip.heightAnchor.constraint(equalToConstant: 44),
            surface.topAnchor.constraint(equalTo: strip.bottomAnchor),
            surface.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            surface.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            surface.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            overlay.topAnchor.constraint(equalTo: view.topAnchor),
            overlay.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            overlay.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        strip.cancel.addTarget(self, action: #selector(cancelTap), for: .touchUpInside)
        strip.done.addTarget(self, action: #selector(doneTap), for: .touchUpInside)
        strip.undo.addTarget(self, action: #selector(undoTap), for: .touchUpInside)
        strip.undo.isHidden = true
        updates = DarwinObserver(Shared.updateNote) { [weak self] in self?.update() }

        // Keyboards can't open apps in code (iOS 18+); a SwiftUI Link can. It covers the mic key when no voice
        // session is running, so one tap opens the app, which starts the session.
        let pad = UIHostingController(rootView: AnyView(Link(destination: Shared.sessionURL) {
            Color.clear.contentShape(Rectangle())
        }))
        pad.view.backgroundColor = .clear
        addChild(pad)
        view.addSubview(pad.view)
        pad.didMove(toParent: self)
        linkPad = pad
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        configureForField()
        refreshSession()
        sessionTimer?.invalidate()
        sessionTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.refreshSession() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if dict == .recording { cancelDictation() }
        surface.cancelAll()
        sessionTimer?.invalidate()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let landscape = view.bounds.width > view.bounds.height * 1.6 && view.bounds.width > 500
        let row: CGFloat = landscape ? 40 : 54
        if surface.rowHeight != row { surface.rowHeight = row }
        let h = 44 + surface.totalHeight
        if heightConstraint?.constant != h {
            heightConstraint?.isActive = false
            heightConstraint = view.heightAnchor.constraint(equalToConstant: h)
            heightConstraint?.priority = UILayoutPriority(999)
            heightConstraint?.isActive = true
        }
        placeLinkPad()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        configureForField()
    }

    private func configureForField() {
        let globe = needsInputModeSwitchKey
        let t = proxy.keyboardType ?? .default
        let current = surface.layout.name
        switch t {
        case .numberPad, .decimalPad, .asciiCapableNumberPad: if current != "number" { surface.layout = Layouts.number(globe: globe) }
        case .phonePad, .namePhonePad: if current != "phone" { surface.layout = Layouts.phone(globe: globe) }
        default:
            if current == "number" || current == "phone" || surface.layout.rows.last?.contains(where: { $0.code == .globe }) != globe {
                surface.layout = t == .numbersAndPunctuation ? Layouts.symbols(globe: globe) : Layouts.letters(globe: globe)
            }
        }
        surface.micEnabled = proxy.isSecureTextEntry != true
        let (label, accent): (String, Bool) = {
            switch proxy.returnKeyType ?? .default {
            case .go: return ("go", true)
            case .google, .search, .yahoo: return ("search", true)
            case .join: return ("join", true)
            case .next: return ("next", false)
            case .route: return ("route", true)
            case .send: return ("send", true)
            case .done: return ("done", true)
            case .emergencyCall: return ("call", true)
            case .continue: return ("continue", true)
            default: return ("return", false)
            }
        }()
        surface.returnLabel = label
        surface.returnAccent = accent
        updateAutoCaps()
        placeLinkPad()
    }

    private func updateAutoCaps() {
        guard surface.layout.letters, surface.shift != .lock else { return }
        let before = proxy.documentContextBeforeInput ?? ""
        let cap: Bool
        switch proxy.autocapitalizationType ?? .sentences {
        case .none: cap = false
        case .allCharacters: cap = true
        case .words: cap = before.isEmpty || before.last!.isWhitespace
        default:
            let trimmed = before.trimmingCharacters(in: .whitespaces)
            cap = trimmed.isEmpty || before.hasSuffix("\n") ||
                (before.last?.isWhitespace == true && ".!?".contains(trimmed.last!))
        }
        surface.shift = cap ? .on : .off
    }

    // ---------------------------------------------------------------- typing
    func keyFeedback(_ k: KKey) {
        if hasFullAccess { haptic.impactOccurred() }
        UIDevice.current.playInputClick()
        if k.code != .mic, lastInsert != nil { hideUndo() }
    }

    func typeChar(_ s: String) {
        let t = surface.layout.letters && surface.shift != .off ? s.uppercased() : s
        if autoSpace, t.count == 1, ".,!?;:)".contains(t), proxy.documentContextBeforeInput?.hasSuffix(" ") == true {
            proxy.deleteBackward()                      // "dictated words ." -> "dictated words. "
            proxy.insertText(t + " ")
        } else {
            proxy.insertText(t)
            autoSpace = false
        }
        lastWasSpace = false
        if surface.shift == .on { surface.shift = .off }
        updateAutoCaps()
    }

    func space() {
        let before = proxy.documentContextBeforeInput ?? ""
        if autoSpace && before.hasSuffix(" ") {
            // the space is already there
        } else if lastWasSpace, before.hasSuffix(" "), before.count >= 2,
                  let c = before.dropLast().last, c.isLetter || c.isNumber || "\"')]".contains(c) {
            proxy.deleteBackward()                      // double space -> ". "
            proxy.insertText(". ")
            lastWasSpace = false
            autoSpace = false
            backToLetters()
            return
        } else {
            proxy.insertText(" ")
        }
        autoSpace = false
        lastWasSpace = true
        backToLetters()
    }

    private func backToLetters() {
        if surface.layout.name == "symbols" || surface.layout.name == "symbols2" {
            surface.layout = Layouts.letters(globe: needsInputModeSwitchKey)
            placeLinkPad()
        }
        updateAutoCaps()
    }

    func backspace() {
        autoSpace = false
        lastWasSpace = false
        proxy.deleteBackward()
        updateAutoCaps()
    }

    /// Swipe left from delete, then let go: removes the last n words.
    func deleteWords(_ n: Int) {
        let before = Array(proxy.documentContextBeforeInput ?? "")
        var i = before.count
        for _ in 0..<n {
            while i > 0 && before[i - 1].isWhitespace { i -= 1 }
            while i > 0 && !before[i - 1].isWhitespace { i -= 1 }
        }
        for _ in 0..<(before.count - i) { proxy.deleteBackward() }
        updateAutoCaps()
    }

    func shiftTap() {
        let now = Date()
        switch surface.shift {
        case .off: surface.shift = .on
        case .on: surface.shift = now.timeIntervalSince(lastShiftTap) < 0.35 ? .lock : .off
        case .lock: surface.shift = .off
        }
        lastShiftTap = now
    }

    func moveCursor(_ steps: Int) {
        proxy.adjustTextPosition(byCharacterOffset: steps)
    }

    func enter() {
        autoSpace = false
        lastWasSpace = false
        proxy.insertText("\n")
        updateAutoCaps()
    }

    func switchMode(_ c: KCode) {
        let globe = needsInputModeSwitchKey
        switch c {
        case .symbols: surface.layout = Layouts.symbols(globe: globe)
        case .symbols2: surface.layout = Layouts.symbols2(globe: globe)
        default: surface.layout = Layouts.letters(globe: globe)
        }
        updateAutoCaps()
        placeLinkPad()
    }

    func globe(_ view: UIView, _ event: UIEvent?) {
        guard let event else { return }
        handleInputModeList(from: view, with: event)   // tap: next keyboard; hold: the list
    }

    // ---------------------------------------------------------------- dictation
    private func refreshSession() {
        let s = hasFullAccess ? Shared.read(SessionState.self, "session.json") : nil
        live = s?.isLive == true
        placeLinkPad()
        guard dict == .idle else { return }
        let pc = (s?.pc.isEmpty == false ? s!.pc : "PC")
        surface.mic = live && s?.status == "ready" ? .idle : .offline
        if !hasFullAccess {
            strip.show(status: "Turn on Allow Full Access to dictate", color: Theme.warn)
        } else if !live {
            strip.show(status: "Tap the mic to start a voice session", color: Theme.textFaint)
        } else {
            switch s?.status {
            case "ready": strip.show(status: s!.detail.isEmpty ? pc : "\(pc) · \(s!.detail)", color: Theme.ok)
            case "connecting": strip.show(status: "Connecting to \(pc)…", color: Theme.warn)
            case "notPaired": strip.show(status: "Pair with your PC in the wisper keys app", color: Theme.warn)
            default: strip.show(status: "\(pc) offline", color: Theme.rec)
            }
        }
        if lastInsert != nil { strip.undo.isHidden = false }
    }

    private func placeLinkPad() {
        guard let pad = linkPad?.view else { return }
        let show = hasFullAccess && !live && surface.micEnabled && dict == .idle
        pad.isHidden = !show
        if show, let r = surface.micRect { pad.frame = surface.convert(r, to: view) }
    }

    func micDown() {
        guard hasFullAccess else { return flash("Turn on Allow Full Access: Settings › General › Keyboard › Keyboards") }
        refreshSession()
        guard live else { return flash("Open wisper keys to start a voice session") }
        micDownAt = Date()
        startedOnThisPress = false
        if dict != .recording { startDictation(); startedOnThisPress = true }
    }

    func micUp() {
        guard dict == .recording else { return }
        // a quick tap leaves it listening (hands-free); a hold ends when you let go
        if !startedOnThisPress || Date().timeIntervalSince(micDownAt) >= 0.35 { stopDictation() }
    }

    private func send(_ cmd: String) {
        Shared.write(Command(id: dictId, cmd: cmd, before: cmd == "start" ? String((proxy.documentContextBeforeInput ?? "").suffix(300)) : ""), "command.json")
        Shared.post(Shared.cmdNote)
    }

    private func startDictation() {
        dictId = Int(Date().timeIntervalSince1970 * 1000) % 1_000_000_000
        send("start")
        dict = .recording
        hideUndo()
        surface.mic = .recording
        strip.showTalking("", recording: true)
        poll?.invalidate()
        poll = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in self?.update() }
    }

    private func stopDictation() {
        guard dict == .recording else { return }
        send("stop")
        dict = .busy
        surface.mic = .busy
        strip.showTalking("", recording: false)
    }

    private func cancelDictation() {
        send("cancel")
        endDictation()
    }

    private func endDictation() {
        dict = .idle
        poll?.invalidate()
        poll = nil
        refreshSession()
    }

    @objc private func cancelTap() { cancelDictation() }
    @objc private func doneTap() { stopDictation() }

    /// The app wrote something (Darwin notification) or the poll fired while a dictation is in flight.
    private func update() {
        guard dict != .idle else { return refreshSession() }
        guard let d = Shared.read(DictationState.self, "dictation.json"), d.id == dictId else { return }
        switch d.state {
        case "recording", "busy":
            if !d.partial.isEmpty { strip.showTalking(d.partial, recording: dict == .recording) }
        case "done":
            endDictation()
            if d.text.trimmingCharacters(in: .whitespaces).isEmpty { flash("Didn't catch that") } else { insert(d.text) }
        case "failed":
            endDictation()
            flash(d.error)
        case "cancelled":
            endDictation()
        default: break
        }
    }

    /// Dictated text lands with sensible spacing: a space before it if you were mid-word, and one after.
    private func insert(_ text: String) {
        let before = proxy.documentContextBeforeInput ?? ""
        let after = proxy.documentContextAfterInput ?? ""
        var s = text
        if let b = before.last, !b.isWhitespace, !"([{“‘/".contains(b), let f = text.first, !".,!?;:".contains(f) { s = " " + s }
        if after.isEmpty || !(after.first!.isWhitespace || ".,!?;:)]}".contains(after.first!)) { s += " "; autoSpace = true } else { autoSpace = false }
        proxy.insertText(s)
        lastWasSpace = false
        lastInsert = s
        strip.undo.isHidden = false
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
            if self?.lastInsert == s { self?.hideUndo() }
        }
        updateAutoCaps()
    }

    @objc private func undoTap() {
        if let s = lastInsert, (proxy.documentContextBeforeInput ?? "").hasSuffix(s) {
            for _ in 0..<s.count { proxy.deleteBackward() }
        }
        hideUndo()
        autoSpace = false
    }

    private func hideUndo() {
        lastInsert = nil
        strip.undo.isHidden = true
    }

    private func flash(_ msg: String) {
        strip.show(status: msg, color: Theme.warn)
        strip.label.textColor = Theme.text
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            if self?.dict == .idle { self?.refreshSession() }
        }
    }
}
