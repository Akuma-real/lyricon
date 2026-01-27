/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import android.annotation.SuppressLint
import android.os.SharedMemory
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.github.proify.lyricon.central.Constants
import io.github.proify.lyricon.central.inflate
import io.github.proify.lyricon.central.json
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.IRemotePlayer
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal class RemotePlayer(
    private val info: ProviderInfo,
    private val playerListener: PlayerListener = ActivePlayerDispatcher
) : IRemotePlayer.Stub() {

    companion object {
        private val DEBUG = Constants.isDebug()
        private const val TAG = "RemotePlayer"
    }

    private val recorder = PlayerRecorder(info)
    private var positionSharedMemory: SharedMemory? = null

    @Volatile
    private var positionReadBuffer: ByteBuffer? = null
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var positionProducerJob: Job? = null

    @Volatile
    private var positionUpdateInterval: Long = ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL
    private val released = AtomicBoolean(false)

    init {
        initSharedMemory()
    }

    fun destroy() {
        if (released.get()) return
        released.set(true)

        stopPositionUpdate()

        positionReadBuffer?.let { SharedMemory.unmap(it) }
        positionReadBuffer = null

        positionSharedMemory?.close()
        positionSharedMemory = null

        scope.cancel()
    }

    private fun initSharedMemory() {
        try {
            val hashCode = "${info.providerPackageName}/${info.playerPackageName}"
                .hashCode().toHexString()
            positionSharedMemory = SharedMemory.create(
                "lyricon_music_position_${hashCode}_${Os.getpid()}",
                Long.SIZE_BYTES
            ).apply {
                setProtect(OsConstants.PROT_READ or OsConstants.PROT_WRITE)
                positionReadBuffer = mapReadOnly()
            }
            Log.i(TAG, "SharedMemory initialized")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to init SharedMemory", t)
        }
    }

    private fun readPosition(): Long {
        val buffer = positionReadBuffer ?: return 0
        return try {
            synchronized(buffer) {
                buffer.getLong(0).coerceAtLeast(0)
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun startPositionUpdate() {
        if (positionProducerJob != null) return

        if (DEBUG) Log.d(TAG, "Start position updater ,interval $positionUpdateInterval ms")

        positionProducerJob = scope.launch {
            while (isActive) {
                val position = readPosition()
                recorder.lastPosition = position
                try {
                    playerListener.onPositionChanged(recorder, position)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error notifying position listener", t)
                }
                delay(positionUpdateInterval)
            }
        }
    }

    @SuppressLint("MemberExtensionConflict")
    private fun stopPositionUpdate() {
        positionProducerJob?.cancel()
        positionProducerJob = null
        Log.d(TAG, "Stop position updater")
    }

    override fun setPositionUpdateInterval(interval: Int) {
        if (released.get()) return

        positionUpdateInterval = interval.coerceAtLeast(16).toLong()
        if (DEBUG) Log.d(TAG, "Update interval = $positionUpdateInterval ms")

        if (positionProducerJob != null) {
            stopPositionUpdate()
            startPositionUpdate()
        }
    }

    private inline fun runCall(crossinline block: PlayerListener.() -> Unit) {
        try {
            playerListener.block()
        } catch (t: Throwable) {
            Log.e(TAG, "Error notifying listener", t)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun setSong(bytes: ByteArray?) {
        if (released.get()) return

        val song = bytes?.let {
            try {
                val start = System.currentTimeMillis()
                val decompressedBytes = bytes.inflate()
                val parsed = json.decodeFromStream(
                    Song.serializer(),
                    decompressedBytes.inputStream()
                )
                if (DEBUG) Log.d(TAG, "Song parsed in ${System.currentTimeMillis() - start} ms")
                parsed
            } catch (t: Throwable) {
                Log.e(TAG, "Song parse failed", t)
                null
            }
        }

        val normalized = song?.normalize()
        recorder.lastSong = normalized
        recorder.lastText = null

        Log.i(TAG, "Song changed")
        runCall { onSongChanged(recorder, normalized) }
    }

    override fun setPlaybackState(isPlaying: Boolean) {
        if (released.get()) return

        recorder.lastIsPlaying = isPlaying
        runCall { onPlaybackStateChanged(recorder, isPlaying) }

        if (DEBUG) Log.i(TAG, "Playback state = $isPlaying")

        if (isPlaying) {
            startPositionUpdate()
        } else {
            stopPositionUpdate()
        }
    }

    override fun seekTo(position: Long) {
        if (released.get()) return

        val safe = position.coerceAtLeast(0)
        recorder.lastPosition = safe

        runCall { onSeekTo(recorder, safe) }
    }

    override fun sendText(text: String?) {
        if (released.get()) return

        recorder.lastSong = null
        recorder.lastText = text

        runCall { onSendText(recorder, text) }
    }

    override fun setDisplayTranslation(isDisplayTranslation: Boolean) {
        if (released.get()) return

        recorder.lastIsDisplayTranslation = isDisplayTranslation
        runCall { onDisplayTranslationChanged(recorder, isDisplayTranslation) }
    }

    override fun getPositionMemory(): SharedMemory? = positionSharedMemory
}