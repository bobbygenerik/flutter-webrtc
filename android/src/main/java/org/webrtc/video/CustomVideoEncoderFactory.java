package org.webrtc.video;

import androidx.annotation.Nullable;

import com.cloudwebrtc.webrtc.SimulcastVideoEncoderFactoryWrapper;

import org.webrtc.EglBase;
import org.webrtc.SoftwareVideoEncoderFactory;
import org.webrtc.VideoCodecInfo;
import org.webrtc.VideoCodecStatus;
import org.webrtc.VideoEncoder;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class CustomVideoEncoderFactory implements VideoEncoderFactory {
    private SoftwareVideoEncoderFactory softwareVideoEncoderFactory = new SoftwareVideoEncoderFactory();
    private SimulcastVideoEncoderFactoryWrapper simulcastVideoEncoderFactoryWrapper;

    private boolean forceSWCodec  = false;

    private List<String> forceSWCodecs = new ArrayList<>();

    // Shared by every encoder created by this factory. Updating the cap only
    // changes subsequent rate-control callbacks; it does not recreate the
    // MediaCodec component.
    private final AtomicInteger bitrateCapBps = new AtomicInteger(0);

    public CustomVideoEncoderFactory(EglBase.Context sharedContext,
                                     boolean enableIntelVp8Encoder,
                                     boolean enableH264HighProfile) {
        this.simulcastVideoEncoderFactoryWrapper = new SimulcastVideoEncoderFactoryWrapper(sharedContext, enableIntelVp8Encoder, enableH264HighProfile);
    }

    public void setForceSWCodec(boolean forceSWCodec) {
        this.forceSWCodec = forceSWCodec;
    }

    public void setForceSWCodecList(List<String> forceSWCodecs) {
        this.forceSWCodecs = forceSWCodecs;
    }

    /**
     * Caps the bitrate allocation delivered to Android video encoders.
     * A value of zero clears the cap.
     */
    public void setBitrateCapBps(int bps) {
        bitrateCapBps.set(Math.max(0, bps));
    }

    public int getBitrateCapBps() {
        return bitrateCapBps.get();
    }

    @Nullable
    @Override
    public VideoEncoder createEncoder(VideoCodecInfo videoCodecInfo) {
        final VideoEncoder encoder;
        if(forceSWCodec) {
            encoder = softwareVideoEncoderFactory.createEncoder(videoCodecInfo);
        } else if(!forceSWCodecs.isEmpty() && forceSWCodecs.contains(videoCodecInfo.name)) {
            encoder = softwareVideoEncoderFactory.createEncoder(videoCodecInfo);
        } else {
            encoder = simulcastVideoEncoderFactoryWrapper.createEncoder(videoCodecInfo);
        }
        return encoder == null ? null : new BitrateClampingVideoEncoder(encoder, bitrateCapBps);
    }

    @Override
    public VideoCodecInfo[] getSupportedCodecs() {
        if(forceSWCodec && forceSWCodecs.isEmpty()) {
            return softwareVideoEncoderFactory.getSupportedCodecs();
        }
        return simulcastVideoEncoderFactoryWrapper.getSupportedCodecs();
    }

    /**
     * Clamps WebRTC's native rate-control allocation before it reaches
     * MediaCodec. This protects the RTP queue without calling
     * RTCRtpSender.setParameters(), so Qualcomm Codec2 is not reinitialized.
     */
    private static final class BitrateClampingVideoEncoder implements VideoEncoder {
        private final VideoEncoder delegate;
        private final AtomicInteger bitrateCapBps;

        BitrateClampingVideoEncoder(VideoEncoder delegate, AtomicInteger bitrateCapBps) {
            this.delegate = delegate;
            this.bitrateCapBps = bitrateCapBps;
        }

        @Override
        public long createNative(long webrtcEnvRef) {
            return delegate.createNative(webrtcEnvRef);
        }

        @Override
        public boolean isHardwareEncoder() {
            return delegate.isHardwareEncoder();
        }

        @Override
        public VideoCodecStatus initEncode(Settings settings, Callback callback) {
            final int cap = bitrateCapBps.get();
            if (cap <= 0 || settings.startBitrate * 1000L <= cap) {
                return delegate.initEncode(settings, callback);
            }
            final Settings clampedSettings = new Settings(
                    settings.numberOfCores,
                    settings.width,
                    settings.height,
                    Math.max(1, cap / 1000),
                    settings.maxFramerate,
                    settings.numberOfSimulcastStreams,
                    settings.automaticResizeOn,
                    settings.capabilities);
            return delegate.initEncode(clampedSettings, callback);
        }

        @Override
        public VideoCodecStatus release() {
            return delegate.release();
        }

        @Override
        public VideoCodecStatus encode(VideoFrame frame, EncodeInfo info) {
            return delegate.encode(frame, info);
        }

        @Override
        public VideoCodecStatus setRateAllocation(BitrateAllocation allocation, int framerate) {
            return delegate.setRateAllocation(clamp(allocation), framerate);
        }

        @Override
        public VideoCodecStatus setRates(RateControlParameters parameters) {
            return delegate.setRates(new RateControlParameters(
                    clamp(parameters.bitrate), parameters.framerateFps));
        }

        @Override
        public ScalingSettings getScalingSettings() {
            return delegate.getScalingSettings();
        }

        @Override
        public ResolutionBitrateLimits[] getResolutionBitrateLimits() {
            return delegate.getResolutionBitrateLimits();
        }

        @Override
        public String getImplementationName() {
            return delegate.getImplementationName();
        }

        @Override
        public EncoderInfo getEncoderInfo() {
            return delegate.getEncoderInfo();
        }

        private BitrateAllocation clamp(BitrateAllocation allocation) {
            final int cap = bitrateCapBps.get();
            final int total = allocation.getSum();
            if (cap <= 0 || total <= cap || total <= 0) {
                return allocation;
            }

            final int[][] source = allocation.bitratesBbs;
            final int[][] clamped = new int[source.length][];
            final double scale = cap / (double) total;
            for (int spatial = 0; spatial < source.length; spatial++) {
                clamped[spatial] = new int[source[spatial].length];
                for (int temporal = 0; temporal < source[spatial].length; temporal++) {
                    clamped[spatial][temporal] =
                            (int) Math.floor(source[spatial][temporal] * scale);
                }
            }
            return new BitrateAllocation(clamped);
        }
    }
}
