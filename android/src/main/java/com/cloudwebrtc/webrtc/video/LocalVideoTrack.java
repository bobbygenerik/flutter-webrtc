package com.cloudwebrtc.webrtc.video;

import androidx.annotation.Nullable;

import com.cloudwebrtc.webrtc.LocalTrack;

import org.webrtc.VideoFrame;
import org.webrtc.VideoProcessor;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

public class LocalVideoTrack extends LocalTrack implements VideoProcessor {
    public interface ExternalVideoFrameProcessing {
        /**
         * Process a video frame.
         * @param frame
         * @return The processed video frame.
         */
        public abstract VideoFrame onFrame(VideoFrame frame);
    }

    public LocalVideoTrack(VideoTrack videoTrack) {
        super(videoTrack);
    }

    List<ExternalVideoFrameProcessing> processors = new ArrayList<>();

    public void addProcessor(ExternalVideoFrameProcessing processor) {
        synchronized (processors) {
            processors.add(processor);
        }
    }

    public void removeProcessor(ExternalVideoFrameProcessing processor) {
        synchronized (processors) {
            processors.remove(processor);
        }
    }

    private VideoSink sink = null;
    private volatile int outputMaxWidth = 0;
    private volatile int outputMaxHeight = 0;
    private volatile int outputMaxFps = 0;
    private volatile long lastOutputFrameTimestampNs = 0;

    /**
     * Restricts frames before they enter the native WebRTC encoder. Keeping the
     * camera capture stable while adapting its output avoids disruptive camera
     * restarts and prevents hardware encoders from ignoring RTP-only scaling.
     */
    public synchronized void setOutputFormat(int maxWidth, int maxHeight, int maxFps) {
        outputMaxWidth = Math.max(0, maxWidth);
        outputMaxHeight = Math.max(0, maxHeight);
        outputMaxFps = Math.max(0, maxFps);
        lastOutputFrameTimestampNs = 0;
    }

    @Override
    public void setSink(@Nullable VideoSink videoSink) {
        sink = videoSink;
    }

    @Override
    public void onCapturerStarted(boolean b) {}

    @Override
    public void onCapturerStopped() {}

    @Override
    public void onFrameCaptured(VideoFrame videoFrame) {
        VideoSink currentSink = sink;
        if (currentSink == null) return;

        synchronized (processors) {
            for (ExternalVideoFrameProcessing processor : processors) {
                videoFrame = processor.onFrame(videoFrame);
            }
        }

        final int maxFps = outputMaxFps;
        if (maxFps > 0) {
            final long timestampNs = videoFrame.getTimestampNs();
            final long minimumIntervalNs = 1_000_000_000L / maxFps;
            if (lastOutputFrameTimestampNs > 0
                    && timestampNs > lastOutputFrameTimestampNs
                    && timestampNs - lastOutputFrameTimestampNs < minimumIntervalNs) {
                return;
            }
            lastOutputFrameTimestampNs = timestampNs;
        }

        final VideoFrame.Buffer source = videoFrame.getBuffer();
        final int maxWidth = outputMaxWidth;
        final int maxHeight = outputMaxHeight;
        if (maxWidth <= 0 || maxHeight <= 0
                || (source.getWidth() <= maxWidth && source.getHeight() <= maxHeight)) {
            currentSink.onFrame(videoFrame);
            return;
        }

        final double targetAspect = (double) maxWidth / maxHeight;
        final double sourceAspect = (double) source.getWidth() / source.getHeight();
        int cropWidth = source.getWidth();
        int cropHeight = source.getHeight();
        int cropX = 0;
        int cropY = 0;
        if (sourceAspect > targetAspect) {
            cropWidth = makeEven((int) Math.round(cropHeight * targetAspect));
            cropX = makeEven((source.getWidth() - cropWidth) / 2);
        } else if (sourceAspect < targetAspect) {
            cropHeight = makeEven((int) Math.round(cropWidth / targetAspect));
            cropY = makeEven((source.getHeight() - cropHeight) / 2);
        }

        final int scaledWidth = makeEven(Math.min(maxWidth, cropWidth));
        final int scaledHeight = makeEven(Math.min(maxHeight, cropHeight));
        final VideoFrame.Buffer scaledBuffer = source.cropAndScale(
                cropX, cropY, cropWidth, cropHeight, scaledWidth, scaledHeight);
        final VideoFrame scaledFrame = new VideoFrame(
                scaledBuffer, videoFrame.getRotation(), videoFrame.getTimestampNs());
        try {
            currentSink.onFrame(scaledFrame);
        } finally {
            scaledFrame.release();
        }
    }

    private static int makeEven(int value) {
        return Math.max(2, value & ~1);
    }
}
