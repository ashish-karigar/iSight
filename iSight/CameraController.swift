import AVFoundation
import Combine
import CoreImage
import UIKit

struct CameraChoice: Identifiable, Equatable {
    let id: String
    let name: String
    let external: Bool
}

@MainActor
final class CameraController: ObservableObject {
    @Published var cameras: [CameraChoice] = []
    @Published var selectedID = ""
    @Published var status = "Camera is stopped"
    @Published var ready = false
    @Published var recording = false
    @Published var busy = false
    @Published var permissionDenied = false
    @Published var microphoneEnabled = false
    @Published var message: String?
    var onCapture: ((URL) -> Void)?

    private let engine = CaptureEngine()
    private var observers: [NSObjectProtocol] = []
    private var active = false
    private var permissionRequest = false
    var session: AVCaptureSession { engine.session }

    init() {
        engine.event = { [weak self] event in
            Task { @MainActor [weak self] in
                guard let self else { return }
                switch event {
                case .ready(let name):
                    guard self.active else { return }
                    self.ready = true
                    self.status = name
                case .stopped(let reason):
                    self.ready = false
                    self.status = reason
                case .recording(let value):
                    self.recording = value
                    if value { self.busy = false }
                case .captured(let url):
                    self.busy = false
                    self.onCapture?(url)
                case .failed(let text):
                    self.busy = false
                    self.message = text
                }
            }
        }
        let center = NotificationCenter.default
        for name in [AVCaptureDevice.wasConnectedNotification, AVCaptureDevice.wasDisconnectedNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor [weak self] in self?.refreshDevices() }
            })
        }
        observers.append(center.addObserver(forName: AVCaptureSession.wasInterruptedNotification,
                                            object: engine.session, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.ready = false
                self?.status = "Camera interrupted. Return to the menu and reopen Live View."
            }
        })
        observers.append(center.addObserver(forName: AVCaptureSession.runtimeErrorNotification,
                                            object: engine.session, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.ready = false
                self?.status = "Camera stopped unexpectedly. Reopen Live View to retry."
            }
        })
    }

    deinit { observers.forEach(NotificationCenter.default.removeObserver) }

    func start() async {
        active = true
        guard !permissionRequest else { return }
        permissionRequest = true
        defer { permissionRequest = false }
        let allowed = await AVCaptureDevice.requestAccess(for: .video)
        guard active else { return }
        permissionDenied = !allowed
        guard allowed else {
            status = "Camera access is off. Enable it in Settings."
            return
        }
        refreshDevices()
        if selectedID.isEmpty {
            selectedID = cameras.first(where: \.external)?.id ?? cameras.first?.id ?? ""
        }
        guard !selectedID.isEmpty else {
            status = "No camera available. Connect a compatible camera on iPad, or use a physical device."
            return
        }
        select(selectedID)
    }

    func refreshDevices() {
        var types: [AVCaptureDevice.DeviceType] = [.builtInWideAngleCamera]
        if UIDevice.current.userInterfaceIdiom == .pad { types.append(.external) }
        cameras = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: .unspecified)
            .devices.map { CameraChoice(id: $0.uniqueID, name: $0.localizedName, external: $0.deviceType == .external) }
        if !selectedID.isEmpty && !cameras.contains(where: { $0.id == selectedID }) {
            selectedID = ""
            ready = false
            engine.stop()
            status = "Camera disconnected. Select a camera to continue."
        }
    }

    func select(_ id: String) {
        guard active, !recording, !busy else { return }
        selectedID = id
        ready = false
        status = "Connecting…"
        engine.configure(id: id)
    }

    func stop() {
        active = false
        ready = false
        engine.stop()
    }

    func capturePhoto() {
        guard ready, !busy else { return }
        busy = true
        engine.capturePhoto()
    }

    func setRotation(_ angle: CGFloat) { engine.setRotation(angle) }

    func toggleRecording() async {
        guard (ready || recording), !busy else { return }
        busy = true
        if recording {
            engine.stopRecording()
        } else {
            var audio = false
            if microphoneEnabled {
                audio = await AVCaptureDevice.requestAccess(for: .audio)
                if !audio { message = "Microphone access is off. This video will be silent." }
            }
            guard active, ready else { busy = false; return }
            engine.startRecording(audio: audio)
        }
    }
}

/// All mutable AVFoundation state and frame processing belongs to this serial queue.
/// The preview layer only reads `session`; UI events are delivered to the main actor.
private final class CaptureEngine: NSObject, @unchecked Sendable,
    AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureFileOutputRecordingDelegate {
    enum Event: Sendable {
        case ready(String), stopped(String), recording(Bool), captured(URL), failed(String)
    }
    let session = AVCaptureSession()
    var event: (@Sendable (Event) -> Void)?
    private let queue = DispatchQueue(label: "com.isightmed.capture", qos: .userInitiated)
    private let frames = AVCaptureVideoDataOutput()
    private let movie = AVCaptureMovieFileOutput()
    private let context = CIContext()
    private var lastFrame: CVPixelBuffer?
    private var audioInput: AVCaptureDeviceInput?
    private var rotation: CGFloat = 90

    func setRotation(_ angle: CGFloat) {
        queue.async {
            self.rotation = angle
            guard !self.movie.isRecording else { return }
            for connection in [self.frames.connection(with: .video), self.movie.connection(with: .video)] {
                if connection?.isVideoRotationAngleSupported(angle) == true { connection?.videoRotationAngle = angle }
            }
        }
    }

    func configure(id: String) {
        queue.async {
            guard !self.movie.isRecording else { return }
            self.session.stopRunning()
            self.lastFrame = nil
            self.session.beginConfiguration()
            self.session.inputs.forEach { self.session.removeInput($0) }
            self.session.outputs.forEach { self.session.removeOutput($0) }
            self.audioInput = nil
            do {
                guard let device = AVCaptureDevice(uniqueID: id) else {
                    throw CaptureError.unavailable
                }
                let input = try AVCaptureDeviceInput(device: device)
                guard self.session.canAddInput(input) else { throw CaptureError.unavailable }
                self.session.addInput(input)
                if self.session.canSetSessionPreset(.high) { self.session.sessionPreset = .high }
                self.frames.alwaysDiscardsLateVideoFrames = true
                self.frames.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
                self.frames.setSampleBufferDelegate(self, queue: self.queue)
                guard self.session.canAddOutput(self.frames) else { throw CaptureError.unsupported }
                self.session.addOutput(self.frames)
                guard self.session.canAddOutput(self.movie) else { throw CaptureError.unsupported }
                self.session.addOutput(self.movie)
                for connection in [self.frames.connection(with: .video), self.movie.connection(with: .video)] {
                    if connection?.isVideoRotationAngleSupported(self.rotation) == true {
                        connection?.videoRotationAngle = self.rotation
                    }
                    if connection?.isVideoMirroringSupported == true {
                        connection?.automaticallyAdjustsVideoMirroring = false
                        connection?.isVideoMirrored = false
                    }
                }
                self.session.commitConfiguration()
                self.session.startRunning()
                if self.session.isRunning { self.event?(.ready(device.localizedName)) }
                else { self.event?(.stopped("Camera could not start. Reopen Live View to retry.")) }
            } catch {
                self.session.commitConfiguration()
                self.event?(.stopped(error.localizedDescription))
            }
        }
    }

    func stop() {
        queue.async {
            if self.movie.isRecording { self.movie.stopRecording() }
            self.session.stopRunning()
            self.lastFrame = nil
            self.event?(.stopped("Camera is stopped"))
        }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        lastFrame = CMSampleBufferGetImageBuffer(sampleBuffer)
    }

    func capturePhoto() {
        queue.async {
            guard let frame = self.lastFrame, self.session.isRunning else {
                self.event?(.failed("No video frame yet. Wait for the preview, then try again."))
                return
            }
            let url = Self.temporaryURL(extension: "jpg")
            do {
                try self.context.writeJPEGRepresentation(of: CIImage(cvPixelBuffer: frame), to: url,
                    colorSpace: CGColorSpaceCreateDeviceRGB(), options: [:])
                self.event?(.captured(url))
            } catch { self.event?(.failed("Photo capture failed: \(error.localizedDescription)")) }
        }
    }

    func startRecording(audio: Bool) {
        queue.async {
            guard self.session.isRunning, !self.movie.isRecording else {
                self.event?(.failed("Camera is not ready to record."))
                return
            }
            self.session.beginConfiguration()
            if let input = self.audioInput { self.session.removeInput(input); self.audioInput = nil }
            if audio, let device = AVCaptureDevice.default(for: .audio) {
                do {
                    let input = try AVCaptureDeviceInput(device: device)
                    if self.session.canAddInput(input) {
                        self.session.addInput(input)
                        self.audioInput = input
                    } else { self.event?(.failed("Microphone unavailable. Recording silent video.")) }
                } catch { self.event?(.failed("Microphone unavailable. Recording silent video.")) }
            }
            self.session.commitConfiguration()
            self.movie.maxRecordedDuration = CMTime(seconds: 3600, preferredTimescale: 600)
            self.movie.minFreeDiskSpaceLimit = 100 * 1024 * 1024
            self.movie.startRecording(to: Self.temporaryURL(extension: "mov"), recordingDelegate: self)
        }
    }

    func stopRecording() {
        queue.async {
            if self.movie.isRecording { self.movie.stopRecording() }
            else { self.event?(.failed("Recording has already stopped.")) }
        }
    }

    func fileOutput(_ output: AVCaptureFileOutput, didStartRecordingTo fileURL: URL,
                    from connections: [AVCaptureConnection]) { event?(.recording(true)) }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                    from connections: [AVCaptureConnection], error: Error?) {
        queue.async {
            // Release microphone capture as soon as recording has finalized.
            if let input = self.audioInput {
                self.session.beginConfiguration()
                self.session.removeInput(input)
                self.audioInput = nil
                self.session.commitConfiguration()
            }
            // Apply any orientation change deferred while the movie was recording.
            for connection in [self.frames.connection(with: .video), self.movie.connection(with: .video)] {
                if connection?.isVideoRotationAngleSupported(self.rotation) == true {
                    connection?.videoRotationAngle = self.rotation
                }
            }
            self.event?(.recording(false))
            let nsError = error as NSError?
            if error == nil || nsError?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool == true {
                self.event?(.captured(outputFileURL))
            } else {
                self.event?(.failed("Recording failed: \(error!.localizedDescription)"))
            }
        }
    }

    private static func temporaryURL(extension ext: String) -> URL {
        let date = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-")
        return FileManager.default.temporaryDirectory
            .appendingPathComponent("iSight_\(date)_\(UUID().uuidString.prefix(8)).\(ext)")
    }

    private enum CaptureError: LocalizedError {
        case unavailable, unsupported
        var errorDescription: String? {
            switch self {
            case .unavailable: "Camera unavailable. Reconnect it or select another camera."
            case .unsupported: "This camera does not support the required preview and recording outputs."
            }
        }
    }
}
