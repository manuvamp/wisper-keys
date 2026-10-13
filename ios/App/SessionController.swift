import AVFoundation
import Foundation

/// The microphone, converted to what wisper expects: 16 kHz mono 16-bit little-endian PCM.
final class Mic {
    private let engine = AVAudioEngine()
    private let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true)!
    private var converter: AVAudioConverter?
    var onPCM: ((Data, Float) -> Void)?          // audio, level (RMS 0..1); called on the audio thread

    var running: Bool { engine.isRunning }

    func start() throws {
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        converter = AVAudioConverter(from: format, to: target)
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buf, _ in self?.convert(buf) }
        engine.prepare()
        try engine.start()
    }

    func stop() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
    }

    private func convert(_ buf: AVAudioPCMBuffer) {
        guard let conv = converter else { return }
        let cap = AVAudioFrameCount(Double(buf.frameLength) * target.sampleRate / buf.format.sampleRate + 32)
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: cap) else { return }
        var fed = false
        var err: NSError?
        conv.convert(to: out, error: &err) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true
            status.pointee = .haveData
            return buf
        }
        guard out.frameLength > 0, let ch = out.int16ChannelData else { return }
        let n = Int(out.frameLength)
        var sum: Float = 0
        for i in 0..<n { let v = Float(ch[0][i]) / 32768; sum += v * v }
        onPCM?(Data(bytes: ch[0], count: n * 2), (sum / Float(n)).squareRoot())
    }
}

/// Audio-thread state: which dictation is recording, and the last 0.4 s heard before it started (the keyboard's
/// "start" takes a moment to arrive, so the first word is never cut).
private final class AudioGate {
    private let lock = NSLock()
    private var preroll = Data()
    private var id: Int?
    private let prerollBytes = 16000 * 2 * 4 / 10

    /// -> the dictation the audio belongs to, or nil (kept as pre-roll).
    func feed(_ data: Data) -> Int? {
        lock.lock(); defer { lock.unlock() }
        if let id { return id }
        preroll.append(data)
        if preroll.count > prerollBytes { preroll.removeFirst(preroll.count - prerollBytes) }
        return nil
    }

    func begin(_ newId: Int) -> Data {
        lock.lock(); defer { lock.unlock() }
        id = newId
        let p = preroll
        preroll = Data()
        return p
    }

    func end() {
        lock.lock(); defer { lock.unlock() }
        id = nil
    }

    var current: Int? { lock.lock(); defer { lock.unlock() }; return id }
}

/// The voice session: while it's on, the app keeps the mic open in the background (iOS shows the orange dot) and the
/// link to the PC up, so the keyboard's mic key works instantly. It ends by itself after the idle time you pick.
final class SessionController: ObservableObject {
    static let shared = SessionController()

    @Published private(set) var active = false
    @Published private(set) var status: VoiceLink.Status = .offline
    @Published private(set) var detail = ""
    @Published private(set) var pairing: Pairing? = Pairing.load()
    @Published private(set) var lastText = ""
    @Published private(set) var error = ""
    @Published var idleMinutes: Int = UserDefaults.standard.object(forKey: "idleMinutes") as? Int ?? 15 {
        didSet { UserDefaults.standard.set(idleMinutes, forKey: "idleMinutes") }
    }

    private let link = VoiceLink()
    private let mic = Mic()
    private let gate = AudioGate()
    private var lastUse = Date()
    private var timer: Timer?
    private var cmdObserver: DarwinObserver?
    private var handled = Set<String>()
    private var partial = ""

    private init() {
        link.onStatus = { [weak self] s, d in
            self?.status = s
            self?.detail = d
            self?.writeSession()
        }
        link.onPartial = { [weak self] id, text in
            guard let self else { return }
            self.partial = text
            self.writeDictation(id, self.gate.current == id ? "recording" : "busy", partial: text)
        }
        link.onFinal = { [weak self] id, text in
            self?.lastText = text
            self?.writeDictation(id, "done", text: text)
        }
        link.onFailed = { [weak self] id, why in self?.writeDictation(id, "failed", error: why) }
        link.onRepaired = { [weak self] p in
            p.save()
            self?.pairing = p
        }
        link.configure(pairing)
        cmdObserver = DarwinObserver(Shared.cmdNote) { [weak self] in self?.handleCommand() }
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            self?.interrupted(n)
        }
        writeSession()
    }

    func pair(_ p: Pairing) {
        p.save()
        pairing = p
        link.configure(p)
        link.connect()
    }

    // ---------------------------------------------------------------- session
    func start() {
        lastUse = Date()
        error = ""
        if active { return }
        do {
            let s = AVAudioSession.sharedInstance()
            try s.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .allowBluetooth, .defaultToSpeaker])
            try s.setActive(true)
            mic.onPCM = { [weak self] data, _ in
                guard let self, let id = self.gate.feed(data) else { return }
                self.link.audio(id, data)
            }
            try mic.start()
        } catch {
            self.error = "Couldn't start the microphone: \(error.localizedDescription)"
            return
        }
        active = true
        link.connect()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.tick() }
        writeSession()
    }

    func stop() {
        guard active else { return }
        if let id = gate.current { link.cancel(id); gate.end() }
        mic.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        active = false
        timer?.invalidate()
        timer = nil
        link.disconnect()
        writeSession()
    }

    private func tick() {
        if idleMinutes > 0, gate.current == nil, Date().timeIntervalSince(lastUse) > Double(idleMinutes) * 60 {
            stop()
        } else {
            writeSession()
        }
    }

    private func interrupted(_ n: Notification) {
        guard active, let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              AVAudioSession.InterruptionType(rawValue: raw) == .ended else { return }
        try? AVAudioSession.sharedInstance().setActive(true)   // after a call: pick the mic back up
        if !mic.running { try? mic.start() }
    }

    // ---------------------------------------------------------------- keyboard <-> app
    private func handleCommand() {
        guard let c = Shared.read(Command.self, "command.json"), handled.insert("\(c.id)/\(c.cmd)").inserted else { return }
        lastUse = Date()
        switch c.cmd {
        case "start":
            guard active else { return writeDictation(c.id, "failed", error: "Voice session is off: tap the mic to open wisper keys") }
            if let old = gate.current { link.cancel(old) }
            partial = ""
            let preroll = gate.begin(c.id)
            link.start(c.id, before: c.before)
            if !preroll.isEmpty { link.audio(c.id, preroll) }
            writeDictation(c.id, "recording")
        case "stop":
            guard gate.current == c.id else { return }
            gate.end()
            link.stop(c.id)
            writeDictation(c.id, "busy", partial: partial)
        case "cancel":
            if gate.current == c.id { gate.end() }
            link.cancel(c.id)
            writeDictation(c.id, "cancelled")
        default: break
        }
    }

    private func writeSession() {
        Shared.write(SessionState(alive: active, heartbeat: Date().timeIntervalSince1970, pc: pairing?.pc ?? "",
                                  status: status.rawValue, detail: detail), "session.json")
        Shared.post(Shared.updateNote)
    }

    private func writeDictation(_ id: Int, _ state: String, partial: String = "", text: String = "", error: String = "") {
        Shared.write(DictationState(id: id, state: state, partial: partial, text: text, error: error), "dictation.json")
        Shared.post(Shared.updateNote)
    }
}
