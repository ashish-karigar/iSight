import SwiftUI
import AVFoundation
import UniformTypeIdentifiers
import QuickLook

struct HomeView: View {
    @EnvironmentObject private var library: CaptureLibrary
    @State private var live = false
    @State private var folderPicker = false
    @State private var captures = false
    @State private var creator = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    Image("BrandLogo")
                        .resizable().scaledToFit().frame(width: 132, height: 132)
                        .onTapGesture(count: 7) { creator = true }
                        .accessibilityLabel("i-Sight Med logo")
                    VStack(spacing: 8) {
                        Text("Welcome to i-Sight Med").font(.largeTitle.bold()).multilineTextAlignment(.center)
                        Text("A closer look. A clearer view.").foregroundStyle(.secondary)
                    }
                    Button { live = true } label: {
                        Label("Open Camera Live View", systemImage: "viewfinder")
                            .frame(maxWidth: .infinity).padding(8)
                    }.buttonStyle(.borderedProminent).controlSize(.large)
                    VStack(spacing: 0) {
                        Button { captures = true } label: { menuRow("Captures", icon: "photo.on.rectangle") }
                        Divider()
                        Button { folderPicker = true } label: { menuRow("Select Storage Location", icon: "folder") }
                        Divider()
                        Link(destination: URL(string: "https://globalmedventuregroup.com/")!) {
                            menuRow("Visit Website", icon: "globe")
                        }
                        Divider()
                        Link(destination: URL(string: "https://globalmedventuregroup.com/pages/i-sight-med-privacy-policy")!) {
                            menuRow("Privacy Policy", icon: "hand.raised")
                        }
                    }
                    .background(.background, in: RoundedRectangle(cornerRadius: 18))
                    VStack(alignment: .leading, spacing: 10) {
                        Label("Saving to \(library.folderName)", systemImage: "externaldrive")
                            .font(.footnote).foregroundStyle(.secondary)
                        Button("Use app storage only") { library.useLocalFolder() }.font(.footnote)
                        Text("Compatible USB cameras are available on supported iPads. On iPhone, use a built-in camera. Captures stay on your device unless you choose a Files folder or share them.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }.padding(28).frame(maxWidth: 560).frame(maxWidth: .infinity)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("i-Sight Med").navigationBarTitleDisplayMode(.inline)
            .fullScreenCover(isPresented: $live) { LiveCameraView() }
            .sheet(isPresented: $captures) { CapturesView() }
            .fileImporter(isPresented: $folderPicker, allowedContentTypes: [.folder]) { result in
                switch result {
                case .success(let url): library.selectFolder(url)
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

    private func menuRow(_ title: String, icon: String) -> some View {
        HStack {
            Label(title, systemImage: icon)
            Spacer()
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
        }.padding(18).contentShape(Rectangle())
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
        GeometryReader { geometry in
            ZStack {
                Color.black.ignoresSafeArea()
                CameraPreview(session: camera.session, onRotation: camera.setRotation).ignoresSafeArea()
                if !camera.ready {
                    VStack(spacing: 16) {
                        Image(systemName: "video.slash").font(.system(size: 42))
                        Text(camera.status).multilineTextAlignment(.center).frame(maxWidth: 380)
                        if camera.permissionDenied {
                            Button("Open Settings") {
                                UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
                            }.buttonStyle(.borderedProminent)
                        }
                    }.padding(24).background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 18))
                }
                VStack {
                    HStack {
                        Button { camera.stop(); dismiss() } label: { Label("Menu", systemImage: "chevron.left") }
                            .disabled(camera.recording || camera.busy || saving > 0)
                        Spacer()
                        if camera.recording {
                            Label("REC", systemImage: "record.circle.fill").foregroundStyle(.red).bold()
                        }
                        Menu {
                            ForEach(camera.cameras) { choice in
                                Button {
                                    camera.select(choice.id)
                                } label: {
                                    Label(choice.name, systemImage: choice.external ? "cable.connector" : "camera")
                                }
                            }
                            Button("Refresh cameras", systemImage: "arrow.clockwise") { camera.refreshDevices() }
                        } label: { Label("Camera", systemImage: "camera.rotate") }
                        .disabled(camera.recording || camera.busy)
                    }.padding().background(.black.opacity(0.65))
                    Spacer()
                    HStack(alignment: .bottom) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(camera.status).font(.footnote)
                            Text(saving > 0 ? "Saving capture…" : "Photos are snapshots of the live video feed.")
                                .font(.caption).foregroundStyle(.white.opacity(0.7))
                        }
                        Spacer()
                        if geometry.size.width > geometry.size.height { controls }
                    }.padding().background(.black.opacity(0.65))
                    if geometry.size.width <= geometry.size.height { controls.padding().background(.black.opacity(0.65)) }
                }.foregroundStyle(.white)
            }
        }
        .task {
            camera.onCapture = { url in
                saving += 1
                Task { await library.save(url); saving -= 1 }
            }
            await camera.start()
        }
        .onDisappear { camera.stop() }
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

    private var controls: some View {
        HStack(spacing: 24) {
            Button { captures = true } label: { Image(systemName: "photo.on.rectangle").font(.title2) }
                .accessibilityLabel("View saved captures").disabled(camera.recording || camera.busy)
            Button { camera.capturePhoto() } label: {
                Image(systemName: "camera.fill").font(.title2).frame(width: 58, height: 58)
                    .background(.white, in: Circle()).foregroundStyle(.black)
            }.accessibilityLabel("Take photo").disabled(!camera.ready || camera.busy || camera.recording)
            Button { Task { await camera.toggleRecording() } } label: {
                Image(systemName: camera.recording ? "stop.fill" : "record.circle.fill")
                    .font(.system(size: 44)).foregroundStyle(.red).frame(width: 58, height: 58)
            }.accessibilityLabel(camera.recording ? "Stop recording" : "Start recording")
                .disabled((!camera.ready && !camera.recording) || camera.busy)
            Toggle(isOn: $camera.microphoneEnabled) {
                Image(systemName: camera.microphoneEnabled ? "mic.fill" : "mic.slash.fill")
            }
                .toggleStyle(.button).accessibilityLabel("Record microphone audio")
                .fixedSize().disabled(camera.recording || camera.busy)
        }
    }
}

private final class PreviewSurface: UIView {
    var onRotation: ((CGFloat) -> Void)?
    private var previousAngle: CGFloat?
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard let connection = previewLayer.connection else { return }
        let angle: CGFloat
        switch window?.windowScene?.interfaceOrientation {
        case .landscapeLeft: angle = 0
        case .landscapeRight: angle = 180
        case .portraitUpsideDown: angle = 270
        default: angle = 90
        }
        if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
        if previousAngle != angle {
            previousAngle = angle
            onRotation?(angle)
        }
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = false
        }
    }
}

private struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    let onRotation: (CGFloat) -> Void
    func makeUIView(context: Context) -> PreviewSurface {
        let view = PreviewSurface()
        view.previewLayer.session = session
        view.onRotation = onRotation
        view.previewLayer.videoGravity = .resizeAspect
        return view
    }
    func updateUIView(_ uiView: PreviewSurface, context: Context) { uiView.setNeedsLayout() }
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
