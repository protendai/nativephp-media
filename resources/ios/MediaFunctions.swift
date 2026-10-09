import Foundation

// iOS is not implemented yet: every function reports "unsupported" so apps
// can check State()/Library() and hide the player. State returns the idle
// state object so UI code reading it needs no special case.
enum MediaFunctions {

    static let idleState: [String: Any] = [
        "playing": false, "buffering": false, "index": -1, "count": 0,
        "id": NSNull(), "title": NSNull(), "artist": NSNull(), "artwork": NSNull(), "type": NSNull(),
        "positionMs": 0, "durationMs": 0, "shuffle": false, "repeat": "off", "speed": 1.0, "error": NSNull(),
    ]

    class Unsupported: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: ["ok": false, "reason": "unsupported"])
        }
    }

    class SetQueue: Unsupported {}
    class Play: Unsupported {}
    class Pause: Unsupported {}
    class Toggle: Unsupported {}
    class Next: Unsupported {}
    class Previous: Unsupported {}
    class SkipTo: Unsupported {}
    class Seek: Unsupported {}
    class SetShuffle: Unsupported {}
    class SetRepeat: Unsupported {}
    class SetSpeed: Unsupported {}
    class Stop: Unsupported {}
    class OpenVideo: Unsupported {}
    class Probe: Unsupported {}
    class RequestPermission: Unsupported {}

    class State: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: MediaFunctions.idleState)
        }
    }

    class Library: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: ["ok": false, "items": []])
        }
    }

    class HasPermission: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            return BridgeResponse.success(data: ["granted": false])
        }
    }
}
