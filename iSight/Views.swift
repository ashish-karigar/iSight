import SwiftUI
import AVFoundation
import UniformTypeIdentifiers
import QuickLook

private struct AndroidButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 48)
            .padding(.horizontal, 12)
            .foregroundStyle(.black)
            .background(Color(red: 176/255, green: 157/255, blue: 215/255), in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(red: 55/255, green: 0, blue: 179/255), lineWidth: 2))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

struct HomeView: View {
    @EnvironmentObject private var library: CaptureLibrary
    @State private var live = false
    @State private var folderPicker = false
    @State private var creator = false

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                ScrollView {
                    Group {
                        if geometry.size.width > geometry.size.height {
                            HStack(spacing: 32) {
                                welcome.frame(maxWidth: .infinity)
                                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 18) {
                                    actions
                                }.frame(maxWidth: .infinity)
                            }
                        } else {
                            VStack(spacing: 32) {
                                welcome
                                VStack(spacing: 18) { actions }
                            }.frame(maxWidth: 520)
                        }
                    }
                    .padding(24)
                    .frame(maxWidth: .infinity, minHeight: geometry.size.height)
                }
            }
            .background(Color(uiColor: .systemBackground))
            .navigationTitle("i-Sight Med").navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Color(red: 187/255, green: 134/255, blue: 252/255), for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .fullScreenCover(isPresented: $live) { LiveCameraView() }
            .fileImporter(isPresented: $folderPicker, allowedContentTypes: [.folder]) { result in
                switch result {
                case .success(let url):
                    library.selectFolder(url)
                    if library.message == nil { library.message = "Storage location: \(library.folderName)" }
                case .failure(let error): library.message = error.localizedDescription
                }
            }
            .alert("Created by Ashish Karigar", isPresented: $creator) { Button("OK", role: .cancel) {} }
            .alert("Storage", isPresented: Binding(get: { library.message != nil },
                                                   set: { if !$0 { library.message = nil } })) {
                Button("OK") { library.message = nil }
            } message: { Text(library.message ?? "") }
        }
    }

    private var welcome: some View {
        VStack(spacing: 12) {
            Image("BrandLogo").resizable().scaledToFit().frame(width: 120, height: 120)
                .onTapGesture(count: 7) { creator = true }
                .accessibilityLabel("i-Sight Med logo")
            Text("Welcome to i-Sight Med").font(.system(size: 24, weight: .bold))
                .multilineTextAlignment(.center)
        }
    }

    @ViewBuilder private var actions: some View {
        Button("Open Camera Live View") { live = true }.buttonStyle(AndroidButtonStyle())
        Link("Visit Website", destination: URL(string: "https://globalmedventuregroup.com/")!)
            .buttonStyle(AndroidButtonStyle())
        Button("Select Storage Location") { folderPicker = true }.buttonStyle(AndroidButtonStyle())
        Link("Privacy Policy", destination: URL(string: "https://globalmedventuregroup.com/pages/i-sight-med-privacy-policy")!)
            .buttonStyle(AndroidButtonStyle())
    }
}

struct LiveCameraView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var phase
    @EnvironmentObject private var library: CaptureLibrary
    @StateObject private var camera = CameraController()
    @State private var captures = false
    @State private var saving = 0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(session: camera.session, deviceID: camera.selectedID, onRotation: camera.setRotation)
                .ignoresSafeArea()
            if !camera.ready {
                VStack(spacing: 16) {
                    Image(systemName: "video.slash").font(.system(size: 32))
                    Text(camera.status).multilineTextAlignment(.center).frame(maxWidth: 320)
                    if camera.permissionDenied {
                        Button("Open Settings") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                            .buttonStyle(.borderedProminent)
                    }
                }.padding(24).background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 18))
            }
            HStack(spacing: 16) {
                VStack(alignment: .leading) {
                    Menu {
                        Button("Back to Menu") { camera.stop(); dismiss() }
                            .disabled(camera.recording || camera.busy || saving > 0)
                        Button("Captures") { captures = true }.disabled(camera.recording || camera.busy)
                        Section("Camera") {
                            ForEach(camera.cameras) { choice in
                                Button(choice.name) { camera.select(choice.id) }
                                    .disabled(camera.recording || camera.busy)
                            }
                            Button("Refresh cameras") { camera.refreshDevices() }
                                .disabled(camera.recording || camera.busy)
                        }
                        Toggle("Microphone audio", isOn: $camera.microphoneEnabled)
                            .disabled(camera.recording || camera.busy)
                    } label: {
                        Text("Menu").padding(.horizontal, 16).padding(.vertical, 10)
                            .background(.white.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
                    }.accessibilityIdentifier("live-menu")
                    Spacer()
                    if camera.recording {
                        Label("REC", systemImage: "record.circle.fill").foregroundStyle(.red).bold()
                    }
                    Text(saving > 0 ? "Saving…" : camera.status).font(.footnote)
                        .padding(8).background(.black.opacity(0.5))
                }
                Spacer()
                VStack(spacing: 48) {
                    Button { Task { await camera.toggleRecording() } } label: {
                        Image(systemName: camera.recording ? "stop.fill" : "play.fill")
                            .foregroundStyle(camera.recording ? .red : .black)
                            .font(.title2).frame(width: 56, height: 56)
                            .background(.white.opacity(0.5), in: Circle())
                    }
                    .accessibilityLabel(camera.recording ? "Stop recording" : "Start recording")
                    .accessibilityIdentifier("record-video")
                    .disabled((!camera.ready && !camera.recording) || camera.busy)
                    Button { camera.capturePhoto() } label: {
                        Image(systemName: "camera.fill").font(.title2).foregroundStyle(.black)
                            .frame(width: 56, height: 56).background(.white.opacity(0.5), in: Circle())
                    }.accessibilityLabel("Take photo").accessibilityIdentifier("capture-photo")
                        .disabled(!camera.ready || camera.busy || camera.recording)
                }.padding(.horizontal, 8)
            }.padding(16).foregroundStyle(.white)
        }
        .statusBarHidden()
        .onAppear { AppOrientation.setLiveView(true) }
        .task {
            camera.onCapture = { url in
                saving += 1
                Task { await library.save(url); saving -= 1 }
            }
            await camera.start()
        }
        .onDisappear { camera.stop(); AppOrientation.setLiveView(false) }
        .onChange(of: phase) { _, value in
            if value == .background { camera.stop() }
            else if value == .active { Task { await camera.start() } }
        }
        .sheet(isPresented: $captures) { CapturesView() }
        .alert("Camera", isPresented: Binding(get: { camera.message != nil },
                                               set: { if !$0 { camera.message = nil } })) {
            Button("OK") { camera.message = nil }
        } message: { Text(camera.message ?? "") }
        .alert("Capture saved", isPresented: Binding(get: { library.message != nil },
                                                      set: { if !$0 { library.message = nil } })) {
            Button("OK") { library.message = nil }
        } message: { Text(library.message ?? "") }
    }
}

private final class PreviewSurface: UIView {
    var onRotation: ((CGFloat) -> Void)?
    private var deviceID = ""
    private var coordinator: AVCaptureDevice.RotationCoordinator?
    private var observations: [NSKeyValueObservation] = []
    private var previousCaptureAngle: CGFloat?
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

    func selectDevice(_ id: String) {
        guard id != deviceID else { return }
        deviceID = id
        observations.removeAll()
        coordinator = nil
        previousCaptureAngle = nil
        guard let device = AVCaptureDevice(uniqueID: id) else { return }
        let rotation = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: previewLayer)
        coordinator = rotation
        observations = [
            rotation.observe(\.videoRotationAngleForHorizonLevelPreview, options: [.initial, .new]) { [weak self] _, _ in
                Task { @MainActor in self?.applyRotation() }
            },
            rotation.observe(\.videoRotationAngleForHorizonLevelCapture, options: [.initial, .new]) { [weak self] _, _ in
                Task { @MainActor in self?.applyRotation() }
            }
        ]
    }

    override func layoutSubviews() { super.layoutSubviews(); applyRotation() }

    private func applyRotation() {
        guard let coordinator, let connection = previewLayer.connection else { return }
        let previewAngle = coordinator.videoRotationAngleForHorizonLevelPreview
        if connection.isVideoRotationAngleSupported(previewAngle) { connection.videoRotationAngle = previewAngle }
        let captureAngle = coordinator.videoRotationAngleForHorizonLevelCapture
        if previousCaptureAngle != captureAngle {
            previousCaptureAngle = captureAngle
            onRotation?(captureAngle)
        }
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
        }
    }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let deviceID: String
    let onRotation: (CGFloat) -> Void
    func makeUIView(context: Context) -> PreviewSurface {
        let view = PreviewSurface()
        view.previewLayer.session = session
        view.onRotation = onRotation
        view.previewLayer.videoGravity = .resizeAspect
        view.selectDevice(deviceID)
        return view
    }
    func updateUIView(_ view: PreviewSurface, context: Context) {
        view.selectDevice(deviceID)
        view.setNeedsLayout()
    }
}

struct CapturesView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var library: CaptureLibrary
    @State private var selected: URL?

    var body: some View {
        NavigationStack {
            Group {
                if library.files.isEmpty {
                    ContentUnavailableView("No captures yet", systemImage: "photo.on.rectangle",
                                           description: Text("Take a photo or record a video in Live View."))
                } else {
                    List(library.files, id: \.self) { url in
                        HStack {
                            Button { selected = url } label: {
                                Label(url.lastPathComponent, systemImage: url.pathExtension == "jpg" ? "photo" : "video")
                                    .lineLimit(2).font(.footnote)
                            }.buttonStyle(.plain)
                            Spacer()
                            ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }
                                .accessibilityLabel("Share \(url.lastPathComponent)")
                        }
                    }
                }
            }
            .navigationTitle("Captures")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .quickLookPreview($selected)
            .onAppear { library.refresh() }
        }
    }
}
