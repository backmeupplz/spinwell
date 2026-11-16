import XCTest

/// Step runner for checking the app against a real server in the Simulator (nothing here ships).
/// `TEST_RUNNER_SW_STEPS=/path/steps.txt` (one step per line), `TEST_RUNNER_SW_OUT=/dir` for screenshots,
/// `TEST_RUNNER_SW_SERVER/USER/PASS` sign in at launch (only when the app is signed out).
/// Steps: `launch [args]`, `tap Label`, `tapid id`, `tapfirst Label`, `shot name`, `wait 2`, `up 3`, `down 3`, `type text`,
/// `back`, `scrollperf 5` (hitch metrics), `launchperf` (cold launch time), `exists Label`, `log text`.
final class Drive: XCTestCase {
    let env = ProcessInfo.processInfo.environment
    let app = XCUIApplication()

    func testSteps() throws {
        guard let path = env["SW_STEPS"], let text = try? String(contentsOfFile: path, encoding: .utf8) else { throw XCTSkip("no SW_STEPS") }
        continueAfterFailure = true
        for raw in text.split(separator: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }
            let parts = line.split(separator: " ", maxSplits: 1).map(String.init)
            run(parts[0], parts.count > 1 ? parts[1] : "")
        }
    }

    func launch(_ extra: [String] = []) {
        app.terminate()
        var a = extra
        if let s = env["SW_SERVER"], let u = env["SW_USER"], let p = env["SW_PASS"] { a += ["-server", s, "-user", u, "-pass", p] }
        app.launchArguments = a
        app.launch()
    }

    func find(_ s: String) -> XCUIElement {
        let p = NSPredicate(format: "label CONTAINS[c] %@ OR identifier == %@", s, s)
        return app.descendants(matching: .any).matching(p).firstMatch
    }

    func run(_ cmd: String, _ arg: String) {
        switch cmd {
        case "launch": launch(arg.split(separator: " ").map(String.init))
        case "tap", "tapfirst":
            let e = find(arg)
            if e.waitForExistence(timeout: 15) { e.tap() } else { XCTFail("missing \(arg)"); shot("zz-missing") }
        case "tapid": let e = app.descendants(matching: .any)[arg].firstMatch; if e.waitForExistence(timeout: 15) { e.tap() } else { XCTFail("missing id \(arg)") }
        case "tapidopt": let e = app.descendants(matching: .any)[arg].firstMatch; if e.waitForExistence(timeout: 3) { e.tap() } // only if it is there
        case "home": XCUIDevice.shared.press(.home)
        case "longpressid": let e = app.descendants(matching: .any)[arg]; if e.waitForExistence(timeout: 15) { e.press(forDuration: 1.2) } else { XCTFail("missing id \(arg)") }
        case "block": try? "refused".write(toFile: "/tmp/spinwell-block", atomically: true, encoding: .utf8) // Debug app sends every request to a dead address
        case "blackhole": try? "blackhole".write(toFile: "/tmp/spinwell-block", atomically: true, encoding: .utf8)
        case "unblock": try? FileManager.default.removeItem(atPath: "/tmp/spinwell-block")
        case "taprow": // tap the Nth song row: `taprow 0`
            let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'song-'"))
            let e = rows.element(boundBy: Int(arg) ?? 0); if e.waitForExistence(timeout: 15) { e.tap() } else { XCTFail("no song row \(arg)") }
        case "rmlog": try? FileManager.default.removeItem(atPath: arg)
        case "shotnow": shot(arg)
        case "exists": print("EXISTS \(arg): \(find(arg).waitForExistence(timeout: 5))")
        case "shot": sleep(2); shot(arg)
        case "wait": sleep(UInt32(arg) ?? 1)
        case "up": for _ in 0..<(Int(arg) ?? 1) { app.swipeUp(velocity: .fast) }
        case "down": for _ in 0..<(Int(arg) ?? 1) { app.swipeDown(velocity: .fast) }
        case "type": app.typeText(arg)
        case "back": app.navigationBars.buttons.firstMatch.tap()
        case "log": print("LOG \(arg)")
        case "playperf":
            // hitches while a song row is tapped and the full player opens and closes: `playperf <row label>`
            let opts = XCTMeasureOptions(); opts.iterationCount = 3
            measure(metrics: [XCTHitchMetric(application: app)], options: opts) {
                let row = find(arg); if row.waitForExistence(timeout: 10) { row.tap() }
                let mini = app.descendants(matching: .any)["mini-player"].firstMatch
                if mini.waitForExistence(timeout: 10) { mini.tap() }
                sleep(2)
                app.swipeDown(velocity: .fast)
                sleep(1)
            }
        case "launchperf":
            measure(metrics: [XCTApplicationLaunchMetric(waitUntilResponsive: true)]) { launch() }
        case "scrollperf":
            let n = Int(arg) ?? 5
            let opts = XCTMeasureOptions(); opts.iterationCount = 3
            measure(metrics: [XCTHitchMetric(application: app)], options: opts) {
                for _ in 0..<n { app.swipeUp(velocity: .fast) }
                for _ in 0..<n { app.swipeDown(velocity: .fast) }
            }
        default: XCTFail("unknown step \(cmd)")
        }
    }

    func shot(_ name: String) {
        let out = env["SW_OUT"] ?? "/tmp/spinwell-shots"
        try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(out)/\(name).png"))
    }
}
