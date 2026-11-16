import Network
import XCTest
@testable import Spinwell

/// Loopback HTTP server serving `body` with optional Range support; `dropFirstAfter` cuts the first response short.
final class RangeServer: @unchecked Sendable {
    let body = Data((0..<100_000).map { UInt8($0 % 251) })
    var honorRange = true
    var dropFirstAfter: Int?
    private(set) var ranges: [String?] = []
    private let listener: NWListener
    private let q = DispatchQueue(label: "range-server")
    private(set) var port: UInt16 = 0

    init() throws {
        let p = NWParameters.tcp
        p.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: p)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { [weak self] s in
            if case .ready = s { self?.port = self?.listener.port?.rawValue ?? 0; ready.signal() }
        }
        listener.newConnectionHandler = { [weak self] c in self?.handle(c) }
        listener.start(queue: q)
        _ = ready.wait(timeout: .now() + 5)
    }

    var url: URL { URL(string: "http://127.0.0.1:\(port)/file")! }

    private func handle(_ c: NWConnection) {
        c.start(queue: q)
        c.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [self] data, _, _, _ in
            guard let data else { c.cancel(); return }
            let req = String(decoding: data, as: UTF8.self)
            let range = req.split(separator: "\r\n").first { $0.lowercased().hasPrefix("range:") }.map { String($0.dropFirst(6)).trimmingCharacters(in: .whitespaces) }
            ranges.append(range)
            var start = 0
            var head = "HTTP/1.1 200 OK\r\nContent-Length: \(body.count)\r\n"
            if honorRange, let r = range, let s = Int(r.dropFirst("bytes=".count).split(separator: "-").first ?? "") {
                start = s
                head = "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes \(s)-\(body.count - 1)/\(body.count)\r\nContent-Length: \(body.count - s)\r\n"
            }
            var payload = body.suffix(from: start)
            var drop = false
            if let d = dropFirstAfter, ranges.count == 1 { payload = payload.prefix(d); drop = true }
            c.send(content: Data((head + "Connection: close\r\n\r\n").utf8) + payload, completion: .contentProcessed { _ in
                if drop { self.q.asyncAfter(deadline: .now() + 0.3) { c.cancel() } } else { c.send(content: nil, isComplete: true, completion: .contentProcessed { _ in c.cancel() }) }
            })
        }
    }

    func stop() { listener.cancel() }
}

final class DownloadTests: XCTestCase {
    private var dest: URL!
    override func setUp() {
        dest = FileManager.default.temporaryDirectory.appending(path: "dl-\(UUID().uuidString)/song.flac")
    }
    override func tearDown() { try? FileManager.default.removeItem(at: dest.deletingLastPathComponent()) }

    func testResumesPartWithRange() async throws {
        let s = try RangeServer(); defer { s.stop() }
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        try s.body.prefix(40_000).write(to: dest.appendingPathExtension("part"))
        try await PartDownload(dest: dest).run(s.url)
        XCTAssertEqual(s.ranges.first, "bytes=40000-")
        XCTAssertEqual(try Data(contentsOf: dest), s.body)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dest.appendingPathExtension("part").path))
    }

    func testServerIgnoringRangeRestartsFromZero() async throws {
        let s = try RangeServer(); s.honorRange = false; defer { s.stop() }
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        try s.body.prefix(40_000).write(to: dest.appendingPathExtension("part"))
        try await PartDownload(dest: dest).run(s.url)
        XCTAssertEqual(try Data(contentsOf: dest), s.body)
    }

    func testInterruptedDownloadResumes() async throws {
        let s = try RangeServer(); s.dropFirstAfter = 30_000; defer { s.stop() }
        do { try await PartDownload(dest: dest).run(s.url); XCTFail("should have failed") } catch {}
        let part = PartDownload.partSize(dest)
        XCTAssertGreaterThan(part, 0); XCTAssertLessThan(part, 100_000)
        try await PartDownload(dest: dest).run(s.url)
        XCTAssertEqual(s.ranges.last, "bytes=\(part)-")
        XCTAssertEqual(try Data(contentsOf: dest), s.body)
    }
}
