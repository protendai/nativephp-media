import AVKit
import UIKit

/// Full-screen video for Media.OpenVideo: AVPlayerViewController bound to the
/// shared ProtendaiMediaPlayer (no second player), with picture-in-picture.
/// iOS's own controls give scrubbing, ±skip, speed and PiP; leaving the
/// screen (not into PiP) pauses, like Android's VideoActivity.
final class ProtendaiVideoController: AVPlayerViewController, AVPlayerViewControllerDelegate {

    /// Kept alive while in PiP, when the controller is no longer presented.
    private static var current: ProtendaiVideoController?

    private var inPictureInPicture = false

    /// Queue ids this player screen opened: leaving it only pauses while the
    /// shared player still holds one of them (music started meanwhile keeps playing).
    private var openedIds: Set<String> = []

    private var ownsPlayback: Bool {
        guard let id = ProtendaiMediaPlayer.shared.currentId else { return false }
        return openedIds.isEmpty || openedIds.contains(id)
    }

    static func present(ids: Set<String> = []) {
        DispatchQueue.main.async {
            if let existing = current {
                existing.openedIds = ids
                // Already on screen: the new queue plays in it.
                if existing.presentingViewController != nil { return }
                // In picture-in-picture: bring the same controller back full screen
                // instead of stacking a second player on top of the PiP one.
                if existing.inPictureInPicture {
                    topViewController()?.present(existing, animated: true)
                    return
                }
                current = nil
            }

            let controller = ProtendaiVideoController()
            controller.openedIds = ids
            controller.player = ProtendaiMediaPlayer.shared.player
            controller.delegate = controller
            controller.allowsPictureInPicturePlayback = true
            controller.canStartPictureInPictureAutomaticallyFromInline = true
            controller.updatesNowPlayingInfoCenter = false // the engine owns the lock screen
            controller.modalPresentationStyle = .fullScreen
            current = controller

            topViewController()?.present(controller, animated: true)
        }
    }

    static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let next = top?.presentedViewController { top = next }
        return top
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        guard isBeingDismissed || presentingViewController == nil else { return }
        if !inPictureInPicture {
            if ownsPlayback { ProtendaiMediaPlayer.shared.pause() }
            Self.current = nil
        }
    }

    // MARK: AVPlayerViewControllerDelegate

    func playerViewControllerWillStartPictureInPicture(_ playerViewController: AVPlayerViewController) {
        inPictureInPicture = true
    }

    func playerViewControllerDidStopPictureInPicture(_ playerViewController: AVPlayerViewController) {
        inPictureInPicture = false
        if presentingViewController == nil { Self.current = nil }
    }

    /// Dismiss on PiP start so the app is usable behind the floating video.
    func playerViewControllerShouldAutomaticallyDismissAtPictureInPictureStart(_ playerViewController: AVPlayerViewController) -> Bool {
        true
    }

    /// Tapping "back to app" in the PiP window brings the full-screen player back.
    func playerViewController(
        _ playerViewController: AVPlayerViewController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void
    ) {
        if playerViewController.presentingViewController != nil {
            completionHandler(true)
            return
        }
        guard let top = Self.topViewController() else {
            completionHandler(false)
            return
        }
        top.present(playerViewController, animated: true) { completionHandler(true) }
    }
}
