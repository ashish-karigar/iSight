# i-Sight Med for iOS and iPadOS

Native SwiftUI + AVFoundation counterpart to the Android app. Open `iSight.xcodeproj` in Xcode, select the **iSight** scheme, then choose an iPhone/iPad simulator or connected device. Requires iOS/iPadOS 17 or newer and Xcode 15 or newer. No third-party packages.

## Features

- Welcome menu with the existing branding, website, privacy-policy link, and creator credit (tap the logo seven times).
- Camera selection, live preview, JPEG snapshots of the video feed, and MOV recording with optional microphone audio (off by default).
- Built-in cameras on iPhone/iPad; UVC external-camera discovery on supported iPads, preferring an external camera when first opened.
- Local Photos/Videos folders, a captures list with Quick Look and sharing, and optional export to a folder selected in Files using a persisted security-scoped bookmark.
- Camera/microphone permission handling, Settings shortcut, disconnect/interruption handling, and stopping capture on backgrounding.

## USB camera compatibility

Apple documents external UVC capture on iPadOS 17+ with supported hardware, such as USB-C iPads. iPhone USB UVC parity is **not promised**; the iPhone app uses built-in cameras. A camera working on Android does not establish iPad compatibility. Test your actual camera, adapter, and power supply on a physical iPad. The app uses Apple's drivers; it does not port Android's libusb/NDK stack.

References: [Apple external-camera documentation](https://developer.apple.com/documentation/avfoundation/avcapturedevice/devicetype-swift.struct/external), [Support external cameras in your iPadOS app](https://developer.apple.com/videos/play/wwdc2023/10106/).

## Storage and capture behavior

Every completed capture is saved in the app's Documents/Captures/Photos or Videos directory. These originals are visible in Files under the app and in the Captures screen. Selecting another Files folder adds a copy there; failure leaves the local original available for sharing. Uninstalling the app removes its local files. Recordings are capped at one hour and stop when disk space is low. Photos are live-frame snapshots, not full-resolution still-camera captures. Video orientation is set before recording; hold the device in that orientation while recording.

Camera switching and leaving Live View are disabled during recording. Stop recording before returning to the menu. External-camera disconnects stop the session without silently switching to a built-in camera. Reconnect and select the camera again. Microphone permission is requested only when recording with audio enabled; denying it produces silent video. Simulator builds can verify UI and storage but cannot establish camera compatibility.

## Run on a physical device

1. Open `iSight.xcodeproj` and select the app target's **Signing & Capabilities** tab.
2. Choose your Apple development team. Change the bundle ID if it is not available for your team (default: `com.isightmed.gmvg.camera`).
3. Select your connected iPhone/iPad, enable Developer Mode if prompted, and Run.
4. Grant camera access. On iPad, connect your UVC camera before opening Live View, or use the Camera menu to select it after connecting.

Version is **1.0.1**, build **2**, independently of Android's Play uploads. For TestFlight/App Store delivery, configure your Apple Developer team, review the existing privacy-policy link for iOS accuracy, complete App Store privacy disclosures, validate device behavior, then use **Product → Archive → Distribute App**. An iOS build cannot be distributed like an unsigned APK.

## Build without signing

```sh
xcodebuild -project iSight.xcodeproj -scheme iSight \
  -sdk iphonesimulator -configuration Debug \
  -derivedDataPath /tmp/isight-ios CODE_SIGNING_ALLOWED=NO build

xcodebuild -project iSight.xcodeproj -scheme iSight \
  -destination 'generic/platform=iOS' -configuration Release \
  -derivedDataPath /tmp/isight-ios-device CODE_SIGNING_ALLOWED=NO build
```

## Hardware acceptance checks

The simulator Debug build and unsigned device Release build were compiled with Xcode 27.1. The app was launched on iPhone and iPad simulators; home-screen previews are in `verification/`. Three storage tests cover local saving, selected-folder copying, and preservation of local captures after export failure. Run them with:

```sh
xcodebuild -project iSight.xcodeproj -scheme iSight \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' \
  -derivedDataPath /tmp/isight-ios CODE_SIGNING_ALLOWED=NO test
```

Choose an available simulator name on your Mac. Camera capture and the following hardware behaviors still require validation:

- Permission allowed/denied and reenabled from Settings; no camera in simulator.
- Built-in front/rear camera selection on iPhone and iPad.
- UVC preview, unplug/reconnect, and unsupported camera behavior on the actual iPad.
- Portrait/landscape preview, matching saved JPEG/video orientation, and an uninterrupted recording.
- Silent/audio videos, microphone denial, backgrounding, low storage, and session interruption.
- JPEG/MOV playback and sharing; selected Files folder after relaunch; disconnected provider leaves the local original intact.
