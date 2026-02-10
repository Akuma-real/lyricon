/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.central.provider.player

import android.media.session.PlaybackState
import android.os.SharedMemory
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.github.proify.lyricon.central.inflate
import io.github.proify.lyricon.central.json
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.IRemotePlayer
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        private const val TAG = "RemotePlayer"
        private const val MIN_INTERVAL_MS = 16L
    }

    private val recorder = PlayerRecorder(info)
    private var positionSharedMemory: SharedMemory? = null

    @Volatile
    private var positionReadBuffer: ByteBuffer? = null
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var positionProducerJob: Job? = null

    @Volatile
    private var positionUpdateInterval: Long = ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL

    private val released = AtomicBoolean(false)
    private val isState2Enabled = AtomicBoolean(false)

    @Volatile
    private var lastPlaybackState: PlaybackState? = null

    init {
        initSharedMemory()
    }

    fun destroy() {
        if (!released.compareAndSet(false, true)) return
        stopPositionUpdate()
        positionReadBuffer?.let { SharedMemory.unmap(it) }
        positionReadBuffer = null
        positionSharedMemory?.close()
        positionSharedMemory = null
        scope.cancel()
    }

    private fun initSharedMemory() {
        try {
            val hash = ("${info.providerPackageName}/${info.playerPackageName}").hashCode()
            val hashHex = Integer.toHexString(hash)
            positionSharedMemory = SharedMemory.create(
                "lyricon_music_position_${hashHex}_${Os.getpid()}",
                Long.SIZE_BYTES
            ).apply {
                setProtect(OsConstants.PROT_READ or OsConstants.PROT_WRITE)
                positionReadBuffer = mapReadOnly()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to init SharedMemory", t)
        }
    }

    private fun computeCurrentPosition(): Long {
        if (isState2Enabled.get()) {
            val state = lastPlaybackState ?: return 0L
            if (state.state != PlaybackState.STATE_PLAYING) {
                return state.position
            }
            val timeDiff = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            return (state.position + (timeDiff * state.playbackSpeed).toLong()).coerceAtLeast(0L)
        } else {
            val buffer = positionReadBuffer ?: return 0L
            return try {
                buffer.getLong(0).coerceAtLeast(0L)
            } catch (_: Throwable) {
                0L
            }
        }
    }

    private fun startPositionUpdate() {
        if (positionProducerJob != null || released.get()) return
        val interval = positionUpdateInterval.coerceAtLeast(MIN_INTERVAL_MS)

        positionProducerJob = scope.launch {
            var nextTick = System.nanoTime()
            while (isActive) {
                val position = computeCurrentPosition()
                recorder.lastPosition = position
                playerListener.safeNotify { onPositionChanged(recorder, position) }

                nextTick += interval * 1_000_000L
                val sleepNs = nextTick - System.nanoTime()
                if (sleepNs > 0) delay(sleepNs / 1_000_000L)
                else {
                    delay(0)
                    nextTick = System.nanoTime()
                }
            }
        }
    }

    private fun stopPositionUpdate() {
        positionProducerJob?.cancel()
        positionProducerJob = null
    }

    override fun setPositionUpdateInterval(interval: Int) {
        if (released.get()) return
        positionUpdateInterval = interval.toLong().coerceAtLeast(MIN_INTERVAL_MS)
        if (positionProducerJob != null) {
            stopPositionUpdate()
            startPositionUpdate()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun setSong(bytes: ByteArray?) {
        if (released.get()) return
        val song = bytes?.let {
            try {
                val decompressed = it.inflate()
                json.decodeFromStream(Song.serializer(), decompressed.inputStream())
            } catch (t: Throwable) {
                Log.e(TAG, "Song parse failed", t)
                null
            }
        }
        val normalized = song?.normalize()
        recorder.lastSong = normalized
        playerListener.safeNotify { onSongChanged(recorder, normalized) }
    }

    /**
     * 设置基础播放状态。调用此方法会强制退出 State2 模式。
     */
    override fun setPlaybackState(isPlaying: Boolean) {
        if (released.get()) return

        // 退出 State2 模式并清理状态
        if (isState2Enabled.compareAndSet(true, false)) {
            lastPlaybackState = null
        }

        if (recorder.lastIsPlaying != isPlaying) {
            recorder.lastIsPlaying = isPlaying
            playerListener.safeNotify { onPlaybackStateChanged(recorder, isPlaying) }
            if (isPlaying) startPositionUpdate() else stopPositionUpdate()
        }
    }

    override fun seekTo(position: Long) {
        if (released.get()) return
        val safe = position.coerceAtLeast(0L)
        recorder.lastPosition = safe
        playerListener.safeNotify { onSeekTo(recorder, safe) }
    }

    override fun sendText(text: String?) {
        if (released.get()) return
        recorder.lastText = text
        playerListener.safeNotify { onSendText(recorder, text) }
    }

    override fun setDisplayTranslation(isDisplayTranslation: Boolean) {
        if (released.get()) return
        recorder.lastIsDisplayTranslation = isDisplayTranslation
        playerListener.safeNotify { onDisplayTranslationChanged(recorder, isDisplayTranslation) }
    }

    override fun setDisplayRoma(isDisplayRoma: Boolean) {
        if (released.get()) return
        recorder.lastDisplayRoma = isDisplayRoma
        playerListener.safeNotify { onDisplayRomaChanged(recorder, isDisplayRoma) }
    }

    /**
     * 设置增强播放状态 (State2)。
     */
    override fun setPlaybackState2(state: PlaybackState?) {
        if (released.get()) return

        if (state == null) {
            if (isState2Enabled.compareAndSet(true, false)) {
                lastPlaybackState = null
                stopPositionUpdate()
            }
            return
        }

        isState2Enabled.set(true)
        lastPlaybackState = state

        val isUiActive = when (state.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true

            else -> false
        }

        if (recorder.lastIsPlaying != isUiActive) {
            recorder.lastIsPlaying = isUiActive
            playerListener.safeNotify { onPlaybackStateChanged(recorder, isUiActive) }
        }

        if (state.state == PlaybackState.STATE_PLAYING) {
            startPositionUpdate()
        } else {
            stopPositionUpdate()
            val snapPos = state.position
            if (recorder.lastPosition != snapPos) {
                recorder.lastPosition = snapPos
                playerListener.safeNotify { onPositionChanged(recorder, snapPos) }
            }
        }
    }

    override fun getPositionMemory(): SharedMemory? = positionSharedMemory

    private inline fun PlayerListener.safeNotify(crossinline block: PlayerListener.() -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Error notifying listener", t)
        }
    }
}