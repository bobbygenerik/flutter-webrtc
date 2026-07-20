package com.cloudwebrtc.webrtc

import android.util.Log
import org.webrtc.*
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
Copyright 2017, Lyo Kato <lyo.kato at gmail.com> (Original Author)
Copyright 2017-2021, Shiguredo Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
internal class SimulcastVideoEncoderFactoryWrapper(
    sharedContext: EglBase.Context?,
    enableIntelVp8Encoder: Boolean,
    enableH264HighProfile: Boolean
) : VideoEncoderFactory {

    /**
     * Factory that prioritizes software encoder.
     *
     * When the selected codec can't be handled by the software encoder,
     * it uses the hardware encoder as a fallback. However, this class is
     * primarily used to address an issue in libwebrtc, and does not have
     * purposeful usecase itself.
     *
     * To use simulcast in libwebrtc, SimulcastEncoderAdapter is used.
     * SimulcastEncoderAdapter takes in a primary and fallback encoder.
     * If HardwareVideoEncoderFactory and SoftwareVideoEncoderFactory are
     * passed in directly as primary and fallback, when H.264 is used,
     * libwebrtc will crash.
     *
     * This is because SoftwareVideoEncoderFactory does not handle H.264,
     * so [SoftwareVideoEncoderFactory.createEncoder] returns null, and
     * the libwebrtc side does not handle nulls, regardless of whether the
     * fallback is actually used or not.
     *
     * To avoid nulls, we simply pass responsibility over to the HardwareVideoEncoderFactory.
     * This results in HardwareVideoEncoderFactory being both the primary and fallback,
     * but there aren't any specific problems in doing so.
     */
    private class FallbackFactory(private val hardwareVideoEncoderFactory: VideoEncoderFactory) :
        VideoEncoderFactory {

        private val softwareVideoEncoderFactory: VideoEncoderFactory = SoftwareVideoEncoderFactory()

        override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
            val softwareEncoder = softwareVideoEncoderFactory.createEncoder(info)
            val hardwareEncoder = hardwareVideoEncoderFactory.createEncoder(info)
            return if (hardwareEncoder != null && softwareEncoder != null) {
                VideoEncoderFallback(hardwareEncoder, softwareEncoder)
            } else {
                softwareEncoder ?: hardwareEncoder
            }
        }

        override fun getSupportedCodecs(): Array<VideoCodecInfo> {
            val supportedCodecInfos: MutableList<VideoCodecInfo> = mutableListOf()
            supportedCodecInfos.addAll(softwareVideoEncoderFactory.supportedCodecs)
            supportedCodecInfos.addAll(hardwareVideoEncoderFactory.supportedCodecs)
            return supportedCodecInfos.toTypedArray()
        }

    }

    /**
     * Wraps each stream encoder and performs the following:
     * - Starts up a single thread
     * - When the width/height from [initEncode] doesn't match the frame buffer's,
     *   scales the frame prior to encoding.
     * - Always calls the encoder on the thread.
     */
    private class StreamEncoderWrapper(private val encoder: VideoEncoder) : VideoEncoder {

        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        var streamSettings: VideoEncoder.Settings? = null

        override fun initEncode(
            settings: VideoEncoder.Settings,
            callback: VideoEncoder.Callback?
        ): VideoCodecStatus {
            streamSettings = settings
            val future = executor.submit(Callable {
            //     LKLog.i {
            //         """initEncode() thread=${Thread.currentThread().name} [${Thread.currentThread().id}]
            //     |  encoder=${encoder.implementationName}
            //     |  streamSettings:
            //     |    numberOfCores=${settings.numberOfCores}
            //     |    width=${settings.width}
            //     |    height=${settings.height}
            //     |    startBitrate=${settings.startBitrate}
            //     |    maxFramerate=${settings.maxFramerate}
            //     |    automaticResizeOn=${settings.automaticResizeOn}
            //     |    numberOfSimulcastStreams=${settings.numberOfSimulcastStreams}
            //     |    lossNotification=${settings.capabilities.lossNotification}
            // """.trimMargin()
            //     }
                return@Callable encoder.initEncode(settings, callback)
            })
            return future.get()
        }

        override fun release(): VideoCodecStatus {
            val future = executor.submit(Callable { return@Callable encoder.release() })
            return future.get()
        }

        override fun encode(
            frame: VideoFrame,
            encodeInfo: VideoEncoder.EncodeInfo?
        ): VideoCodecStatus {
            val future = executor.submit(Callable {
                //LKLog.d { "encode() buffer=${frame.buffer}, thread=${Thread.currentThread().name} " +
                //        "[${Thread.currentThread().id}]" }
                if (streamSettings == null) {
                    return@Callable encoder.encode(frame, encodeInfo)
                } else if (frame.buffer.width == streamSettings!!.width) {
                    return@Callable encoder.encode(frame, encodeInfo)
                } else {
                    // The incoming buffer is different than the streamSettings received in initEncode()
                    // Need to scale.
                    val originalBuffer = frame.buffer
                    // TODO: Do we need to handle when the scale factor is weird?
                    val adaptedBuffer = originalBuffer.cropAndScale(
                        0, 0, originalBuffer.width, originalBuffer.height,
                        streamSettings!!.width, streamSettings!!.height
                    )
                    val adaptedFrame = VideoFrame(adaptedBuffer, frame.rotation, frame.timestampNs)
                    val result = encoder.encode(adaptedFrame, encodeInfo)
                    adaptedBuffer.release()
                    return@Callable result
                }
            })
            return future.get()
        }

        override fun setRateAllocation(
            allocation: VideoEncoder.BitrateAllocation?,
            frameRate: Int
        ): VideoCodecStatus {
            val future = executor.submit(Callable {
                return@Callable encoder.setRateAllocation(
                    clampAllocation(allocation),
                    frameRate
                )
            })
            return future.get()
        }

        override fun getScalingSettings(): VideoEncoder.ScalingSettings {
            val future = executor.submit(Callable { return@Callable encoder.scalingSettings })
            return future.get()
        }

        override fun getImplementationName(): String {
            val future = executor.submit(Callable { return@Callable encoder.implementationName })
            return future.get()
        }

        override fun createNative(webrtcEnvRef: Long): Long {
            val future = executor.submit(Callable { return@Callable encoder.createNative(webrtcEnvRef) })
            return future.get()
        }

        override fun isHardwareEncoder(): Boolean {
            val future = executor.submit(Callable { return@Callable encoder.isHardwareEncoder })
            return future.get()
        }

        override fun setRates(rcParameters: VideoEncoder.RateControlParameters?): VideoCodecStatus {
            val future = executor.submit(Callable {
                if (rcParameters == null) {
                    return@Callable encoder.setRates(null)
                }
                val clamped = clampAllocation(rcParameters.bitrate)
                if (clamped === rcParameters.bitrate) {
                    return@Callable encoder.setRates(rcParameters)
                }
                return@Callable encoder.setRates(
                    VideoEncoder.RateControlParameters(clamped, rcParameters.framerateFps)
                )
            })
            return future.get()
        }

        override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> {
            val future = executor.submit(Callable { return@Callable encoder.resolutionBitrateLimits })
            return future.get()
        }

        override fun getEncoderInfo(): VideoEncoder.EncoderInfo {
            val future = executor.submit(Callable { return@Callable encoder.encoderInfo })
            return future.get()
        }

        /**
         * Clamp GCC's allocation before it reaches HardwareVideoEncoder.
         * This wrapper is created by the primary/fallback factories that
         * native SimulcastVideoEncoder calls into; the outer
         * BitrateClampingVideoEncoder around SimulcastVideoEncoder is
         * bypassed via createNative() and never sees setRates().
         */
        private fun clampAllocation(
            allocation: VideoEncoder.BitrateAllocation?
        ): VideoEncoder.BitrateAllocation? {
            if (allocation == null) return null
            val capBps = EncoderBitrateCap.get()
            if (capBps <= 0) return allocation
            val originalBps = allocation.getSum()
            if (originalBps <= capBps || originalBps <= 0) return allocation
            val scale = capBps.toDouble() / originalBps
            val original = allocation.bitratesBbs
            val clamped = Array(original.size) { s ->
                IntArray(original[s].size) { t ->
                    (original[s][t] * scale).toInt()
                }
            }
            Log.d(
                "StreamEncoderWrapper",
                "setRates clamped ${originalBps / 1000}kbps → ${capBps / 1000}kbps" +
                    " [${encoder.implementationName}]"
            )
            return VideoEncoder.BitrateAllocation(clamped)
        }
    }

    private class StreamEncoderWrapperFactory(private val factory: VideoEncoderFactory) :
        VideoEncoderFactory {
        override fun createEncoder(videoCodecInfo: VideoCodecInfo?): VideoEncoder? {
            val encoder = factory.createEncoder(videoCodecInfo)
            if (encoder == null) {
                return null
            }
            if (encoder is WrappedNativeVideoEncoder) {
              return encoder
            }
            return StreamEncoderWrapper(encoder)
        }

        override fun getSupportedCodecs(): Array<VideoCodecInfo> {
            return factory.supportedCodecs
        }
    }


    private val primary: VideoEncoderFactory
    private val fallback: VideoEncoderFactory
    private val native: SimulcastVideoEncoderFactory

    init {
        val hardwareVideoEncoderFactory = HardwareVideoEncoderFactory(
            sharedContext, enableIntelVp8Encoder, enableH264HighProfile
        )
        primary = StreamEncoderWrapperFactory(hardwareVideoEncoderFactory)
        fallback = StreamEncoderWrapperFactory(FallbackFactory(primary))
        native = SimulcastVideoEncoderFactory(primary, fallback)
    }

    override fun createEncoder(info: VideoCodecInfo?): VideoEncoder? {
        return native.createEncoder(info)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        return native.supportedCodecs
    }

}
