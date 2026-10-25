import AVFoundation
import Foundation
import MediaPlayer
import Photos
import UIKit

/// One queue item, as sent by PHP (see SPEC.md "Queue item").
struct ProtendaiMediaItem {
    let id: String
    let uri: String
    let title: String?
    let artist: String?
    let album: String?
    let artwork: String?
    let type: String // "audio" | "video"

    init?(json: [String: Any]) {
        guard let uri = json["uri"] as? String, !uri.isEmpty else { return nil }
        self.uri = uri
        self.id = (json["id"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? uri
        self.title = Self.string(json["title"]) ?? URL(fileURLWithPath: uri).deletingPathExtension().lastPathComponent
        self.artist = Self.string(json["artist"])
        self.album = Self.string(json["album"])
        self.artwork = Self.string(json["artwork"])
        self.type = (json["type"] as? String) == "video" ? "video" : "audio"
    }

    private static func string(_ value: Any?) -> String? {
        guard let s = value as? String, !s.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return s
    }

    /// File path, file:// / http(s) / ipod-library:// URL. "ph://<id>" (Photos) is resolved separately.
    static func url(for uri: String) -> URL? {
        if uri.hasPrefix("/") { return URL(fileURLWithPath: uri) }
        return URL(string: uri)
    }
}

/// The iOS player behind Media.*: one AVPlayer driving a queue, with
/// shuffle/repeat/speed, per-item resume positions, background audio, the
/// lock screen / Control Center (MPNowPlayingInfoCenter + remote commands),
/// and a state cache PHP can read from any thread.
///
/// Mirrors Android's MediaManager + PlaybackService. All player work happens
/// on the main thread; bridge functions hop there and return at once.
final class ProtendaiMediaPlayer: NSObject {

    static let shared = ProtendaiMediaPlayer()

    private static let eventNamespace = "Protendai\\Media\\Events\\"
    private static let positionsKey = "protendai_media_positions"
    private static let saveEvery: TimeInterval = 5
    private static let endMargin: Double = 5 // seconds: closer to the end counts as finished

    let player = AVPlayer()

    private var queue: [ProtendaiMediaItem] = []
    private var order: [Int] = []      // play order (queue indexes)
    private var orderPos = -1          // position in `order`
    private var shuffle = false
    private var repeatMode = "off"
    private var speed: Float = 1
    private var lastError: String?
    private var ended = false
    private var wantsPlay = false
    private var pendingSeek: Double?
    private var loadToken = 0
    /// Id of the item whose AVPlayerItem is in the player right now. Positions
    /// are saved under this id, never under the queue cursor: the cursor moves
    /// before the (possibly async) load swaps the player item.
    private var loadedId: String?

    private var tickTimer: Timer?
    private var lastSave = Date.distantPast
    private var itemStatusObservation: NSKeyValueObservation?
    private var timeControlObservation: NSKeyValueObservation?
    private var remoteCommandsReady = false

    private let stateLock = NSLock()
    private var cachedState: [String: Any] = ProtendaiMediaPlayer.idleState
    private var lastSignature = ""

    static let idleState: [String: Any] = [
        "playing": false, "buffering": false, "index": -1, "count": 0,
        "id": NSNull(), "title": NSNull(), "artist": NSNull(), "artwork": NSNull(), "type": NSNull(),
        "positionMs": 0, "durationMs": 0, "shuffle": false, "repeat": "off", "speed": 1.0, "error": NSNull(),
    ]

    private override init() {
        super.init()
        player.automaticallyWaitsToMinimizeStalling = true

        timeControlObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.timeControlChanged() }
        }

        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(itemEnded(_:)), name: .AVPlayerItemDidPlayToEndTime, object: nil)
        center.addObserver(self, selector: #selector(itemFailed(_:)), name: .AVPlayerItemFailedToPlayToEndTime, object: nil)
        center.addObserver(self, selector: #selector(audioInterrupted(_:)), name: AVAudioSession.interruptionNotification, object: nil)
        center.addObserver(self, selector: #selector(routeChanged(_:)), name: AVAudioSession.routeChangeNotification, object: nil)
    }

    // MARK: - State (any thread)

    func state() -> [String: Any] {
        stateLock.lock()
        defer { stateLock.unlock() }
        return cachedState
    }

    // MARK: - Commands (main thread)

    /// Replace the queue. startPositionMs < 0 resumes the start item from its saved position.
    func setQueue(_ items: [ProtendaiMediaItem], startIndex: Int, startPositionMs: Int, play: Bool) {
        guard !items.isEmpty else { return }
        savePosition() // the outgoing item, before the queue is replaced
        queue = items
        let index = min(max(startIndex, 0), items.count - 1)
        rebuildOrder(current: index)
        lastError = nil
        ended = false
        let position = startPositionMs >= 0 ? Double(startPositionMs) / 1000 : savedPosition(items[index].id)
        load(index: index, position: position, play: play)
    }

    func play() {
        guard currentIndex >= 0 else { return }
        if ended {
            // Replay a finished queue from the top instead of doing nothing.
            ended = false
            orderPos = 0
            load(index: order[0], position: 0, play: true)
            return
        }
        wantsPlay = true
        activateSession()
        player.playImmediately(atRate: speed)
        refreshState()
    }

    func pause() {
        wantsPlay = false
        player.pause()
        savePosition()
        refreshState()
    }

    func toggle() {
        player.timeControlStatus == .paused ? play() : pause()
    }

    /// Next item, from its start (saved positions apply to setQueue and skipTo only, like Android).
    func next() {
        guard !order.isEmpty else { return }
        savePosition()
        if orderPos + 1 < order.count {
            orderPos += 1
        } else if repeatMode == "all" {
            orderPos = 0
        } else {
            return
        }
        load(index: order[orderPos], position: 0, play: wantsPlay || isPlaying)
    }

    /// Like Media3: restart the track when past 3 s, otherwise go back one.
    func previous() {
        guard !order.isEmpty else { return }
        if currentSeconds > 3 || (orderPos == 0 && repeatMode != "all") {
            seek(toMs: 0)
            return
        }
        savePosition()
        orderPos = orderPos > 0 ? orderPos - 1 : order.count - 1
        load(index: order[orderPos], position: 0, play: wantsPlay || isPlaying)
    }

    /// Jump to a queue index, resuming that item's saved position.
    func skipTo(_ index: Int) {
        guard index >= 0, index < queue.count else { return }
        savePosition()
        if let pos = order.firstIndex(of: index) { orderPos = pos }
        ended = false
        load(index: index, position: savedPosition(queue[index].id), play: true)
    }

    func seek(toMs ms: Int) {
        let time = CMTime(seconds: Double(max(ms, 0)) / 1000, preferredTimescale: 600)
        player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            DispatchQueue.main.async {
                self?.refreshState()
                self?.updateNowPlaying()
            }
        }
    }

    func setShuffle(_ enabled: Bool) {
        shuffle = enabled
        rebuildOrder(current: currentIndex)
        refreshState()
    }

    func setRepeat(_ mode: String) {
        repeatMode = ["one", "all"].contains(mode) ? mode : "off"
        refreshState()
    }

    func setSpeed(_ value: Float) {
        speed = min(max(value, 0.25), 3)
        player.defaultRate = speed
        if player.timeControlStatus != .paused { player.rate = speed }
        refreshState()
        updateNowPlaying()
    }

    func stop() {
        savePosition()
        wantsPlay = false
        loadToken += 1
        player.pause()
        player.replaceCurrentItem(with: nil)
        loadedId = nil
        itemStatusObservation = nil
        queue = []
        order = []
        orderPos = -1
        ended = false
        stopTick()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        // Hand audio back to whatever the user was listening to before.
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        refreshState()
    }

    // MARK: - Loading

    private var currentIndex: Int { orderPos >= 0 && orderPos < order.count ? order[orderPos] : -1 }

    private var currentItem: ProtendaiMediaItem? {
        let index = currentIndex
        return index >= 0 ? queue[index] : nil
    }

    private var isPlaying: Bool { player.timeControlStatus == .playing }

    /// Id of the queue item the cursor is on (main thread).
    var currentId: String? { currentItem?.id }

    private var currentSeconds: Double {
        let s = player.currentTime().seconds
        return s.isFinite ? max(s, 0) : 0
    }

    private var durationSeconds: Double {
        guard let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 else { return 0 }
        return d
    }

    private func rebuildOrder(current: Int) {
        let all = Array(queue.indices)
        if shuffle, current >= 0 {
            order = [current] + all.filter { $0 != current }.shuffled()
            orderPos = 0
        } else {
            order = all
            orderPos = current >= 0 ? current : (all.isEmpty ? -1 : 0)
        }
    }

    private func load(index: Int, position: Double, play: Bool) {
        guard index >= 0, index < queue.count else { return }
        if let pos = order.firstIndex(of: index) { orderPos = pos }

        loadToken += 1
        let token = loadToken
        let item = queue[index]
        wantsPlay = play
        pendingSeek = position > 0 ? position : nil
        lastError = nil

        var track: [String: Any] = ["id": item.id, "index": index, "type": item.type]
        if let title = item.title { track["title"] = title }
        if let artist = item.artist { track["artist"] = artist }
        dispatch("TrackChanged", track)

        makePlayerItem(for: item) { [weak self] playerItem in
            guard let self = self, token == self.loadToken else { return }
            guard let playerItem = playerItem else {
                self.fail(id: item.id, message: "This item could not be opened")
                return
            }
            self.watch(playerItem, token: token)
            self.player.replaceCurrentItem(with: playerItem)
            self.loadedId = item.id
            self.player.defaultRate = self.speed
            if play {
                self.activateSession()
                self.player.playImmediately(atRate: self.speed)
            }
            self.setUpRemoteCommands()
            self.updateNowPlaying()
            self.refreshState()
        }
    }

    /// AVPlayerItem for a queue item; Photos videos ("ph://<localIdentifier>") resolve asynchronously.
    private func makePlayerItem(for item: ProtendaiMediaItem, completion: @escaping (AVPlayerItem?) -> Void) {
        if item.uri.hasPrefix("ph://") {
            let identifier = String(item.uri.dropFirst("ph://".count))
            guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject else {
                completion(nil)
                return
            }
            let options = PHVideoRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .automatic
            _ = PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { playerItem, _ in
                DispatchQueue.main.async { completion(playerItem) }
            }
            return
        }

        guard let url = ProtendaiMediaItem.url(for: item.uri) else {
            completion(nil)
            return
        }
        completion(AVPlayerItem(url: url))
    }

    private func watch(_ playerItem: AVPlayerItem, token: Int) {
        itemStatusObservation = playerItem.observe(\.status, options: [.new]) { [weak self] observed, _ in
            DispatchQueue.main.async {
                guard let self = self, token == self.loadToken else { return }
                switch observed.status {
                case .readyToPlay:
                    if let seconds = self.pendingSeek {
                        self.pendingSeek = nil
                        self.player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                    }
                    self.updateNowPlaying()
                    self.refreshState()
                case .failed:
                    self.fail(id: self.currentItem?.id, message: observed.error?.localizedDescription ?? "Playback failed")
                default:
                    break
                }
            }
        }
    }

    private func fail(id: String?, message: String) {
        lastError = message
        var payload: [String: Any] = ["message": message]
        if let id = id { payload["id"] = id }
        dispatch("PlaybackError", payload)
        refreshState()
    }

    // MARK: - Player notifications

    @objc private func itemEnded(_ note: Notification) {
        DispatchQueue.main.async {
            guard (note.object as? AVPlayerItem) === self.player.currentItem else { return }
            if let id = self.loadedId { self.clearPosition(id) }

            if self.repeatMode == "one" {
                self.player.seek(to: .zero)
                self.player.playImmediately(atRate: self.speed)
                return
            }
            if self.orderPos + 1 < self.order.count {
                self.orderPos += 1
                self.load(index: self.order[self.orderPos], position: 0, play: true)
            } else if self.repeatMode == "all", !self.order.isEmpty {
                self.orderPos = 0
                self.load(index: self.order[0], position: 0, play: true)
            } else {
                self.ended = true
                self.wantsPlay = false
                self.player.pause()
                self.dispatch("QueueEnded", [:])
                self.refreshState()
            }
        }
    }

    @objc private func itemFailed(_ note: Notification) {
        DispatchQueue.main.async {
            guard (note.object as? AVPlayerItem) === self.player.currentItem else { return }
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self.fail(id: self.currentItem?.id, message: error?.localizedDescription ?? "Playback stopped")
        }
    }

    @objc private func audioInterrupted(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        DispatchQueue.main.async {
            if type == .began {
                self.savePosition()
                self.refreshState()
            } else if let optionsRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt,
                      AVAudioSession.InterruptionOptions(rawValue: optionsRaw).contains(.shouldResume),
                      self.wantsPlay {
                self.player.playImmediately(atRate: self.speed)
            }
        }
    }

    /// Headphones unplugged: pause, like Android's "becoming noisy".
    @objc private func routeChanged(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
        DispatchQueue.main.async { self.pause() }
    }

    private func timeControlChanged() {
        if isPlaying {
            startTick()
        } else {
            stopTick()
            if player.timeControlStatus == .paused { savePosition() }
        }
        refreshState()
        updateNowPlaying()
    }

    // MARK: - Tick, state, events

    private func startTick() {
        guard tickTimer == nil else { return }
        tickTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.refreshState()
            if Date().timeIntervalSince(self.lastSave) >= Self.saveEvery { self.savePosition() }
        }
    }

    private func stopTick() {
        tickTimer?.invalidate()
        tickTimer = nil
    }

    private func refreshState() {
        let item = currentItem
        let next: [String: Any] = [
            "playing": isPlaying,
            "buffering": player.timeControlStatus == .waitingToPlayAtSpecifiedRate,
            "index": currentIndex,
            "count": queue.count,
            "id": Self.orNull(item?.id),
            "title": Self.orNull(item?.title),
            "artist": Self.orNull(item?.artist),
            "artwork": Self.orNull(item?.artwork),
            "type": Self.orNull(item?.type),
            "positionMs": item == nil ? 0 : Int(currentSeconds * 1000),
            "durationMs": item == nil ? 0 : Int(durationSeconds * 1000),
            "shuffle": shuffle,
            "repeat": repeatMode,
            "speed": Double(speed),
            "error": Self.orNull(lastError),
        ]

        stateLock.lock()
        cachedState = next
        stateLock.unlock()

        // Position moves every tick; only the rest counts as a change worth an event.
        var signatureSource = next
        signatureSource.removeValue(forKey: "positionMs")
        let signature = (try? JSONSerialization.data(withJSONObject: signatureSource, options: [.sortedKeys]))
            .flatMap { String(data: $0, encoding: .utf8) } ?? ""
        if signature != lastSignature {
            lastSignature = signature
            dispatch("StateChanged", next)
        }
    }

    static func orNull(_ value: String?) -> Any {
        if let value = value { return value }
        return NSNull()
    }

    /// Events go to PHP on the main queue. Null values are left out so the PHP
    /// listener's own defaults apply (a present null would be cast to "").
    func dispatch(_ event: String, _ payload: [String: Any]) {
        let clean = payload.filter { !($0.value is NSNull) }
        DispatchQueue.main.async {
            LaravelBridge.shared.send?(Self.eventNamespace + event, clean)
        }
    }

    // MARK: - Audio session, lock screen

    private func activateSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: currentItem?.type == "video" ? .moviePlayback : .default)
        try? session.setActive(true)
    }

    private func setUpRemoteCommands() {
        guard !remoteCommandsReady else { return }
        remoteCommandsReady = true
        let center = MPRemoteCommandCenter.shared()

        _ = center.playCommand.addTarget { [weak self] _ in self?.play(); return .success }
        _ = center.pauseCommand.addTarget { [weak self] _ in self?.pause(); return .success }
        _ = center.togglePlayPauseCommand.addTarget { [weak self] _ in self?.toggle(); return .success }
        _ = center.nextTrackCommand.addTarget { [weak self] _ in self?.next(); return .success }
        _ = center.previousTrackCommand.addTarget { [weak self] _ in self?.previous(); return .success }
        _ = center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seek(toMs: Int(event.positionTime * 1000))
            return .success
        }
    }

    private func updateNowPlaying() {
        guard let item = currentItem else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }

        var info: [String: Any] = [
            MPMediaItemPropertyTitle: item.title ?? "",
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentSeconds,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(speed) : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(speed),
            MPNowPlayingInfoPropertyMediaType: item.type == "video"
                ? MPNowPlayingInfoMediaType.video.rawValue
                : MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyPlaybackQueueIndex: currentIndex,
            MPNowPlayingInfoPropertyPlaybackQueueCount: queue.count,
        ]
        if let artist = item.artist { info[MPMediaItemPropertyArtist] = artist }
        if let album = item.album { info[MPMediaItemPropertyAlbumTitle] = album }
        if durationSeconds > 0 { info[MPMediaItemPropertyPlaybackDuration] = durationSeconds }
        if let path = item.artwork, let image = Self.loadImage(path) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private static func loadImage(_ value: String) -> UIImage? {
        if value.hasPrefix("/") { return UIImage(contentsOfFile: value) }
        if let url = URL(string: value), url.isFileURL { return UIImage(contentsOfFile: url.path) }
        return nil // remote artwork isn't fetched for the lock screen
    }

    // MARK: - Resume positions

    private func positions() -> [String: Double] {
        UserDefaults.standard.dictionary(forKey: Self.positionsKey) as? [String: Double] ?? [:]
    }

    private func savedPosition(_ id: String) -> Double {
        positions()[id] ?? 0
    }

    private func savePosition() {
        lastSave = Date()
        guard let id = loadedId, player.currentItem != nil else { return }
        let position = currentSeconds
        let duration = durationSeconds
        var all = positions()
        if duration > 0, duration - position <= Self.endMargin {
            all.removeValue(forKey: id)
        } else if position > 0 {
            all[id] = position
        } else {
            return
        }
        UserDefaults.standard.set(all, forKey: Self.positionsKey)
    }

    private func clearPosition(_ id: String) {
        var all = positions()
        all.removeValue(forKey: id)
        UserDefaults.standard.set(all, forKey: Self.positionsKey)
    }
}
