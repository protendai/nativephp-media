import AVFoundation
import Foundation
import MediaPlayer
import Photos
import UIKit
import UniformTypeIdentifiers

/// Bridge functions for protendai/nativephp-media on iOS. Namespace "Media.*".
///
/// Same functions, parameters, return shapes and events as Android (SPEC.md).
/// Playback commands hop to the main thread and return at once; the result
/// arrives as StateChanged / TrackChanged events and through Media.State.
///
/// iOS differences: Library lists the Music app's downloaded, DRM-free songs
/// (uri "ipod-library://…") and Photos-library videos (uri "ph://<id>"),
/// instead of MediaStore. Those uris play and probe here but aren't files.
enum MediaFunctions {

    class SetQueue: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let items = MediaFunctions.items(parameters["items"])
            guard !items.isEmpty else { return BridgeResponse.success(data: ["ok": false]) }
            let startIndex = MediaFunctions.int(parameters["startIndex"], 0)
            let startPosition = MediaFunctions.int(parameters["startPosition"], -1)
            let play = parameters["play"] as? Bool ?? true
            DispatchQueue.main.async {
                ProtendaiMediaPlayer.shared.setQueue(items, startIndex: startIndex, startPositionMs: startPosition, play: play)
            }
            return BridgeResponse.success(data: ["ok": true])
        }
    }

    class Play: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            MediaFunctions.command { $0.play() }
        }
    }

    class Pause: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            MediaFunctions.command { $0.pause() }
        }
    }

    class Toggle: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            MediaFunctions.command { $0.toggle() }
        }
    }

    class Next: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            MediaFunctions.command { $0.next() }
        }
    }

    class Previous: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            MediaFunctions.command { $0.previous() }
        }
    }

    class SkipTo: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let index = MediaFunctions.int(parameters["index"], -1)
            return MediaFunctions.command { $0.skipTo(index) }
        }
    }

    class Seek: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let position = max(MediaFunctions.int(parameters["position"], 0), 0)
            return MediaFunctions.command { $0.seek(toMs: position) }
        }
    }

    class SetShuffle: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let enabled = parameters["enabled"] as? Bool ?? false
            return MediaFunctions.command { $0.setShuffle(enabled) }
        }
    }

    class SetRepeat: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let mode = parameters["mode"] as? String ?? "off"
            return MediaFunctions.command { $0.setRepeat(mode) }
        }
    }

    class SetSpeed: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let speed = (parameters["speed"] as? NSNumber)?.floatValue ?? 1
            return MediaFunctions.command { $0.setSpeed(speed) }
        }
    }

    class Stop: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            MediaFunctions.command { $0.stop() }
        }
    }

    /// The cached state object; never blocks on the player.
    class State: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: ProtendaiMediaPlayer.shared.state())
        }
    }

    /// Queue the items and show them in the full-screen video player.
    class OpenVideo: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let items = MediaFunctions.items(parameters["items"])
            guard !items.isEmpty else { return BridgeResponse.success(data: ["ok": false]) }
            let startIndex = MediaFunctions.int(parameters["startIndex"], 0)
            let startPosition = MediaFunctions.int(parameters["startPosition"], -1)
            DispatchQueue.main.async {
                ProtendaiMediaPlayer.shared.setQueue(items, startIndex: startIndex, startPositionMs: startPosition, play: true)
                ProtendaiVideoController.present()
            }
            return BridgeResponse.success(data: ["ok": true])
        }
    }

    /// Tags and duration; optionally a JPEG preview (embedded art, a video frame, or the Photos thumbnail) at artworkDest.
    class Probe: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let uri = parameters["uri"] as? String, !uri.isEmpty else {
                return BridgeResponse.success(data: ["ok": false])
            }
            let dest = (parameters["artworkDest"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            return BridgeResponse.success(data: ProtendaiMediaLibrary.probe(uri: uri, artworkDest: dest))
        }
    }

    /// Device media, newest first, paged with limit/offset.
    class Library: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let video = (parameters["type"] as? String) == "video"
            let limit = max(MediaFunctions.int(parameters["limit"], 500), 1)
            let offset = max(MediaFunctions.int(parameters["offset"], 0), 0)
            return BridgeResponse.success(data: video
                ? ProtendaiMediaLibrary.videos(limit: limit, offset: offset)
                : ProtendaiMediaLibrary.songs(limit: limit, offset: offset))
        }
    }

    /// Music library (audio) or Photos (video) access. Result: PermissionResult.
    class RequestPermission: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let type = (parameters["type"] as? String) == "video" ? "video" : "audio"
            if ProtendaiMediaLibrary.granted(type) {
                ProtendaiMediaPlayer.shared.dispatch("PermissionResult", ["granted": true, "type": type])
                return BridgeResponse.success(data: ["ok": true, "granted": true])
            }
            ProtendaiMediaLibrary.request(type) { granted in
                ProtendaiMediaPlayer.shared.dispatch("PermissionResult", ["granted": granted, "type": type])
            }
            return BridgeResponse.success(data: ["ok": true])
        }
    }

    class HasPermission: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let type = (parameters["type"] as? String) == "video" ? "video" : "audio"
            return BridgeResponse.success(data: ["granted": ProtendaiMediaLibrary.granted(type)])
        }
    }

    // MARK: Helpers

    static func command(_ block: @escaping (ProtendaiMediaPlayer) -> Void) -> [String: Any] {
        DispatchQueue.main.async { block(ProtendaiMediaPlayer.shared) }
        return BridgeResponse.success(data: ["ok": true])
    }

    /// Nested params arrive as an array of dictionaries; tolerate a JSON string too.
    static func items(_ value: Any?) -> [ProtendaiMediaItem] {
        var raw: [Any] = []
        if let array = value as? [Any] {
            raw = array
        } else if let string = value as? String,
                  let data = string.data(using: .utf8),
                  let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any] {
            raw = array
        }
        return raw.compactMap { ($0 as? [String: Any]).flatMap(ProtendaiMediaItem.init(json:)) }
    }

    static func int(_ value: Any?, _ fallback: Int) -> Int {
        (value as? NSNumber)?.intValue ?? (value as? String).flatMap(Int.init) ?? fallback
    }
}

// MARK: - Library, probe, permissions

enum ProtendaiMediaLibrary {

    private static let maxArt: CGFloat = 512

    // MARK: Permissions

    static func granted(_ type: String) -> Bool {
        if type == "video" {
            let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            return status == .authorized || status == .limited
        }
        return MPMediaLibrary.authorizationStatus() == .authorized
    }

    static func request(_ type: String, completion: @escaping (Bool) -> Void) {
        if type == "video" {
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                completion(status == .authorized || status == .limited)
            }
        } else {
            MPMediaLibrary.requestAuthorization { status in
                completion(status == .authorized)
            }
        }
    }

    // MARK: Songs (Music app)

    /// Downloaded, DRM-free songs only: streaming/cloud and protected items have no playable assetURL.
    static func songs(limit: Int, offset: Int) -> [String: Any] {
        guard granted("audio") else {
            return ["ok": true, "items": [], "total": 0, "offset": offset]
        }

        let all = (MPMediaQuery.songs().items ?? [])
            .filter { $0.assetURL != nil && !$0.hasProtectedAsset && !$0.isCloudItem }
            .sorted { $0.dateAdded > $1.dateAdded }

        let page = all.dropFirst(offset).prefix(limit).map { song -> [String: Any] in
            let url = song.assetURL!
            return [
                "id": "audio:\(song.persistentID)",
                "uri": url.absoluteString,
                "title": ProtendaiMediaPlayer.orNull(song.title),
                "artist": ProtendaiMediaPlayer.orNull(song.artist),
                "album": ProtendaiMediaPlayer.orNull(song.albumTitle),
                "durationMs": Int(song.playbackDuration * 1000),
                "size": 0,
                "dateAdded": Int(song.dateAdded.timeIntervalSince1970),
                "folder": ProtendaiMediaPlayer.orNull(song.albumTitle),
                "mime": ProtendaiMediaPlayer.orNull(mime(forExtension: url.pathExtension)),
            ]
        }

        return ["ok": true, "items": Array(page), "total": all.count, "offset": offset]
    }

    // MARK: Videos (Photos library)

    static func videos(limit: Int, offset: Int) -> [String: Any] {
        guard granted("video") else {
            return ["ok": true, "items": [], "total": 0, "offset": offset]
        }

        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let result = PHAsset.fetchAssets(with: .video, options: options)
        let total = result.count

        var items: [[String: Any]] = []
        if offset < total {
            let end = min(offset + limit, total)
            for i in offset..<end {
                let asset = result.object(at: i)
                let resource = PHAssetResource.assetResources(for: asset).first
                let name = resource?.originalFilename
                items.append([
                    "id": "video:\(asset.localIdentifier)",
                    "uri": "ph://\(asset.localIdentifier)",
                    "title": ProtendaiMediaPlayer.orNull(name.map { ($0 as NSString).deletingPathExtension }),
                    "artist": NSNull(),
                    "album": NSNull(),
                    "durationMs": Int(asset.duration * 1000),
                    "size": 0,
                    "dateAdded": Int((asset.creationDate ?? Date()).timeIntervalSince1970),
                    "folder": "Photos",
                    "mime": ProtendaiMediaPlayer.orNull(resource.flatMap { UTType($0.uniformTypeIdentifier)?.preferredMIMEType }),
                ])
            }
        }

        return ["ok": true, "items": items, "total": total, "offset": offset]
    }

    // MARK: Probe

    static func probe(uri: String, artworkDest: String?) -> [String: Any] {
        if uri.hasPrefix("ph://") {
            return probePhoto(identifier: String(uri.dropFirst("ph://".count)), artworkDest: artworkDest)
        }
        guard let url = ProtendaiMediaItem.url(for: uri) else { return ["ok": false] }

        let asset = AVURLAsset(url: url)
        let metadata = asset.commonMetadata
        func tag(_ key: AVMetadataKey) -> Any {
            ProtendaiMediaPlayer.orNull(AVMetadataItem.metadataItems(from: metadata, withKey: key, keySpace: .common).first?.stringValue)
        }

        let duration = asset.duration.seconds
        var width = 0
        var height = 0
        let videoTrack = asset.tracks(withMediaType: .video).first
        if let track = videoTrack {
            let size = track.naturalSize.applying(track.preferredTransform)
            width = Int(abs(size.width))
            height = Int(abs(size.height))
        }

        var hasArtwork = false
        if let dest = artworkDest {
            var image: UIImage?
            if let data = AVMetadataItem.metadataItems(from: metadata, withKey: AVMetadataKey.commonKeyArtwork, keySpace: .common).first?.dataValue {
                image = UIImage(data: data)
            } else if videoTrack != nil {
                let generator = AVAssetImageGenerator(asset: asset)
                generator.appliesPreferredTrackTransform = true
                generator.maximumSize = CGSize(width: maxArt, height: maxArt)
                let at = CMTime(seconds: min(1, duration.isFinite ? duration / 2 : 0), preferredTimescale: 600)
                if let cg = try? generator.copyCGImage(at: at, actualTime: nil) {
                    image = UIImage(cgImage: cg)
                }
            }
            if let image = image { hasArtwork = writeJpeg(image, to: dest) }
        }

        return [
            "ok": duration.isFinite || videoTrack != nil,
            "title": tag(.commonKeyTitle),
            "artist": tag(.commonKeyArtist),
            "album": tag(.commonKeyAlbumName),
            "durationMs": duration.isFinite ? Int(duration * 1000) : 0,
            "width": width,
            "height": height,
            "hasArtwork": hasArtwork,
        ]
    }

    private static func probePhoto(identifier: String, artworkDest: String?) -> [String: Any] {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject else {
            return ["ok": false]
        }

        var hasArtwork = false
        if let dest = artworkDest {
            let options = PHImageRequestOptions()
            options.isSynchronous = true
            options.deliveryMode = .highQualityFormat
            options.isNetworkAccessAllowed = true
            options.resizeMode = .fast
            var image: UIImage?
            _ = PHImageManager.default().requestImage(
                for: asset,
                targetSize: CGSize(width: maxArt, height: maxArt),
                contentMode: .aspectFit,
                options: options
            ) { result, _ in image = result }
            if let image = image { hasArtwork = writeJpeg(image, to: dest) }
        }

        let name = PHAssetResource.assetResources(for: asset).first?.originalFilename
        return [
            "ok": true,
            "title": ProtendaiMediaPlayer.orNull(name.map { ($0 as NSString).deletingPathExtension }),
            "artist": NSNull(),
            "album": NSNull(),
            "durationMs": Int(asset.duration * 1000),
            "width": asset.pixelWidth,
            "height": asset.pixelHeight,
            "hasArtwork": hasArtwork,
        ]
    }

    // MARK: Helpers

    private static func writeJpeg(_ image: UIImage, to path: String) -> Bool {
        let longEdge = max(image.size.width, image.size.height)
        var output = image
        if longEdge > maxArt {
            let scale = maxArt / longEdge
            let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
            output = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        }
        guard let data = output.jpegData(compressionQuality: 0.85) else { return false }
        let url = URL(fileURLWithPath: path)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        return (try? data.write(to: url)) != nil
    }

    private static func mime(forExtension ext: String) -> String? {
        ext.isEmpty ? nil : UTType(filenameExtension: ext)?.preferredMIMEType
    }
}
