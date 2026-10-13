import CryptoKit
import Foundation
import Network

/// The PC this phone is paired with (from the QR code that wisper's tools/pair.py shows).
struct Pairing: Codable, Equatable {
    var urls: [String]
    var token: String
    var pc: String
    var rendezvous: String? = nil        // where the PC publishes its away-from-home address (GitHub gist API)

    /// wisperkeys://pair?u=ws://192.168.0.10:47822&u=ws://100.x.y.z:47822&t=TOKEN&n=PC-NAME
    static func parse(_ url: URL) -> Pairing? {
        guard url.scheme == "wisperkeys", url.host == "pair",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return nil }
        let urls = items.filter { $0.name == "u" }.compactMap { $0.value }.filter { $0.hasPrefix("ws://") || $0.hasPrefix("wss://") }
        guard let token = items.first(where: { $0.name == "t" })?.value, !token.isEmpty, !urls.isEmpty else { return nil }
        let r = items.first(where: { $0.name == "r" })?.value
        return Pairing(urls: urls, token: token, pc: items.first(where: { $0.name == "n" })?.value ?? "",
                       rendezvous: r?.hasPrefix("https://") == true ? r : nil)
    }

    static func load() -> Pairing? {
        guard let d = UserDefaults.standard.data(forKey: "pairing") else { return nil }
        return try? JSONDecoder().decode(Pairing.self, from: d)
    }

    func save() {
        UserDefaults.standard.set(try? JSONEncoder().encode(self), forKey: "pairing")
    }
}

/// The link to wisper on the PC (protocol: wisper/service/remote.py). Same behaviour as the Android keyboard:
/// every paired address is tried at once and the first to answer wins; the connection stays open for the whole
/// session so the mic key costs no handshake; the dictation in flight is replayed if the link drops.
final class VoiceLink {
    enum Status: String { case notPaired, connecting, ready, offline }

    var onStatus: ((Status, String) -> Void)?
    var onPartial: ((Int, String) -> Void)?
    var onFinal: ((Int, String) -> Void)?
    var onFailed: ((Int, String) -> Void)?
    /** The PC was found at a new address (restart, new Wi-Fi address): save the updated pairing. */
    var onRepaired: ((Pairing) -> Void)?

    private struct Dictation {
        let id: Int
        let before: String
        var audio = Data()
        var stopped = false
    }

    private let q = DispatchQueue(label: "wisper.link")
    private var pairing: Pairing?
    private var live: NWConnection?
    private var attempts: [NWConnection] = []
    private var dict: Dictation?
    private var wanted = false
    private var retryDelay = 1.0
    private var pingTimer: DispatchSourceTimer?
    private var browser: NWBrowser?
    private var lastFind = Date.distantPast
    private var lastLookup = Date.distantPast
    private(set) var status: Status = .offline

    private let pathMonitor = NWPathMonitor()
    private var lastPath = ""

    init() {
        pathMonitor.pathUpdateHandler = { [weak self] path in self?.pathChanged(path) }
        pathMonitor.start(queue: q)
    }

    /// Wi-Fi <-> mobile data: reconnect at once so the direct Wi-Fi path wins at home and the tunnel takes over
    /// when you leave, instead of waiting for the old link to time out. Never during a dictation.
    private func pathChanged(_ path: NWPath) {
        let key = path.availableInterfaces.map { $0.name }.joined(separator: ",")
        defer { lastPath = key }
        guard !lastPath.isEmpty, key != lastPath, path.status == .satisfied, wanted, dict == nil else { return }
        live?.cancel()
        attempts.forEach { $0.cancel() }
        live = nil
        attempts = []
        pingTimer?.cancel()
        pingTimer = nil
        retryDelay = 1
        connectLocked()
    }

    func configure(_ p: Pairing?) {
        q.async {
            guard p != self.pairing else { return }
            self.pairing = p
            self.dropAll()
            if self.wanted { self.connectLocked() }
        }
    }

    func connect() {
        q.async {
            self.wanted = true
            self.connectLocked()
        }
    }

    func disconnect() {
        q.async {
            self.wanted = false
            self.dropAll()
        }
    }

    private func setStatus(_ s: Status, _ detail: String) {
        status = s
        DispatchQueue.main.async { self.onStatus?(s, detail) }
    }

    private func connectLocked() {
        guard live == nil, attempts.isEmpty else { return }
        guard let p = pairing else { return setStatus(.notPaired, "") }
        setStatus(.connecting, "")
        for u in p.urls {
            var base = u
            while base.hasSuffix("/") { base.removeLast() }
            guard let url = URL(string: base + "/v1/stream") else { continue }
            let ws = NWProtocolWebSocket.Options()
            ws.autoReplyPing = true
            ws.setAdditionalHeaders([("Authorization", "Bearer \(p.token)")])
            let tcp = NWProtocolTCP.Options()
            tcp.noDelay = true                       // small messages (stop, the final text) go out at once
            tcp.connectionTimeout = 4
            let params = url.scheme == "wss" ? NWParameters(tls: NWProtocolTLS.Options(), tcp: tcp) : NWParameters(tls: nil, tcp: tcp)
            params.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
            let c = NWConnection(to: .url(url), using: params)
            attempts.append(c)
            c.stateUpdateHandler = { [weak self, unowned c] state in
                switch state {
                case .failed, .cancelled: self?.lost(c, "PC unreachable")
                case .waiting: c.cancel()            // no route right now: fail fast, retry later
                default: break
                }
            }
            receive(c)
            c.start(queue: q)
        }
        if attempts.isEmpty { setStatus(.offline, "bad address") }
    }

    private func dropAll() {
        live?.cancel()
        attempts.forEach { $0.cancel() }
        live = nil
        attempts = []
        pingTimer?.cancel()
        pingTimer = nil
        setStatus(pairing == nil ? .notPaired : .offline, "")
    }

    private func lost(_ c: NWConnection, _ reason: String) {
        if c === live {
            live = nil
            pingTimer?.cancel()
            pingTimer = nil
        } else if let i = attempts.firstIndex(where: { $0 === c }) {
            attempts.remove(at: i)
        } else { return }
        guard live == nil, attempts.isEmpty else { return }
        setStatus(.offline, reason)
        if wanted || dict != nil {
            findLocked()
            let delay = retryDelay
            retryDelay = min(retryDelay * 2, 15)
            q.asyncAfter(deadline: .now() + delay) { [weak self] in
                guard let self, self.wanted || self.dict != nil else { return }
                self.connectLocked()
            }
        }
    }

    // ---------------------------------------------------------------- finding the PC again
    /// Every saved address failed. Look for the PC two ways at once: on this Wi-Fi (wisper announces _wisper._tcp
    /// with a fingerprint of the token; at most every 20 s) and, wherever you are, at the address it last published
    /// (the rendezvous gist; at most once a minute). Pairing stays a one-time thing.
    private func findLocked() {
        lookupLocked()
        guard let p = pairing, browser == nil, Date().timeIntervalSince(lastFind) > 20 else { return }
        lastFind = Date()
        let want = Self.fingerprint(p.token)
        let b = NWBrowser(for: .bonjourWithTXTRecord(type: "_wisper._tcp", domain: nil), using: NWParameters())
        browser = b
        b.browseResultsChangedHandler = { [weak self] results, _ in
            for r in results {
                if case let .bonjour(txt) = r.metadata, txt["id"] == want {
                    self?.resolve(r.endpoint)
                    return
                }
            }
        }
        b.start(queue: q)
        q.asyncAfter(deadline: .now() + 6) { [weak self] in
            self?.browser?.cancel()
            self?.browser = nil
        }
    }

    /// A Bonjour result is a name; connect once over plain TCP to learn the IP and port behind it.
    private func resolve(_ endpoint: NWEndpoint) {
        let c = NWConnection(to: endpoint, using: .tcp)
        c.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                if case let .hostPort(host, port)? = c.currentPath?.remoteEndpoint {
                    let h = "\(host)"
                    if !h.contains(":") { self?.found("ws://\(h):\(port)") }
                }
                c.cancel()
            case .failed, .waiting: c.cancel()
            default: break
            }
        }
        c.start(queue: q)
    }

    private func lookupLocked() {
        guard let p = pairing, let r = p.rendezvous, let url = URL(string: r),
              Date().timeIntervalSince(lastLookup) > 60 else { return }
        lastLookup = Date()
        let want = Self.fingerprint(p.token)
        var req = URLRequest(url: url, timeoutInterval: 8)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        URLSession.shared.dataTask(with: req) { [weak self] data, _, _ in
            guard let data, let gist = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let file = (gist["files"] as? [String: Any])?["wisper.json"] as? [String: Any],
                  let content = (file["content"] as? String)?.data(using: .utf8),
                  let c = (try? JSONSerialization.jsonObject(with: content)) as? [String: Any],
                  c["id"] as? String == want, let urls = c["urls"] as? [String] else { return }
            self?.q.async { self?.adopt(urls.filter { $0.hasPrefix("ws") }) }
        }.resume()
    }

    private func found(_ url: String) {
        browser?.cancel()
        browser = nil
        adopt([url])
    }

    /// New addresses for the paired PC: put them first, drop stale tunnel addresses, save, reconnect.
    private func adopt(_ found: [String]) {
        guard var p = pairing, !found.isEmpty else { return }
        let tunnels = found.contains { $0.hasPrefix("wss://") }
        var merged: [String] = []
        for u in found + p.urls.filter({ !(tunnels && $0.hasPrefix("wss://")) }) where !merged.contains(u) { merged.append(u) }
        merged = Array(merged.prefix(5))
        if merged != p.urls {
            p.urls = merged
            pairing = p
            DispatchQueue.main.async { self.onRepaired?(p) }
        }
        retryDelay = 1
        if live == nil && attempts.isEmpty { connectLocked() }
    }

    static func fingerprint(_ token: String) -> String {
        String(SHA256.hash(data: Data(token.utf8)).map { String(format: "%02x", $0) }.joined().prefix(12))
    }

    private func receive(_ c: NWConnection) {
        c.receiveMessage { [weak self] data, context, _, error in
            guard let self else { return }
            if error != nil { return }   // the state handler deals with it
            if let data, let meta = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata,
               meta.opcode == .text, let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                self.handle(c, obj)
            }
            if c.state == .ready { self.receive(c) }
        }
    }

    private func handle(_ c: NWConnection, _ m: [String: Any]) {
        switch m["type"] as? String {
        case "ready":
            guard live == nil else { if c !== live { c.cancel() }; return }
            live = c
            attempts.removeAll { $0 === c }
            attempts.forEach { $0.cancel() }
            attempts = []
            retryDelay = 1
            setStatus(.ready, "")
            replay(c)
            startPings()
        case "pong":
            if let t = (m["t"] as? NSNumber)?.uint64Value {
                let ms = Int((DispatchTime.now().uptimeNanoseconds &- t) / 1_000_000)
                setStatus(.ready, "\(ms) ms")
            }
        case "partial":
            let id = (m["id"] as? NSNumber)?.intValue ?? -1, text = m["text"] as? String ?? ""
            DispatchQueue.main.async { self.onPartial?(id, text) }
        case "final":
            let id = (m["id"] as? NSNumber)?.intValue ?? -1, text = m["text"] as? String ?? ""
            if dict?.id == id { dict = nil }
            DispatchQueue.main.async { self.onFinal?(id, text) }
        default: break
        }
    }

    private func startPings() {
        pingTimer?.cancel()
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now(), repeating: 10)
        t.setEventHandler { [weak self] in
            guard let self, let c = self.live else { return }
            self.sendText(c, ["type": "ping", "t": NSNumber(value: DispatchTime.now().uptimeNanoseconds)])
        }
        t.resume()
        pingTimer = t
    }

    private func sendText(_ c: NWConnection, _ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj) else { return }
        send(c, data, .text)
    }

    private func send(_ c: NWConnection, _ data: Data, _ op: NWProtocolWebSocket.Opcode) {
        let ctx = NWConnection.ContentContext(identifier: "m", metadata: [NWProtocolWebSocket.Metadata(opcode: op)])
        c.send(content: data, contentContext: ctx, isComplete: true, completion: .contentProcessed { _ in })
    }

    // ---------------------------------------------------------------- dictation
    func start(_ id: Int, before: String) {
        q.async {
            self.dict = Dictation(id: id, before: before)
            if let c = self.live { self.sendText(c, ["type": "start", "id": id, "before": before]) } else { self.connectLocked() }
        }
    }

    func audio(_ id: Int, _ data: Data) {
        q.async {
            guard var d = self.dict, d.id == id, !d.stopped, d.audio.count < 20_000_000 else { return }
            d.audio.append(data)
            self.dict = d
            if let c = self.live { self.send(c, data, .binary) }
        }
    }

    func stop(_ id: Int) {
        q.async {
            guard var d = self.dict, d.id == id else { return }
            d.stopped = true
            self.dict = d
            if let c = self.live { self.sendText(c, ["type": "stop", "id": id]) } else { self.connectLocked() }
            self.q.asyncAfter(deadline: .now() + 15) {
                guard self.dict?.id == id else { return }
                self.dict = nil
                let why = self.live == nil ? "Couldn't reach your PC" : "Your PC didn't answer"
                DispatchQueue.main.async { self.onFailed?(id, why) }
            }
        }
    }

    func cancel(_ id: Int) {
        q.async {
            guard self.dict?.id == id else { return }
            if let c = self.live { self.sendText(c, ["type": "cancel", "id": id]) }
            self.dict = nil
        }
    }

    /// A fresh connection picks up the dictation in flight: start + everything said so far (+ stop if released).
    private func replay(_ c: NWConnection) {
        guard let d = dict else { return }
        sendText(c, ["type": "start", "id": d.id, "before": d.before])
        var i = 0
        while i < d.audio.count {
            let n = min(32_000, d.audio.count - i)
            send(c, d.audio.subdata(in: i..<(i + n)), .binary)
            i += n
        }
        if d.stopped { sendText(c, ["type": "stop", "id": d.id]) }
    }
}
