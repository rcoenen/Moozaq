import Foundation
import AVFoundation

private enum PlaybackState: String {
    case idle = "IDLE"
    case buffering = "BUFFERING"
    case playing = "PLAYING"
    case paused = "PAUSED"
    case error = "ERROR"
}

private enum IdleReason: String {
    case finished = "FINISHED"
    case cancelled = "CANCELLED"
    case error = "ERROR"
}

private func writeProtocolObject(_ object: [String: Any]) {
    guard JSONSerialization.isValidJSONObject(object),
          var data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else {
        return
    }
    data.append(0x0a)
    try? FileHandle.standardOutput.write(contentsOf: data)
}

@MainActor
final class PlayerController {
    private enum TransportKind {
        case play
        case pause
    }

    private struct PendingLoad {
        let id: Int
        let generation: Int
        let session: Int
        let start: Double
        let autoplay: Bool
        var initialSeekStarted = false
        var initialSeekComplete = false
        var playRequested = false
    }

    private struct PendingTransport {
        let id: Int
        let generation: Int
        let session: Int
        let kind: TransportKind
    }

    private struct PendingSeek {
        let id: Int
        let token: Int
        let generation: Int
        let session: Int
        let position: Double
        var resumeAfter: Bool
        let restorePlayIfNeeded: Bool
        var started = false
        var unavailableSince: Double?
    }

    private struct PendingVolume {
        let id: Int
        let generation: Int
        let value: Double
    }

    private let player = AVPlayer()
    private let emit: ([String: Any]) -> Void
    private let onExit: () -> Void
    private var playerObservations: [NSKeyValueObservation] = []
    private var itemObservations: [NSKeyValueObservation] = []
    private var notificationObservers: [NSObjectProtocol] = []
    private var pendingLoad: PendingLoad?
    private var pendingTransport: PendingTransport?
    private var pendingSeek: PendingSeek?
    private var pendingVolume: PendingVolume?
    private var generation = 0
    private var sessionCounter = 0
    private var seekCounter = 0
    private(set) var currentSession: Int?
    private var currentPosition = 0.0
    private var currentDuration: Double?
    private var currentVolume = 0.15
    private var playbackState: PlaybackState = .idle
    private var idleReason: IdleReason?
    private var lastCommand: Int?
    private var lastError: String?
    private(set) var isClosing = false

    var hasMediaItem: Bool {
        player.currentItem != nil
    }

    init(emit: @escaping ([String: Any]) -> Void, onExit: @escaping () -> Void) {
        self.emit = emit
        self.onExit = onExit
        player.volume = 0.15
        updateVolume()
        installPlayerObservers()
    }

    func handleLine(_ data: Data) {
        guard !isClosing else { return }
        let object: Any
        do {
            object = try JSONSerialization.jsonObject(with: data)
        } catch {
            respond(id: nil, ok: false, error: "Invalid JSON")
            return
        }
        guard let command = object as? [String: Any] else {
            respond(id: nil, ok: false, error: "Invalid command")
            return
        }
        handleCommand(command)
    }

    func handleCommand(_ command: [String: Any]) {
        guard !isClosing else { return }
        guard let id = Self.integer(command["id"]) else {
            respond(id: nil, ok: false, error: "Invalid command id")
            return
        }
        guard let operation = command["op"] as? String else {
            respond(id: id, ok: false, error: "Invalid operation")
            return
        }

        switch operation {
        case "load":
            guard let urlString = command["url"] as? String,
                  let url = Self.mediaURL(urlString) else {
                respond(id: id, ok: false, error: "Invalid URL")
                return
            }
            guard let start = Self.optionalNonnegative(command["start"], default: 0.0) else {
                respond(id: id, ok: false, error: "Invalid start")
                return
            }
            guard let autoplay = Self.optionalBoolean(command["autoplay"], default: true) else {
                respond(id: id, ok: false, error: "Invalid autoplay")
                return
            }
            updateVolume()
            guard let volume = Self.optionalVolume(command["volume"], default: currentVolume) else {
                respond(id: id, ok: false, error: "Invalid volume")
                return
            }
            guard let duration = Self.optionalDuration(command["duration"]) else {
                respond(id: id, ok: false, error: "Invalid duration")
                return
            }
            load(id: id, url: url, start: start, autoplay: autoplay, volume: volume, duration: duration,
                 headers: Self.httpHeaders(command["headers"]))
        case "play":
            play(id: id)
        case "pause":
            pause(id: id)
        case "seek":
            guard let rawPosition = command["position"],
                  let position = Self.nonnegative(rawPosition) else {
                respond(id: id, ok: false, error: "Invalid position")
                return
            }
            seek(id: id, position: position)
        case "volume":
            guard let rawVolume = command["value"],
                  let volume = Self.volume(rawVolume) else {
                respond(id: id, ok: false, error: "Invalid volume")
                return
            }
            setVolume(id: id, value: volume)
        case "stop":
            stop(id: id)
        case "state":
            respond(id: id, ok: true)
            refreshActual()
        case "close":
            respond(id: id, ok: true)
            shutdown()
        default:
            respond(id: id, ok: false, error: "Unknown operation")
        }
    }

    func receiveEOF() {
        shutdown()
    }

    private static func integer(_ value: Any?) -> Int? {
        guard let value,
              let number = value as? NSNumber,
              !Self.isBoolean(number) else {
            return nil
        }
        let double = number.doubleValue
        guard double.isFinite,
              double.rounded() == double,
              abs(double) <= 9_007_199_254_740_991.0 else {
            return nil
        }
        return Int(exactly: double)
    }

    private static func isBoolean(_ number: NSNumber) -> Bool {
        CFGetTypeID(number) == CFBooleanGetTypeID()
    }

    private static func number(_ value: Any?) -> Double? {
        guard let value,
              let number = value as? NSNumber,
              !Self.isBoolean(number) else {
            return nil
        }
        return number.doubleValue
    }

    private static func nonnegative(_ value: Any?) -> Double? {
        guard let number = Self.number(value), number.isFinite, number >= 0 else {
            return nil
        }
        return number
    }

    private static func volume(_ value: Any?) -> Double? {
        guard let number = Self.number(value), number.isFinite, number >= 0, number <= 1 else {
            return nil
        }
        return number
    }

    private static func optionalNonnegative(_ value: Any?, default defaultValue: Double) -> Double? {
        guard let value else { return defaultValue }
        return Self.nonnegative(value)
    }

    private static func optionalVolume(_ value: Any?, default defaultValue: Double) -> Double? {
        guard let value else { return defaultValue }
        return Self.volume(value)
    }

    private static func optionalBoolean(_ value: Any?, default defaultValue: Bool) -> Bool? {
        guard let value else { return defaultValue }
        guard let number = value as? NSNumber, Self.isBoolean(number) else { return nil }
        return number.boolValue
    }

    private static func optionalDuration(_ value: Any?) -> Double?? {
        guard let value else { return .some(nil) }
        if value is NSNull { return .some(nil) }
        guard let duration = Self.nonnegative(value) else { return nil }
        return .some(duration)
    }

    private static func mediaURL(_ string: String) -> URL? {
        guard !string.isEmpty,
              let url = URL(string: string),
              let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http",
              url.host != nil else {
            return nil
        }
        return url
    }

    private static func httpHeaders(_ value: Any?) -> [String: String] {
        guard let fields = value as? [String: Any] else { return [:] }
        return fields.reduce(into: [:]) { result, field in
            guard let text = field.value as? String, !text.contains("\r"), !text.contains("\n") else { return }
            result[field.key] = text
        }
    }

    private func respond(id: Int?, ok: Bool, error: String? = nil) {
        var response: [String: Any] = [
            "id": id ?? NSNull(),
            "ok": ok,
        ]
        if let error {
            response["error"] = error
        }
        emit(response)
    }

    private func nextSession() -> Int {
        if sessionCounter == Int.max {
            sessionCounter = 1
        } else {
            sessionCounter += 1
        }
        return sessionCounter
    }

    private func invalidatePendingCommands() {
        generation += 1
        pendingLoad = nil
        pendingTransport = nil
        pendingSeek = nil
        pendingVolume = nil
    }

    private func load(id: Int, url: URL, start: Double, autoplay: Bool, volume: Double, duration: Double?, headers: [String: String]) {
        invalidatePendingCommands()
        removeItemObservers()
        player.pause()

        currentSession = nextSession()
        currentPosition = start
        currentDuration = duration
        playbackState = .buffering
        idleReason = nil
        lastCommand = nil
        lastError = nil
        pendingLoad = PendingLoad(
            id: id,
            generation: generation,
            session: currentSession!,
            start: start,
            autoplay: autoplay,
            initialSeekComplete: start == 0
        )

        player.volume = Float(volume)
        updateVolume()
        let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFields": headers])
        let item = AVPlayerItem(asset: asset)
        player.replaceCurrentItem(with: item)
        installItemObservers(for: item, generation: generation)
        if autoplay {
            player.play()
        } else {
            player.play()
            player.pause()
        }

        respond(id: id, ok: true)
        emitState()
        refreshActual()
    }

    private func play(id: Int) {
        guard let session = transportSession(id: id) else { return }
        pendingTransport = PendingTransport(id: id, generation: generation, session: session, kind: .play)
        pendingSeek?.resumeAfter = true
        idleReason = nil
        lastError = nil
        respond(id: id, ok: true)
        player.play()
        refreshActual()
    }

    private func pause(id: Int) {
        guard let session = transportSession(id: id) else { return }
        pendingTransport = PendingTransport(id: id, generation: generation, session: session, kind: .pause)
        pendingSeek?.resumeAfter = false
        idleReason = nil
        lastError = nil
        respond(id: id, ok: true)
        player.pause()
        refreshActual()
    }

    private func transportSession(id: Int) -> Int? {
        guard let item = player.currentItem, let session = currentSession else {
            respond(id: id, ok: false, error: "No media loaded")
            return nil
        }
        guard pendingLoad == nil else {
            respond(id: id, ok: false, error: "Load in progress")
            return nil
        }
        guard item.status != .failed else {
            respond(id: id, ok: false, error: "Media unavailable")
            return nil
        }
        return session
    }

    private func canSeekTo(_ position: Double, item: AVPlayerItem) -> Bool {
        if position <= 0 { return true }
        return item.seekableTimeRanges.contains { value in
            let range = value.timeRangeValue
            let start = CMTimeGetSeconds(range.start)
            let duration = CMTimeGetSeconds(range.duration)
            return start.isFinite && duration.isFinite && duration >= 0 &&
                position >= start && position <= start + duration
        }
    }

    @discardableResult
    private func advancePendingSeek(_ item: AVPlayerItem) -> Bool {
        guard var seek = pendingSeek,
              seek.generation == generation,
              seek.session == currentSession else { return false }
        if !seek.started {
            if !canSeekTo(seek.position, item: item) {
                if seek.unavailableSince == nil {
                    seek.unavailableSince = Date().timeIntervalSince1970
                    pendingSeek = seek
                    playbackState = .buffering
                    return true
                }
                if Date().timeIntervalSince1970 - seek.unavailableSince! < 2 {
                    playbackState = .buffering
                    return true
                }
                pendingSeek = nil
                lastCommand = seek.id
                lastError = "Seek unavailable"
                playbackState = .error
                idleReason = .error
                emitState()
                return true
            }
            seek.unavailableSince = nil
            seek.started = true
            pendingSeek = seek
            let id = seek.id
            let token = seek.token
            let callbackGeneration = seek.generation
            player.seek(
                to: mediaTime(seek.position),
                toleranceBefore: .zero,
                toleranceAfter: .zero,
                completionHandler: { [weak self] finished in
                    Task { @MainActor in
                        self?.completeSeek(
                            id: id,
                            token: token,
                            generation: callbackGeneration,
                            finished: finished
                        )
                    }
                }
            )
        }
        return false
    }

    private func seek(id: Int, position: Double) {
        guard let item = player.currentItem, let session = currentSession else {
            respond(id: id, ok: false, error: "No media loaded")
            return
        }
        guard pendingLoad == nil else {
            respond(id: id, ok: false, error: "Load in progress")
            return
        }
        guard item.status == .readyToPlay else {
            respond(id: id, ok: false, error: item.status == .failed ? "Media unavailable" : "Media not ready")
            return
        }

        let actuallyPlaying = player.timeControlStatus == .playing && player.rate > 0
        let intendedPlaying: Bool
        if let transport = pendingTransport {
            intendedPlaying = transport.kind == .play
        } else {
            intendedPlaying = player.timeControlStatus != .paused
        }
        seekCounter += 1
        let callbackToken = seekCounter
        pendingSeek = PendingSeek(
            id: id,
            token: callbackToken,
            generation: generation,
            session: session,
            position: position,
            resumeAfter: intendedPlaying,
            restorePlayIfNeeded: actuallyPlaying
        )
        idleReason = nil
        lastError = nil
        respond(id: id, ok: true)
        advancePendingSeek(item)
        refreshActual()
    }

    private func completeSeek(id: Int, token: Int, generation callbackGeneration: Int, finished: Bool) {
        guard !isClosing,
              callbackGeneration == generation,
              let seek = pendingSeek,
              seek.id == id,
              seek.token == token,
              seek.generation == generation,
              seek.session == currentSession else {
            return
        }
        guard finished else {
            pendingSeek = nil
            lastCommand = id
            lastError = "Seek failed"
            playbackState = .error
            idleReason = .error
            emitState()
            return
        }

        if !seek.resumeAfter, player.rate != 0 {
            player.pause()
        } else if seek.resumeAfter,
                  seek.restorePlayIfNeeded,
                  player.rate == 0,
                  player.timeControlStatus == .paused {
            player.play()
        }

        let actualPosition = CMTimeGetSeconds(player.currentTime())
        guard actualPosition.isFinite, actualPosition >= 0 else {
            pendingSeek = nil
            lastCommand = id
            lastError = "Seek failed"
            playbackState = .error
            idleReason = .error
            emitState()
            return
        }
        currentPosition = actualPosition
        pendingSeek = nil
        lastCommand = id
        lastError = nil
        idleReason = nil
        refreshActual()
    }

    private func setVolume(id: Int, value: Double) {
        pendingVolume = PendingVolume(id: id, generation: generation, value: value)
        respond(id: id, ok: true)
        player.volume = Float(value)
        refreshActual()
    }

    private func stop(id: Int) {
        invalidatePendingCommands()
        removeItemObservers()
        player.pause()
        player.replaceCurrentItem(with: nil)
        currentSession = nil
        currentPosition = 0
        currentDuration = nil
        playbackState = .idle
        idleReason = .cancelled
        lastCommand = id
        lastError = nil
        updateVolume()
        respond(id: id, ok: true)
        emitState()
    }

    private func shutdown() {
        guard !isClosing else { return }
        isClosing = true
        invalidatePendingCommands()
        removeItemObservers()
        playerObservations.forEach { $0.invalidate() }
        playerObservations.removeAll()
        player.pause()
        player.replaceCurrentItem(with: nil)
        currentSession = nil
        currentPosition = 0
        currentDuration = nil
        playbackState = .idle
        idleReason = .cancelled
        lastError = nil
        onExit()
    }

    private func mediaTime(_ seconds: Double) -> CMTime {
        let scale = 1000.0
        if seconds >= Double(Int64.max) / scale {
            return CMTime(value: Int64.max, timescale: 1000)
        }
        return CMTime(seconds: seconds, preferredTimescale: 1000)
    }

    private func installPlayerObservers() {
        playerObservations = [
            player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in
                    self?.refreshActual()
                }
            },
            player.observe(\.rate, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in
                    self?.refreshActual()
                }
            },
            player.observe(\.volume, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in
                    self?.refreshActual()
                }
            },
        ]
    }

    private func installItemObservers(for item: AVPlayerItem, generation observedGeneration: Int) {
        itemObservations = [
            item.observe(\.status, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in
                    guard let self, self.generation == observedGeneration else { return }
                    self.refreshActual()
                }
            },
            item.observe(\.duration, options: [.new]) { [weak self] _, _ in
                Task { @MainActor in
                    guard let self, self.generation == observedGeneration else { return }
                    self.refreshActual()
                }
            },
        ]

        let center = NotificationCenter.default
        let end = center.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: nil
        ) { [weak self] notification in
            let identifier = (notification.object as? AVPlayerItem).map(ObjectIdentifier.init)
            Task { @MainActor in
                guard let self,
                      self.generation == observedGeneration,
                      let currentItem = self.player.currentItem,
                      ObjectIdentifier(currentItem) == identifier else { return }
                self.didPlayToEnd()
            }
        }
        let failure = center.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: nil
        ) { [weak self] notification in
            let identifier = (notification.object as? AVPlayerItem).map(ObjectIdentifier.init)
            Task { @MainActor in
                guard let self,
                      self.generation == observedGeneration,
                      let currentItem = self.player.currentItem,
                      ObjectIdentifier(currentItem) == identifier else { return }
                self.failCurrentItem("Playback failed")
            }
        }
        let stalled = center.addObserver(
            forName: .AVPlayerItemPlaybackStalled,
            object: item,
            queue: nil
        ) { [weak self] notification in
            let identifier = (notification.object as? AVPlayerItem).map(ObjectIdentifier.init)
            Task { @MainActor in
                guard let self,
                      self.generation == observedGeneration,
                      let currentItem = self.player.currentItem,
                      ObjectIdentifier(currentItem) == identifier else { return }
                self.refreshActual()
            }
        }
        notificationObservers = [end, failure, stalled]
    }

    private func removeItemObservers() {
        itemObservations.forEach { $0.invalidate() }
        itemObservations.removeAll()
        let center = NotificationCenter.default
        notificationObservers.forEach { center.removeObserver($0) }
        notificationObservers.removeAll()
    }

    private func refreshActual() {
        guard !isClosing else { return }
        updateVolume()
        finalizeVolumeCommand()

        guard let item = player.currentItem, currentSession != nil else {
            currentSession = nil
            currentPosition = 0
            currentDuration = nil
            playbackState = .idle
            emitState()
            return
        }

        updateDuration(item)
        if item.status == .failed {
            failCurrentItem(pendingLoad == nil ? "Playback failed" : "Load failed")
            return
        }

        if pendingLoad != nil {
            if pendingLoad?.initialSeekComplete == true {
                updatePosition()
            }
            advancePendingLoad(item)
            emitState()
            return
        }

        updatePosition()
        if advancePendingSeek(item) {
            emitState()
            return
        }
        finalizeTransportCommand()
        if playbackState != .error && !(playbackState == .idle && idleReason == .finished) {
            playbackState = derivedState(item)
        }
        emitState()
    }

    private func advancePendingLoad(_ item: AVPlayerItem) {
        guard var load = pendingLoad,
              load.generation == generation,
              load.session == currentSession else { return }
        guard item.status == .readyToPlay else {
            playbackState = .buffering
            return
        }

        if !load.initialSeekComplete {
            playbackState = .buffering
            guard !load.initialSeekStarted else { return }
            guard canSeekTo(load.start, item: item) else {
                playbackState = .buffering
                return
            }
            load.initialSeekStarted = true
            pendingLoad = load
            let loadID = load.id
            let loadGeneration = load.generation
            let start = load.start
            player.seek(
                to: mediaTime(start),
                toleranceBefore: .zero,
                toleranceAfter: .zero,
                completionHandler: { [weak self] finished in
                    Task { @MainActor in
                        self?.completeInitialSeek(
                            id: loadID,
                            generation: loadGeneration,
                            finished: finished
                        )
                    }
                }
            )
            return
        }

        if load.autoplay {
            if !load.playRequested {
                load.playRequested = true
                pendingLoad = load
                player.play()
            }
            if player.timeControlStatus == .playing, player.rate > 0 {
                pendingLoad = nil
                lastCommand = load.id
                lastError = nil
                idleReason = nil
                playbackState = .playing
            } else {
                playbackState = .buffering
            }
        } else {
            if player.rate != 0 {
                player.pause()
            }
            if player.rate == 0, player.timeControlStatus == .paused {
                pendingLoad = nil
                lastCommand = load.id
                lastError = nil
                idleReason = nil
                playbackState = .paused
            } else {
                playbackState = .buffering
            }
        }
    }

    private func completeInitialSeek(id: Int, generation callbackGeneration: Int, finished: Bool) {
        guard !isClosing,
              callbackGeneration == generation,
              var load = pendingLoad,
              load.id == id,
              load.generation == generation,
              load.session == currentSession else { return }
        guard finished else {
            failCurrentItem("Load failed")
            return
        }
        let actualPosition = CMTimeGetSeconds(player.currentTime())
        guard actualPosition.isFinite, actualPosition >= 0 else {
            failCurrentItem("Load failed")
            return
        }
        currentPosition = actualPosition
        load.initialSeekComplete = true
        pendingLoad = load
        refreshActual()
    }

    private func finalizeTransportCommand() {
        guard let transport = pendingTransport,
              transport.generation == generation,
              transport.session == currentSession else { return }
        switch transport.kind {
        case .play:
            guard player.timeControlStatus == .playing, player.rate > 0 else { return }
        case .pause:
            guard player.rate == 0, player.timeControlStatus == .paused else { return }
        }
        pendingTransport = nil
        lastCommand = transport.id
        lastError = nil
        idleReason = nil
    }

    private func finalizeVolumeCommand() {
        guard let volume = pendingVolume, volume.generation == generation else { return }
        guard abs(currentVolume - volume.value) <= 0.0001 else { return }
        pendingVolume = nil
        lastCommand = volume.id
    }

    private func derivedState(_ item: AVPlayerItem) -> PlaybackState {
        guard item.status == .readyToPlay else { return .buffering }
        switch player.timeControlStatus {
        case .playing:
            return player.rate > 0 ? .playing : .buffering
        case .paused:
            return player.rate == 0 ? .paused : .buffering
        case .waitingToPlayAtSpecifiedRate:
            return .buffering
        @unknown default:
            return .buffering
        }
    }

    private func didPlayToEnd() {
        updatePosition()
        updateVolume()
        pendingLoad = nil
        pendingTransport = nil
        pendingSeek = nil
        playbackState = .idle
        idleReason = .finished
        lastCommand = nil
        lastError = nil
        emitState()
    }

    private func failCurrentItem(_ message: String) {
        guard !isClosing else { return }
        if let load = pendingLoad {
            lastCommand = load.id
        } else {
            lastCommand = nil
        }
        pendingLoad = nil
        pendingTransport = nil
        pendingSeek = nil
        updateVolume()
        if currentPosition.isFinite == false || currentPosition < 0 {
            currentPosition = 0
        }
        playbackState = .error
        idleReason = .error
        lastError = message
        emitState()
    }

    private func updatePosition() {
        let position = CMTimeGetSeconds(player.currentTime())
        if position.isFinite, position >= 0 {
            currentPosition = position
        }
    }

    private func updateDuration(_ item: AVPlayerItem) {
        let duration = CMTimeGetSeconds(item.duration)
        if duration.isFinite, duration >= 0 {
            currentDuration = duration
        }
    }

    private func updateVolume() {
        let volume = Double(player.volume)
        if volume.isFinite {
            currentVolume = min(max(volume, 0), 1)
        }
    }

    private func emitState() {
        guard !isClosing else { return }
        let safePosition = currentPosition.isFinite && currentPosition >= 0 ? currentPosition : 0
        let safeVolume = currentVolume.isFinite ? min(max(currentVolume, 0), 1) : 0
        let duration: Any
        if let currentDuration, currentDuration.isFinite, currentDuration >= 0 {
            duration = currentDuration
        } else {
            duration = NSNull()
        }
        let state: [String: Any] = [
            "connected": true,
            "state": playbackState.rawValue,
            "position": safePosition,
            "duration": duration,
            "volume": safeVolume,
            "session": currentSession ?? NSNull(),
            "command": lastCommand ?? NSNull(),
            "error": lastError ?? NSNull(),
            "idle_reason": idleReason?.rawValue ?? NSNull(),
            "item_status": player.currentItem?.status.rawValue ?? -1,
            "time_control": player.timeControlStatus.rawValue,
            "waiting_reason": player.reasonForWaitingToPlay?.rawValue ?? NSNull(),
            "error_domain": (player.currentItem?.error as NSError?)?.domain ?? NSNull(),
            "error_code": (player.currentItem?.error as NSError?)?.code ?? NSNull(),
        ]
        let message: [String: Any] = ["event": "state", "state": state]
        emit(message)
    }
}

final class StdinReader {
    private let controller: PlayerController
    private let input: FileHandle

    init(controller: PlayerController, input: FileHandle = .standardInput) {
        self.controller = controller
        self.input = input
    }

    func start() {
        let controller = self.controller
        let input = self.input
        DispatchQueue.global(qos: .utility).async {
            var buffer = Data()
            while true {
                let chunk = input.availableData
                guard !chunk.isEmpty else {
                    if !buffer.isEmpty {
                        Self.deliver(Self.line(buffer), to: controller)
                    }
                    DispatchQueue.main.async {
                        MainActor.assumeIsolated {
                            controller.receiveEOF()
                        }
                    }
                    return
                }
                buffer.append(chunk)
                while let newline = buffer.firstIndex(of: 0x0a) {
                    let line = Data(buffer[..<newline])
                    buffer.removeSubrange(buffer.startIndex...newline)
                    Self.deliver(Self.line(line), to: controller)
                }
            }
        }
    }

    private static func line(_ data: Data) -> Data {
        var line = data
        if line.last == 0x0d {
            line.removeLast()
        }
        return line
    }

    private static func deliver(_ line: Data, to controller: PlayerController) {
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                controller.handleLine(line)
            }
        }
    }
}

@MainActor
private enum SelfCheck {
    static func run() -> Bool {
        var failures = 0
        var messages: [[String: Any]] = []
        var exits = 0
        let controller = PlayerController(
            emit: { messages.append($0) },
            onExit: { exits += 1 }
        )

        func expect(_ condition: @autoclosure () -> Bool) {
            if !condition() { failures += 1 }
        }

        func line(_ object: [String: Any]) -> Data {
            try! JSONSerialization.data(withJSONObject: object)
        }

        func lastState() -> [String: Any]? {
            guard let message = messages.last,
                  message["event"] as? String == "state" else { return nil }
            return message["state"] as? [String: Any]
        }

        func expectAck(id: Int, ok: Bool) {
            guard let message = messages.first else {
                failures += 1
                return
            }
            expect(message["id"] as? Int == id)
            expect(message["ok"] as? Bool == ok)
            if !ok {
                expect((message["error"] as? String)?.isEmpty == false)
            }
        }

        controller.handleLine(line(["id": 1, "op": "state"]))
        expect(messages.count == 2)
        expectAck(id: 1, ok: true)
        let empty = lastState()
        expect(empty != nil)
        if let empty {
            expect(Set(empty.keys) == Set([
                "connected", "state", "position", "duration", "volume",
                "session", "command", "error", "idle_reason",
                "item_status", "time_control", "waiting_reason", "error_domain", "error_code",
            ]))
            expect(empty["connected"] as? Bool == true)
            expect(empty["state"] as? String == "IDLE")
            expect(empty["position"] as? Double == 0)
            expect(empty["duration"] is NSNull)
            if let volume = empty["volume"] as? Double {
                expect(volume >= 0 && volume <= 1)
            } else {
                failures += 1
            }
            expect(empty["session"] is NSNull)
            expect(empty["command"] is NSNull)
            expect(empty["error"] is NSNull)
            expect(empty["idle_reason"] is NSNull)
        }
        expect(!controller.hasMediaItem)

        messages.removeAll()
        controller.handleLine(line(["id": 2, "op": "volume", "value": 0.0]))
        expect(messages.count == 2)
        expectAck(id: 2, ok: true)
        let volumeState = lastState()
        expect(volumeState?["state"] as? String == "IDLE")
        expect(volumeState?["command"] as? Int == 2)
        expect((volumeState?["volume"] as? Double) == 0)
        expect(volumeState?["session"] is NSNull)
        expect(!controller.hasMediaItem)

        messages.removeAll()
        controller.handleLine(Data("not json".utf8))
        expect(messages.count == 1)
        expect(messages[0]["ok"] as? Bool == false)
        expect((messages[0]["error"] as? String)?.isEmpty == false)
        expect(!controller.hasMediaItem)

        messages.removeAll()
        controller.handleLine(line(["id": 3, "op": "seek", "position": -1.0]))
        expect(messages.count == 1)
        expectAck(id: 3, ok: false)
        controller.handleCommand(["id": 4, "op": "volume", "value": Double.infinity])
        expect(messages.count == 2)
        expect(messages.last?["ok"] as? Bool == false)
        controller.handleCommand(["id": 5, "op": "seek", "position": Double.nan])
        expect(messages.count == 3)
        expect(messages.last?["ok"] as? Bool == false)
        controller.handleCommand(["id": 6, "op": "volume", "value": 1.5])
        expect(messages.count == 4)
        expect(messages.last?["ok"] as? Bool == false)
        expect(!controller.hasMediaItem)

        messages.removeAll()
        controller.handleLine(line(["id": 7, "op": "stop"]))
        expect(messages.count == 2)
        expectAck(id: 7, ok: true)
        let stopped = lastState()
        expect(stopped?["state"] as? String == "IDLE")
        expect(stopped?["command"] as? Int == 7)
        expect(stopped?["idle_reason"] as? String == "CANCELLED")
        expect(stopped?["session"] is NSNull)
        expect(stopped?["position"] as? Double == 0)
        expect(!controller.hasMediaItem)

        messages.removeAll()
        controller.handleLine(line(["id": 8, "op": "state"]))
        expect(messages.count == 2)
        expectAck(id: 8, ok: true)
        expect(lastState()?["command"] as? Int == 7)
        expect(!controller.hasMediaItem)

        controller.receiveEOF()
        controller.receiveEOF()
        expect(exits == 1)
        expect(controller.isClosing)
        expect(!controller.hasMediaItem)
        expect(controller.currentSession == nil)

        var closeMessages: [[String: Any]] = []
        var closeExits = 0
        let closeController = PlayerController(
            emit: { closeMessages.append($0) },
            onExit: { closeExits += 1 }
        )
        closeController.handleLine(line(["id": 9, "op": "close"]))
        closeController.receiveEOF()
        expect(closeMessages.count == 1)
        expect(closeMessages.first?["id"] as? Int == 9)
        expect(closeMessages.first?["ok"] as? Bool == true)
        expect(closeExits == 1)
        expect(closeController.isClosing)
        expect(!closeController.hasMediaItem)
        expect(closeController.currentSession == nil)

        return failures == 0
    }
}

@main
struct MoozaqAudioPlayer {
    @MainActor
    static func main() {
        if CommandLine.arguments.dropFirst().contains("--check") {
            let success = SelfCheck.run()
            if !success {
                try? FileHandle.standardError.write(contentsOf: Data("Self-check failed\n".utf8))
            }
            exit(success ? 0 : 1)
        }

        let controller = PlayerController(
            emit: { writeProtocolObject($0) },
            onExit: { exit(0) }
        )
        let reader = StdinReader(controller: controller)
        reader.start()
        withExtendedLifetime(reader) {
            RunLoop.main.run()
        }
    }
}
