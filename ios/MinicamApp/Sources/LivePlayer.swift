import AVFoundation
import CoreMedia
import MinicamKit
import SwiftUI
import UIKit
import VideoToolbox

/// True if the device can decode every sample MIME type (`video/hevc`, …).
@Sendable func canDecode(_ mimes: [String]) -> Bool {
    mimes.allSatisfy { mime in
        switch mime {
        case "video/avc", "audio/mp4a-latm":
            return true
        case "video/hevc":
            #if targetEnvironment(simulator)
            // The simulator decodes HEVC in software.
            return true
            #else
            return VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)
            #endif
        default:
            return false
        }
    }
}

/// Plays a camera's live fMP4 (ADR 0006): each fragment's samples become `CMSampleBuffer`s for an
/// `AVSampleBufferDisplayLayer` (and an `AVSampleBufferAudioRenderer` while audio is on) on one
/// synchronizer, which starts at the first keyframe and speeds up while behind live.
@MainActor
@Observable
final class LivePlayer {
    @ObservationIgnored let displayLayer = AVSampleBufferDisplayLayer()
    @ObservationIgnored private let audioRenderer = AVSampleBufferAudioRenderer()
    @ObservationIgnored private let synchronizer = AVSampleBufferRenderSynchronizer()
    @ObservationIgnored private var tracks: [Int: (track: Fmp4.Track, format: CMFormatDescription)] = [:]
    @ObservationIgnored private var started = false
    @ObservationIgnored private var newest = CMTime.invalid
    @ObservationIgnored private var audioAttached = false
    @ObservationIgnored private var audioOn = false
    /// A frame has been shown (the last one stays up while the stream restarts).
    private(set) var hasPicture = false
    /// The decoder gave up: the view switches to MJPEG.
    private(set) var failed = false

    init() {
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = UIColor.black.cgColor
        synchronizer.addRenderer(displayLayer)
    }

    /// Starts playing a new stream from its init segment; throws if it's malformed or unplayable.
    func start(initSegment: Data, audio: Bool) throws {
        var formats: [Int: (track: Fmp4.Track, format: CMFormatDescription)] = [:]
        for track in try Fmp4.parseInit(initSegment) {
            if let format = Self.formatDescription(track) { formats[track.id] = (track, format) }
        }
        guard formats.values.contains(where: { $0.track.kind == .video }) else {
            throw Fmp4Error(reason: "No playable video track")
        }
        reset()
        tracks = formats
        setAudio(audio && formats.values.contains { $0.track.kind == .audio })
    }

    /// Enqueues one fragment's samples; a malformed fragment is dropped.
    func append(_ fragment: Data) {
        guard !tracks.isEmpty, let trafs = try? Fmp4.parseFragment(fragment) else { return }
        for traf in trafs {
            guard let (track, format) = tracks[traf.trackID] else { continue }
            for sample in traf.samples {
                guard let buffer = Self.sampleBuffer(sample, track: track, format: format) else { continue }
                let time = CMSampleBufferGetPresentationTimeStamp(buffer)
                switch track.kind {
                case .video:
                    // Decoding can only start at a keyframe.
                    if !started {
                        guard sample.isSync else { continue }
                        synchronizer.setRate(1, time: time)
                        started = true
                    }
                    displayLayer.sampleBufferRenderer.enqueue(buffer)
                    newest = newest.isValid ? max(newest, time) : time
                    if !hasPicture { hasPicture = true }
                case .audio where audioOn && started:
                    audioRenderer.enqueue(buffer)
                default:
                    break
                }
            }
        }
        checkStatus()
    }

    /// Call regularly while playing: speeds up while more than a second is buffered.
    func catchUp() {
        guard started, newest.isValid else { return }
        let behind = CMTimeGetSeconds(CMTimeSubtract(newest, synchronizer.currentTime()))
        let rate = LiveCatchUp.speed(bufferedMillis: Int(behind * 1_000), current: synchronizer.rate)
        if rate != synchronizer.rate { synchronizer.rate = rate }
        checkStatus()
    }

    func stop() {
        reset()
        setAudio(false)
    }

    private func reset() {
        synchronizer.rate = 0
        displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: false, completionHandler: nil)
        audioRenderer.flush()
        tracks = [:]
        started = false
        newest = .invalid
    }

    private func checkStatus() {
        if displayLayer.sampleBufferRenderer.status == .failed, !failed { failed = true }
    }

    /// The audio renderer joins the synchronizer, and the app plays sound, only while audio is on.
    private func setAudio(_ on: Bool) {
        let session = AVAudioSession.sharedInstance()
        if on, !audioAttached {
            synchronizer.addRenderer(audioRenderer)
            audioAttached = true
            try? session.setCategory(.playback, mode: .moviePlayback)
            try? session.setActive(true)
        } else if !on, audioAttached {
            synchronizer.removeRenderer(audioRenderer, at: .zero, completionHandler: nil)
            audioAttached = false
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
        }
        audioOn = on
    }

    // MARK: - Core Media

    private static func formatDescription(_ track: Fmp4.Track) -> CMFormatDescription? {
        var format: CMFormatDescription?
        switch (track.kind, track.codec) {
        case (.video, "avc1"), (.video, "avc3"), (.video, "hvc1"), (.video, "hev1"):
            guard !track.config.isEmpty else { return nil }
            let isHEVC = track.codec.hasPrefix("h")
            let atoms = [isHEVC ? "hvcC" : "avcC": track.config] as CFDictionary
            let extensions = [kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms: atoms] as CFDictionary
            CMVideoFormatDescriptionCreate(
                allocator: kCFAllocatorDefault,
                codecType: isHEVC ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264,
                width: Int32(clamping: track.width), height: Int32(clamping: track.height),
                extensions: extensions, formatDescriptionOut: &format
            )
        case (.audio, "mp4a"):
            guard !track.config.isEmpty, track.sampleRate > 0, track.channels > 0 else { return nil }
            var description = AudioStreamBasicDescription(
                mSampleRate: Float64(track.sampleRate), mFormatID: kAudioFormatMPEG4AAC, mFormatFlags: 0,
                mBytesPerPacket: 0, mFramesPerPacket: 1_024, mBytesPerFrame: 0,
                mChannelsPerFrame: UInt32(track.channels), mBitsPerChannel: 0, mReserved: 0
            )
            // The esds ES descriptor is AAC's magic cookie.
            track.config.withUnsafeBytes { cookie in
                _ = CMAudioFormatDescriptionCreate(
                    allocator: kCFAllocatorDefault, asbd: &description, layoutSize: 0, layout: nil,
                    magicCookieSize: cookie.count, magicCookie: cookie.baseAddress, extensions: nil,
                    formatDescriptionOut: &format
                )
            }
        default:
            return nil
        }
        return format
    }

    private static func sampleBuffer(
        _ sample: Fmp4.Sample, track: Fmp4.Track, format: CMFormatDescription
    ) -> CMSampleBuffer? {
        let size = sample.data.count
        var block: CMBlockBuffer?
        guard size > 0, CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: size,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
            dataLength: size, flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block
        ) == noErr, let block else { return nil }
        let copied = sample.data.withUnsafeBytes { bytes in
            CMBlockBufferReplaceDataBytes(with: bytes.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: size)
        }
        guard copied == noErr else { return nil }

        let timescale = CMTimeScale(track.timescale)
        let presentation = CMTime(value: sample.decodeTime + sample.compositionOffset, timescale: timescale)
        var buffer: CMSampleBuffer?
        if track.kind == .audio {
            var packet = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(size))
            CMAudioSampleBufferCreateReadyWithPacketDescriptions(
                allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1,
                presentationTimeStamp: presentation, packetDescriptions: &packet, sampleBufferOut: &buffer
            )
            return buffer
        }
        var timing = CMSampleTimingInfo(
            duration: CMTime(value: sample.duration, timescale: timescale),
            presentationTimeStamp: presentation,
            decodeTimeStamp: CMTime(value: sample.decodeTime, timescale: timescale)
        )
        var sampleSize = size
        CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: 1,
            sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize, sampleBufferOut: &buffer
        )
        if let buffer, !sample.isSync,
           let attachments = CMSampleBufferGetSampleAttachmentsArray(buffer, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let attachment = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(
                attachment,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        }
        return buffer
    }
}

/// Hosts the player's display layer; the regular and fullscreen layouts each make one, and the
/// layer moves to whichever is on screen.
struct LiveVideoView: UIViewRepresentable {
    let player: LivePlayer

    func makeUIView(context: Context) -> LayerHostView {
        let view = LayerHostView()
        view.backgroundColor = .black
        view.hosted = player.displayLayer
        return view
    }

    func updateUIView(_ view: LayerHostView, context: Context) {
        if view.hosted !== player.displayLayer || player.displayLayer.superlayer !== view.layer {
            view.hosted = player.displayLayer
        }
    }

    static func dismantleUIView(_ view: LayerHostView, coordinator: ()) {
        if view.hosted?.superlayer === view.layer { view.hosted?.removeFromSuperlayer() }
        view.hosted = nil
    }

    final class LayerHostView: UIView {
        var hosted: CALayer? {
            didSet {
                guard let hosted else { return }
                layer.addSublayer(hosted)
                setNeedsLayout()
            }
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            hosted?.frame = bounds
            CATransaction.commit()
        }
    }
}

/// Detection boxes of an fMP4 stream, which can't be drawn into the video (spec 009 AC7): the
/// class colour, the label and confidence on a tab above the box, like the server's MJPEG boxes.
struct DetectionOverlay: View {
    let detections: Detections

    var body: some View {
        Canvas { context, size in
            for box in detections.boxes {
                let fitted = box.fit(
                    frameWidth: detections.width, frameHeight: detections.height,
                    viewWidth: size.width, viewHeight: size.height
                )
                let rect = CGRect(x: fitted.left, y: fitted.top, width: fitted.width, height: fitted.height)
                let color = UInt32(box.color.trimmingCharacters(in: ["#"]), radix: 16).map { Color(hex: $0) } ?? Theme.accent
                context.stroke(Path(rect), with: .color(color), lineWidth: 2)
                let label = context.resolve(
                    Text("\(box.label) \(Int((box.confidence * 100).rounded()))%")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.white)
                )
                let textSize = label.measure(in: size)
                let tab = CGRect(
                    x: rect.minX, y: max(0, rect.minY - textSize.height),
                    width: textSize.width + 6, height: textSize.height
                )
                context.fill(Path(tab), with: .color(color))
                context.draw(label, at: CGPoint(x: tab.minX + 3, y: tab.minY), anchor: .topLeading)
            }
        }
        .allowsHitTesting(false)
    }
}
