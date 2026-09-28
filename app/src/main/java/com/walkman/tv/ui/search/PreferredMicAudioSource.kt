package com.walkman.tv.ui.search

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On Android 13+, feed SpeechRecognizer from the device's built-in input when Android exposes it.
 * The vendor's wake-word microphone may still be private to the system; callers fall back to
 * the default recognizer input when this source cannot be opened or routed.
 */
internal class PreferredMicAudioSource private constructor(
    private val recorder: AudioRecord,
    val readEnd: ParcelFileDescriptor,
    private val writeEnd: ParcelFileDescriptor,
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val worker = Thread({
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { output ->
                val samples = ByteArray(4096)
                while (!stopped.get()) {
                    val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (count < 0) break
                    if (count > 0) output.write(samples, 0, count)
                }
            }
        } catch (_: Exception) {
            // Closing either end of the pipe is the normal way to finish recognition.
        }
    }, "walkman-builtin-mic").apply {
        isDaemon = true
        start()
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { recorder.stop() }
        runCatching { writeEnd.close() }
        runCatching { readEnd.close() }
        runCatching { recorder.release() }
        worker.interrupt()
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        fun open(context: Context): PreferredMicAudioSource? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return null
            val builtIn = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.isSource && it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: return null
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return null

            var recorder: AudioRecord? = null
            var pipe: Array<ParcelFileDescriptor>? = null
            var handedOff = false
            try {
                val record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, 8192))
                    .build()
                recorder = record
                if (record.state != AudioRecord.STATE_INITIALIZED ||
                    !record.setPreferredDevice(builtIn)) return null
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null
                val routed = record.routedDevice
                if (routed != null && routed.type != AudioDeviceInfo.TYPE_BUILTIN_MIC)
                    return null
                val ends = ParcelFileDescriptor.createPipe()
                pipe = ends
                val source = PreferredMicAudioSource(record, ends[0], ends[1])
                handedOff = true
                return source
            } catch (_: Exception) {
                return null
            } finally {
                if (!handedOff) {
                    pipe?.forEach { runCatching { it.close() } }
                    recorder?.let { record ->
                        runCatching {
                            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                                record.stop()
                        }
                        runCatching { record.release() }
                    }
                }
            }
        }
    }
}
