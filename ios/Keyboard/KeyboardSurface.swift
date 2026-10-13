import UIKit

enum ShiftState { case off, on, lock }
enum MicState { case idle, recording, busy, offline }

protocol SurfaceDelegate: AnyObject {
    func keyFeedback(_ k: KKey)
    func typeChar(_ s: String)
    func backspace()
    func deleteWords(_ n: Int)
    func shiftTap()
    func space()
    func moveCursor(_ steps: Int)
    func enter()
    func switchMode(_ c: KCode)
    func micDown()
    func micUp()
    func globe(_ view: UIView, _ event: UIEvent?)
}

/// Draws the callouts (the enlarged key above your finger, long-press choices) on top of the strip, since the keys
/// view can't draw outside its own bounds. Never takes touches.
final class CalloutOverlay: UIView {
    struct Item { var rect: CGRect; var labels: [String]; var selected: Int? }
    var items: [Item] = [] { didSet { setNeedsDisplay() } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isOpaque = false
        backgroundColor = .clear
        contentMode = .redraw
    }
    required init?(coder: NSCoder) { fatalError() }

    override func draw(_ rect: CGRect) {
        for it in items {
            Theme.popup.setFill()
            UIBezierPath(roundedRect: it.rect, cornerRadius: 9).fill()
            let cell = it.rect.width / CGFloat(it.labels.count)
            for (i, l) in it.labels.enumerated() {
                let cr = CGRect(x: it.rect.minX + CGFloat(i) * cell, y: it.rect.minY, width: cell, height: it.rect.height)
                if it.selected == i {
                    Theme.accent.setFill()
                    UIBezierPath(roundedRect: cr.insetBy(dx: 2, dy: 2), cornerRadius: 7).fill()
                }
                let big = it.selected == nil
                drawCentered(l, in: cr, font: .systemFont(ofSize: big ? 30 : 22), color: it.selected == i ? .white : Theme.text)
            }
        }
    }
}

func drawCentered(_ s: String, in r: CGRect, font: UIFont, color: UIColor) {
    let a = NSAttributedString(string: s, attributes: [.font: font, .foregroundColor: color])
    let size = a.size()
    a.draw(at: CGPoint(x: r.midX - size.width / 2, y: r.midY - size.height / 2))
}

/// The keys. Multi-touch aware: a second finger landing while the first is still down types the first key at once
/// (rollover), so fast two-thumb typing never drops letters. Gaps between keys belong to the nearest key.
final class KeyboardSurface: UIView {
    weak var delegate: SurfaceDelegate?
    weak var overlay: CalloutOverlay?

    var layout = Layouts.letters(globe: false) { didSet { cancelAll(); placeKeys(); invalidateIntrinsicContentSize(); setNeedsDisplay() } }
    var shift = ShiftState.off { didSet { if shift != oldValue { setNeedsDisplay() } } }
    var mic = MicState.idle { didSet { if mic != oldValue { setNeedsDisplay() } } }
    var micEnabled = true { didSet { setNeedsDisplay() } }
    var returnLabel = "return" { didSet { setNeedsDisplay() } }
    var returnAccent = false { didSet { setNeedsDisplay() } }
    var rowHeight: CGFloat = 54 { didSet { invalidateIntrinsicContentSize(); setNeedsLayout() } }

    private struct Box { let key: KKey; let rect: CGRect; let hit: CGRect; let row: Int }
    private var boxes: [Box] = []
    private let gapH: CGFloat = 5, gapV: CGFloat = 9, side: CGFloat = 3, top: CGFloat = 3

    private enum Mode { case none, cursor, swipeDelete }
    private final class Ptr {
        var box: Box
        let downX: CGFloat
        var typed = false
        var popup: [String]?
        var popupSel = 0
        var popupRect = CGRect.zero
        var mode = Mode.none
        var anchorX: CGFloat
        var words = 0
        var longPress: DispatchWorkItem?
        var repeatTimer: Timer?
        init(_ box: Box, _ x: CGFloat) { self.box = box; downX = x; anchorX = x }
    }
    private var ptrs: [ObjectIdentifier: Ptr] = [:]
    private var order: [ObjectIdentifier] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        backgroundColor = Theme.bg
        contentMode = .redraw
    }
    required init?(coder: NSCoder) { fatalError() }

    var totalHeight: CGFloat { CGFloat(layout.rows.count) * rowHeight + 2 * top }
    override var intrinsicContentSize: CGSize { CGSize(width: UIView.noIntrinsicMetric, height: totalHeight) }

    /// Where the mic key is (the "open the app" link sits over it when no voice session is running).
    var micRect: CGRect? { boxes.first { $0.key.code == .mic }?.rect }

    override func layoutSubviews() {
        super.layoutSubviews()
        placeKeys()
        setNeedsDisplay()
    }

    private func placeKeys() {
        guard bounds.width > 0 else { return }
        let unit = (bounds.width - 2 * side) / 10
        var out: [Box] = []
        for (r, row) in layout.rows.enumerated() {
            let total = row.reduce(0) { $0 + $1.width }
            var x = side + (10 - total) * unit / 2
            let y = top + CGFloat(r) * rowHeight
            for (i, k) in row.enumerated() {
                let w = k.width * unit
                let rect = CGRect(x: x + gapH / 2, y: y + gapV / 2, width: w - gapH, height: rowHeight - gapV)
                let left = i == 0 ? 0 : x
                let right = i == row.count - 1 ? bounds.width : x + w
                out.append(Box(key: k, rect: rect, hit: CGRect(x: left, y: y, width: right - left, height: rowHeight), row: r))
                x += w
            }
        }
        boxes = out
    }

    private func box(at p: CGPoint) -> Box? {
        guard !boxes.isEmpty else { return nil }
        let r = min(max(Int((p.y - top) / rowHeight), 0), layout.rows.count - 1)
        let inRow = boxes.filter { $0.row == r }
        return inRow.first { p.x >= $0.hit.minX && p.x < $0.hit.maxX }
            ?? inRow.min { min(abs(p.x - $0.hit.minX), abs(p.x - $0.hit.maxX)) < min(abs(p.x - $1.hit.minX), abs(p.x - $1.hit.maxX)) }
    }

    // ---------------------------------------------------------------- touch
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { down(t, event) }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { move(t, event) }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { up(t, event) }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            if let p = ptrs.removeValue(forKey: ObjectIdentifier(t)) { cancelTimers(p) }
            order.removeAll { $0 == ObjectIdentifier(t) }
        }
        refresh()
    }

    private func down(_ t: UITouch, _ event: UIEvent?) {
        let pt = t.location(in: self)
        guard let b = box(at: pt) else { return }
        for id in order {                                  // rollover: the earlier key is typed now
            if let p = ptrs[id], p.box.key.code == .char, !p.typed, p.popup == nil {
                p.typed = true
                cancelTimers(p)
                delegate?.typeChar(p.box.key.label)
            }
        }
        let p = Ptr(b, pt.x)
        let id = ObjectIdentifier(t)
        ptrs[id] = p
        order.append(id)
        let k = b.key
        if k.code == .mic && !micEnabled { p.typed = true; return refresh() }
        if k.code != .globe { delegate?.keyFeedback(k) }
        switch k.code {
        case .char: if !k.alts.isEmpty { armLongPress(p) }
        case .backspace:
            delegate?.backspace()
            var n = 0
            p.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.42, repeats: false) { [weak self] _ in
                p.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.06, repeats: true) { timer in
                    self?.delegate?.backspace()
                    n += 1
                    if n == 12 {   // speed up
                        timer.invalidate()
                        p.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.03, repeats: true) { _ in self?.delegate?.backspace() }
                    }
                }
            }
        case .shift: delegate?.shiftTap()
        case .symbols, .symbols2, .letters: delegate?.switchMode(k.code)
        case .mic: delegate?.micDown()
        case .globe: delegate?.globe(self, event)
        default: break
        }
        refresh()
    }

    private func armLongPress(_ p: Ptr) {
        p.longPress?.cancel()
        let w = DispatchWorkItem { [weak self] in self?.longPress(p) }
        p.longPress = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.33, execute: w)
    }

    private func longPress(_ p: Ptr) {
        guard ptrs.values.contains(where: { $0 === p }), !p.typed else { return }
        let k = p.box.key
        guard !k.alts.isEmpty else { return }
        let alts = layout.letters && shift != .off ? k.alts.map { $0.uppercased() } : k.alts
        let cell = min(p.box.rect.width + gapH, (bounds.width - 2 * side) / CGFloat(alts.count))
        let w = cell * CGFloat(alts.count)
        let left = min(max(p.box.rect.midX - cell / 2, side), bounds.width - side - w)
        p.popup = alts
        p.popupSel = 0
        p.popupRect = CGRect(x: left, y: p.box.rect.minY - rowHeight - 2, width: w, height: rowHeight - gapV / 2)
        delegate?.keyFeedback(k)
        refresh()
    }

    private func move(_ t: UITouch, _ event: UIEvent?) {
        guard let p = ptrs[ObjectIdentifier(t)] else { return }
        let pt = t.location(in: self)
        switch p.box.key.code {
        case .char:
            if let popup = p.popup {
                let cell = p.popupRect.width / CGFloat(popup.count)
                let sel = min(max(Int((pt.x - p.popupRect.minX) / cell), 0), popup.count - 1)
                if sel != p.popupSel { p.popupSel = sel; refresh() }
            } else if !p.typed, let b = box(at: pt), b.key.code == .char, b.rect != p.box.rect {
                p.box = b                                  // slid onto a neighbouring letter: that one counts
                if b.key.alts.isEmpty { cancelTimers(p) } else { armLongPress(p) }
                refresh()
            }
        case .space:
            if p.mode == .cursor || (!p.typed && abs(pt.x - p.downX) > 12) {
                if p.mode != .cursor { p.mode = .cursor; cancelTimers(p); p.anchorX = pt.x }
                let n = Int((pt.x - p.anchorX) / 9)
                if n != 0 { delegate?.moveCursor(n); p.anchorX += CGFloat(n) * 9 }
            }
        case .backspace:
            if p.mode == .swipeDelete || p.downX - pt.x > 28 {
                if p.mode != .swipeDelete { p.mode = .swipeDelete; cancelTimers(p) }
                let dx = p.downX - pt.x - 28
                let words = dx < 0 ? 0 : 1 + Int(dx / 24)
                if words != p.words { p.words = words; refresh() }
            }
        case .globe: delegate?.globe(self, event)
        default: break
        }
    }

    private func up(_ t: UITouch, _ event: UIEvent?) {
        let id = ObjectIdentifier(t)
        order.removeAll { $0 == id }
        guard let p = ptrs.removeValue(forKey: id) else { return }
        cancelTimers(p)
        let k = p.box.key
        switch k.code {
        case .char:
            if p.popup != nil { delegate?.typeChar(k.alts[p.popupSel]) } else if !p.typed { delegate?.typeChar(k.label) }
        case .space: if p.mode == .none && !p.typed { delegate?.space() }
        case .backspace: if p.mode == .swipeDelete && p.words > 0 { delegate?.deleteWords(p.words) }
        case .enter: delegate?.enter()
        case .mic: if !p.typed { delegate?.micUp() }
        case .globe: delegate?.globe(self, event)
        default: break
        }
        refresh()
    }

    private func cancelTimers(_ p: Ptr) {
        p.longPress?.cancel()
        p.longPress = nil
        p.repeatTimer?.invalidate()
        p.repeatTimer = nil
    }

    func cancelAll() {
        ptrs.values.forEach(cancelTimers)
        ptrs.removeAll()
        order.removeAll()
        refresh()
    }

    private func refresh() {
        setNeedsDisplay()
        guard let overlay else { return }
        var items: [CalloutOverlay.Item] = []
        for p in ptrs.values {
            if let popup = p.popup {
                items.append(.init(rect: clampUp(convert(p.popupRect, to: overlay)), labels: popup, selected: p.popupSel))
            } else if p.box.key.code == .char, !p.typed {
                let w = p.box.rect.width + 10, h = rowHeight * 1.05
                let left = min(max(p.box.rect.midX - w / 2, 0), bounds.width - w)
                let r = CGRect(x: left, y: p.box.rect.minY - h - 4, width: w, height: h)
                items.append(.init(rect: clampUp(convert(r, to: overlay)), labels: [keyText(p.box.key)], selected: nil))
            } else if p.box.key.code == .backspace, p.mode == .swipeDelete {
                let r = CGRect(x: p.box.rect.maxX - 110, y: p.box.rect.minY - rowHeight - 4, width: 110, height: rowHeight * 0.8)
                items.append(.init(rect: clampUp(convert(r, to: overlay)), labels: [p.words == 0 ? "cancel" : "−\(p.words) word\(p.words == 1 ? "" : "s")"], selected: nil))
            }
        }
        overlay.items = items
    }

    private func clampUp(_ r: CGRect) -> CGRect { r.minY < 0 ? r.offsetBy(dx: 0, dy: -r.minY) : r }

    // ---------------------------------------------------------------- drawing
    private func keyText(_ k: KKey) -> String {
        layout.letters && shift != .off && k.label.count == 1 ? k.label.uppercased() : k.label
    }

    override func draw(_ rect: CGRect) {
        let pressed = Set(ptrs.values.map { $0.box.rect.origin.x * 10_000 + $0.box.rect.origin.y })
        for b in boxes { drawKey(b, down: pressed.contains(b.rect.origin.x * 10_000 + b.rect.origin.y)) }
    }

    private func drawKey(_ b: Box, down: Bool) {
        let k = b.key
        let bg: UIColor
        switch k.code {
        case .enter: bg = returnAccent ? (down ? Theme.accentDown : Theme.accent) : (down ? Theme.modDown : Theme.mod)
        case .mic where mic == .recording: bg = Theme.rec
        case .char, .space: bg = down ? Theme.keyDown : Theme.key
        case .shift where shift != .off: bg = Theme.keyDown
        default: bg = down ? Theme.modDown : Theme.mod
        }
        bg.setFill()
        UIBezierPath(roundedRect: b.rect, cornerRadius: 7).fill()
        switch k.code {
        case .char:
            let t = keyText(k)
            let size: CGFloat = t.count > 1 ? 18 : (layout.letters ? 23 : 21)
            drawCentered(t, in: b.rect, font: .systemFont(ofSize: size), color: Theme.text)
            if layout.letters, let h = k.alts.first {
                drawCentered(h, in: CGRect(x: b.rect.maxX - 14, y: b.rect.minY + 3, width: 12, height: 14),
                             font: .systemFont(ofSize: 10.5), color: Theme.textDim)
            }
        case .symbols, .symbols2, .letters:
            drawCentered(k.label, in: b.rect, font: .systemFont(ofSize: 15, weight: .semibold), color: Theme.text)
        case .enter:
            drawCentered(returnLabel, in: b.rect, font: .systemFont(ofSize: 15, weight: .medium), color: returnAccent ? .white : Theme.text)
        case .space: break
        case .shift:
            symbol(shift == .off ? "shift" : (shift == .on ? "shift.fill" : "capslock.fill"), in: b.rect,
                   color: shift == .off ? Theme.text : Theme.accentLight)
        case .backspace: symbol("delete.left", in: b.rect, color: Theme.text)
        case .globe: symbol("globe", in: b.rect, color: Theme.text)
        case .mic:
            let color: UIColor
            if !micEnabled { color = Theme.textFaint }
            else {
                switch mic {
                case .recording: color = .white
                case .busy, .offline: color = Theme.textDim
                case .idle: color = Theme.accentLight
                }
            }
            symbol("mic.fill", in: b.rect, color: color)
        }
    }

    private func symbol(_ name: String, in r: CGRect, color: UIColor) {
        guard let img = UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: 19, weight: .regular))?
            .withTintColor(color, renderingMode: .alwaysOriginal) else { return }
        img.draw(at: CGPoint(x: r.midX - img.size.width / 2, y: r.midY - img.size.height / 2))
    }
}
