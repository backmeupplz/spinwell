import Foundation

struct SplitMix: RandomNumberGenerator {
    var s: UInt64
    init(seed: UInt64) { s = seed }
    mutating func next() -> UInt64 {
        s &+= 0x9E3779B97F4A7C15
        var z = s
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}

protocol Shufflable { var artistKey: String { get }; var albumKey: String { get } }
extension Song: Shufflable {
    var artistKey: String { artistId ?? artist ?? "" }
    var albumKey: String { albumId ?? album ?? "" }
}

/// Random permutation, spread so no two neighbours share an artist (then, where it is free, an album) whenever
/// the input allows it. `first` (an index into `songs`) lands at position 0. Same seed, same order.
func smartShuffle<T: Shufflable>(_ songs: [T], first: Int? = nil, seed: UInt64) -> [T] {
    guard songs.count > 1 else { return songs }
    var rng = SplitMix(seed: seed)
    var pool = Array(songs.indices).shuffled(using: &rng)
    var out: [Int] = []
    var counts: [String: Int] = [:]
    for s in songs { counts[s.artistKey, default: 0] += 1 }

    func take(_ i: Int) {
        out.append(i)
        pool.remove(at: pool.firstIndex(of: i)!)
        counts[songs[i].artistKey]! -= 1
    }
    // Feasible to finish with `last` as the final artist placed: nobody needs more than ceil(R/2) slots,
    // and `last` no more than floor(R/2).
    func feasible(_ last: String) -> Bool {
        let r = pool.count - 1 // the candidate under test is still in the pool
        for (a, c) in counts where c > 0 {
            if c > (r + 1) / 2 || (a == last && c > r / 2) { return false }
        }
        return true
    }
    if let f = first, songs.indices.contains(f) { take(f) } else { take(pool[0]) }
    while !pool.isEmpty {
        let last = songs[out.last!]
        var pick: Int?
        var fallbackArtist: Int?
        for i in pool where songs[i].artistKey != last.artistKey {
            counts[songs[i].artistKey]! -= 1
            let ok = feasible(songs[i].artistKey)
            counts[songs[i].artistKey]! += 1
            guard ok else { continue }
            if songs[i].albumKey != last.albumKey { pick = i; break }
            if fallbackArtist == nil { fallbackArtist = i }
        }
        if let i = pick ?? fallbackArtist { take(i); continue }
        // Infeasible (one artist dominates): spread the dominant artist as far as it goes.
        let candidates = pool.filter { songs[$0].artistKey != last.artistKey }
        take(candidates.max { counts[songs[$0].artistKey]! < counts[songs[$1].artistKey]! } ?? pool[0])
    }
    return out.map { songs[$0] }
}

enum Repeat: String, Codable { case off, all, one
    var next: Repeat { switch self { case .off: .all; case .all: .one; case .one: .off } }
}

struct QItem: Codable, Hashable, Identifiable {
    var id = UUID()
    var song: Song
}
extension QItem: Shufflable {
    var artistKey: String { song.artistKey }
    var albumKey: String { song.albumKey }
}

/// The app-owned queue, shared by the player and Up next. Pure value type so it is trivial to test and persist.
struct QueueState: Codable {
    var items: [QItem] = []
    var original: [QItem] = []
    var index = 0
    var added: Set<UUID> = []
    var context: Ref?
    var shuffle = false
    var repeatMode: Repeat = .off

    var current: QItem? { items.indices.contains(index) ? items[index] : nil }
    var isEmpty: Bool { items.isEmpty }
    var hasNext: Bool { index + 1 < items.count || repeatMode != .off && !items.isEmpty }

    /// Replace the queue with `songs` (a context), starting at `at`. `shuffle` also picks the order.
    mutating func start(_ songs: [Song], at: Int = 0, context: Ref?, shuffle: Bool, seed: UInt64 = .random(in: 0...UInt64.max)) {
        let list = songs.map { QItem(song: $0) }
        original = list
        added = []
        self.context = context
        self.shuffle = shuffle
        if shuffle {
            let start = songs.indices.contains(at) ? at : 0
            items = smartShuffle(list, first: start, seed: seed)
        } else { items = list }
        index = shuffle ? 0 : min(max(at, 0), max(items.count - 1, 0))
    }

    /// Shuffle button: random start song.
    mutating func startShuffled(_ songs: [Song], context: Ref?, seed: UInt64 = .random(in: 0...UInt64.max)) {
        var r = SplitMix(seed: seed ^ 0xABCDEF)
        start(songs, at: songs.isEmpty ? 0 : Int.random(in: 0..<songs.count, using: &r), context: context, shuffle: true, seed: seed)
    }

    /// Entries added with Play next / Add to queue that follow the current one (contiguous).
    var addedRun: Int {
        var n = 0
        while index + 1 + n < items.count, added.contains(items[index + 1 + n].id) { n += 1 }
        return n
    }
    var nextInQueue: ArraySlice<QItem> { items[min(index + 1, items.count)..<min(index + 1 + addedRun, items.count)] }
    var nextFromContext: ArraySlice<QItem> { items[min(index + 1 + addedRun, items.count)...] }

    mutating func playNext(_ songs: [Song]) { insert(songs, at: index + 1) }
    mutating func addToQueue(_ songs: [Song]) { insert(songs, at: index + 1 + addedRun) }

    private mutating func insert(_ songs: [Song], at i: Int) {
        let new = songs.map { QItem(song: $0) }
        if items.isEmpty { items = new; index = 0 } else { items.insert(contentsOf: new, at: min(i, items.count)) }
        added.formUnion(new.map(\.id))
    }

    mutating func remove(_ id: UUID) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        items.remove(at: i)
        original.removeAll { $0.id == id }
        added.remove(id)
        if i < index { index -= 1 }
        index = max(0, min(index, items.count - 1))
    }

    /// Drop everything "Next in queue".
    mutating func clearAdded() {
        let run = Set(nextInQueue.map(\.id))
        items.removeAll { run.contains($0.id) }
        added.subtract(run)
    }

    /// Reorder inside `items` (offsets are absolute indices into `items`).
    mutating func move(from: IndexSet, to: Int) {
        let cur = current?.id
        items.move(fromOffsets: from, toOffset: to)
        if let cur, let i = items.firstIndex(where: { $0.id == cur }) { index = i }
    }

    mutating func jump(to id: UUID) { if let i = items.firstIndex(where: { $0.id == id }) { index = i } }

    mutating func setShuffle(_ on: Bool, seed: UInt64 = .random(in: 0...UInt64.max)) {
        guard on != shuffle else { return }
        shuffle = on
        guard let cur = current else { return }
        let after = Array(items[(index + 1)...])
        let run = after.filter { added.contains($0.id) }
        if on {
            let rest = after.filter { !added.contains($0.id) }
            let tail = smartShuffle([cur] + rest, first: 0, seed: seed).dropFirst()
            items = Array(items[..<index]) + [cur] + run + tail
        } else {
            if let i = original.firstIndex(where: { $0.id == cur.id }) {
                items = Array(original[...i]) + run + Array(original[(i + 1)...])
                index = i
            } else {
                items = [cur] + run + original
                index = 0
            }
        }
    }

    /// Advance after a song ended (`auto`) or the user tapped next. Returns false at the end.
    mutating func advance(auto: Bool) -> Bool {
        if auto, repeatMode == .one { return !items.isEmpty }
        if index + 1 < items.count { index += 1; return true }
        if repeatMode == .all, !items.isEmpty { index = 0; return true }
        return false
    }

    mutating func back() {
        if index > 0 { index -= 1 } else if repeatMode == .all, !items.isEmpty { index = items.count - 1 }
    }

    /// What plays after the current song (for preloading), nil at the end.
    var upcoming: QItem? {
        if repeatMode == .one { return current }
        if index + 1 < items.count { return items[index + 1] }
        return repeatMode == .all ? items.first : nil
    }
}
