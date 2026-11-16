import SwiftUI
import UIKit

/// Cover art: NSCache over a disk cache, ids carry a content hash so entries live forever.
@MainActor enum Covers {
    private static let cache: NSCache<NSString, UIImage> = { let c = NSCache<NSString, UIImage>(); c.totalCostLimit = 96 << 20; return c }()
    private static var loading: [String: Task<UIImage?, Never>] = [:]
    private static var colors: [String: UIColor] = [:]
    private static var dir: URL { URL.cachesDirectory.appending(path: "covers/" + app.scope) }

    /// 150 / 300 / 600 / 1200: the size for `points` wide on a 3x screen.
    static func bucket(_ points: CGFloat) -> Int {
        let px = points * 3
        return [150, 300, 600, 1200].first { CGFloat($0) >= px } ?? 1200
    }

    private static func key(_ id: String, _ size: Int) -> String { "\(id)@\(size)" }
    static func mem(_ id: String, _ size: Int) -> UIImage? { cache.object(forKey: key(id, size) as NSString) }
    static func clearMemory() { cache.removeAllObjects(); loading = [:]; colors = [:] }
    static func clearAll() { clearMemory(); try? FileManager.default.removeItem(at: URL.cachesDirectory.appending(path: "covers")) }

    private static func file(_ id: String, _ size: Int) -> URL {
        dir.appending(path: (id.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "x") + "_\(size)")
    }

    /// Whatever is cached for this cover, any size, biggest first: instant stand-in while the right size loads.
    static func any(_ id: String) -> UIImage? { [1200, 600, 300, 150].lazy.compactMap { mem(id, $0) }.first }

    static func get(_ id: String, size: Int) async -> UIImage? {
        guard !id.isEmpty else { return nil }
        let k = key(id, size)
        if let i = cache.object(forKey: k as NSString) { return i }
        if let t = loading[k] { return await t.value }
        let f = file(id, size)
        let url = app.offline ? nil : app.cover(id, size: size)
        let session = app.session
        let t = Task { () -> UIImage? in
            let img: UIImage? = await Task.detached(priority: .userInitiated) {
                var data = try? Data(contentsOf: f)
                if data == nil, let url {
                    data = try? await session.data(from: url).0
                    if let d = data, UIImage(data: d) != nil {
                        try? FileManager.default.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
                        try? d.write(to: f)
                    } else { data = nil }
                }
                guard let data else { return nil }
                return await UIImage(data: data)?.byPreparingForDisplay()
            }.value
            if let img {
                cache.setObject(img, forKey: k as NSString, cost: Int(img.size.width * img.size.height * 4))
            }
            loading[k] = nil
            return img
        }
        loading[k] = t
        return await t.value
    }

    /// Fetch the sizes the player needs offline.
    static func prefetch(_ id: String) async { for s in [600, 1200] { _ = await get(id, size: s) } }

    // MARK: dominant color: 16x16 downsample, skipping near-black/white pixels, weighted by saturation

    static func color(_ id: String) -> UIColor? {
        if let c = colors[id] { return c }
        guard let img = any(id) else { return nil }
        let c = dominant(img)
        colors[id] = c
        return c
    }

    static func dominant(_ img: UIImage) -> UIColor {
        guard let cg = img.cgImage else { return .darkGray }
        var px = [UInt8](repeating: 0, count: 16 * 16 * 4)
        let ctx = CGContext(data: &px, width: 16, height: 16, bitsPerComponent: 8, bytesPerRow: 64, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        ctx?.interpolationQuality = .medium
        ctx?.draw(cg, in: CGRect(x: 0, y: 0, width: 16, height: 16))
        var r = 0.0, g = 0.0, b = 0.0, w = 0.0
        var ar = 0.0, ag = 0.0, ab = 0.0
        for i in 0..<256 {
            let pr = Double(px[i * 4]) / 255, pg = Double(px[i * 4 + 1]) / 255, pb = Double(px[i * 4 + 2]) / 255
            ar += pr; ag += pg; ab += pb
            let mx = max(pr, pg, pb), mn = min(pr, pg, pb)
            if mx < 0.12 || mn > 0.92 { continue }
            let wt = 0.15 + (mx - mn) * (mx - mn) * 4
            r += pr * wt; g += pg * wt; b += pb * wt; w += wt
        }
        if w == 0 { return UIColor(red: ar / 256, green: ag / 256, blue: ab / 256, alpha: 1) }
        return UIColor(red: r / w, green: g / w, blue: b / w, alpha: 1)
    }
}

/// Rounded cover with a fixed aspect box, so cells never jump when the image arrives.
struct Cover: View {
    let id: String?
    var size: CGFloat = 150
    var radius: CGFloat = 10
    var symbol = "music.note"
    @State private var img: UIImage?
    @State private var shown = false

    var body: some View {
        let px = Covers.bucket(size)
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .background(Rectangle().fill(.quaternary.opacity(0.5)))
            .overlay {
                if let img {
                    Image(uiImage: img).resizable().scaledToFill().transition(.opacity)
                } else {
                    Image(systemName: symbol).font(.system(size: max(14, size / 4))).foregroundStyle(.tertiary)
                }
            }
            .clipShape(.rect(cornerRadius: radius))
            .task(id: (id ?? "") + "@\(px)") {
                guard let id, !id.isEmpty else { img = nil; return }
                if let m = Covers.mem(id, px) { img = m; return }
                img = Covers.any(id)
                let got = await Covers.get(id, size: px)
                guard !Task.isCancelled, let got else { return }
                withAnimation(.easeOut(duration: 0.2)) { img = got }
            }
    }
}

extension View {
    func glassCard(radius: CGFloat = 20) -> some View { glassEffect(.regular, in: .rect(cornerRadius: radius)) }
}
