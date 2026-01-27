/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.hook.systemui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.central.provider.player.ActivePlayerListener
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ProviderInfo
import io.github.proify.lyricon.xposed.util.LyricPrefs
import io.github.proify.lyricon.xposed.util.StatusBarColor
import io.github.proify.lyricon.xposed.util.StatusBarColorMonitor

object LyricViewController : ActivePlayerListener,
    StatusBarColorMonitor.OnColorChangeListener, Handler.Callback {
    private const val TAG = "LyricViewController"
    private const val DEBUG = false

    private const val MSG_PROVIDER_CHANGED = 1
    private const val MSG_SONG_CHANGED = 2
    private const val MSG_PLAYBACK_STATE = 3
    private const val MSG_POSITION = 4
    private const val MSG_SEEK_TO = 5
    private const val MSG_SEND_TEXT = 6
    private const val MSG_TRANSLATION_TOGGLE = 7
    private const val MSG_COLOR_CHANGED = 8

    private const val UPDATE_INTERVAL_MS = 1000L / 60L //16.66 ms

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var activePackage: String = ""
        private set

    @SuppressLint("StaticFieldLeak")
    var statusBarViewManager: StatusBarViewManager? = null

    var providerInfo: ProviderInfo? = null
        private set

    private val uiHandler by lazy { Handler(Looper.getMainLooper(), this) }

    // 记录上次成功发送消息的时间戳，用于采样控制
    private var lastPostTime = 0L

    init {
        StatusBarColorMonitor.register(this)
    }

    // --- 接口回调：通过 Message 池发送指令 ---

    override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
        if (DEBUG) YLog.debug(tag = TAG, msg = "onActiveProviderChanged: $providerInfo")

        this.providerInfo = providerInfo
        activePackage = providerInfo?.playerPackageName.orEmpty()
        LyricPrefs.activePackageName = activePackage
        uiHandler.obtainMessage(MSG_PROVIDER_CHANGED, providerInfo).sendToTarget()
    }

    override fun onSongChanged(song: Song?) {
        if (DEBUG) YLog.debug(tag = TAG, msg = "onSongChanged: $song")

        uiHandler.obtainMessage(MSG_SONG_CHANGED, song).sendToTarget()
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        if (DEBUG) YLog.debug(tag = TAG, msg = "onPlaybackStateChanged: $isPlaying")

        this.isPlaying = isPlaying
        // arg1 传递布尔值，0开销
        uiHandler.obtainMessage(MSG_PLAYBACK_STATE, if (isPlaying) 1 else 0, 0).sendToTarget()
    }

    override fun onPositionChanged(position: Long) {
        if (DEBUG) YLog.debug(tag = TAG, msg = "onPositionChanged: $position")

        val now = SystemClock.uptimeMillis()

        // 移除队列中旧的进度消息，确保合并
        uiHandler.removeMessages(MSG_POSITION)

        val msg = uiHandler.obtainMessage(MSG_POSITION)
        // 位运算拆分 Long 到两个 Int，避免 Long 对象装箱分配
        msg.arg1 = (position shr 32).toInt()
        msg.arg2 = (position and 0xFFFFFFFFL).toInt()

        val timePassed = now - lastPostTime
        if (timePassed >= UPDATE_INTERVAL_MS) {
            uiHandler.sendMessage(msg)
            lastPostTime = now
        } else {
            // 频率限制：如果回调过快，则延迟到下一个周期执行
            uiHandler.sendMessageDelayed(msg, UPDATE_INTERVAL_MS - timePassed)
        }
    }

    override fun onSeekTo(position: Long) {
        if (DEBUG) YLog.debug(tag = TAG, msg = "onSeekTo: $position")

        val msg = uiHandler.obtainMessage(MSG_SEEK_TO)
        msg.arg1 = (position shr 32).toInt()
        msg.arg2 = (position and 0xFFFFFFFFL).toInt()
        msg.sendToTarget()
    }

    override fun onSendText(text: String?) {
        if (DEBUG) YLog.debug(tag = TAG, msg = "onSendText: $text")

        uiHandler.obtainMessage(MSG_SEND_TEXT, text).sendToTarget()
    }

    override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
        if (DEBUG) YLog.debug(tag = TAG, msg = "onDisplayTranslationChanged: $isDisplayTranslation")

        uiHandler.obtainMessage(MSG_TRANSLATION_TOGGLE, if (isDisplayTranslation) 1 else 0, 0)
            .sendToTarget()
    }

    override fun onColorChanged(colorInfo: StatusBarColor) {
        // 颜色变化同样合并处理，只响应最后一帧
        uiHandler.removeMessages(MSG_COLOR_CHANGED)
        uiHandler.obtainMessage(MSG_COLOR_CHANGED, colorInfo).sendToTarget()
    }

    // --- 集中式 UI 处理逻辑 ---

    override fun handleMessage(msg: Message): Boolean {
        val view = statusBarViewManager?.lyricView ?: return true
        if (!view.isAttachedToWindow) return true

        try {
            when (msg.what) {
                MSG_PROVIDER_CHANGED -> {
                    val info = msg.obj as? ProviderInfo
                    view.logoView.providerLogo = info?.logo
                    statusBarViewManager?.updateLyricStyle(LyricPrefs.getLyricStyle())
                    if (info == null) {
                        view.updateSong(null)
                        view.setPlaying(false)
                    } else {
                        view.updateVisibility()
                    }
                }

                MSG_SONG_CHANGED -> view.updateSong(msg.obj as? Song)
                MSG_PLAYBACK_STATE -> view.setPlaying(msg.arg1 == 1)
                MSG_POSITION -> {
                    // 合并高低位还原 Long
                    val pos = (msg.arg1.toLong() shl 32) or (msg.arg2.toLong() and 0xFFFFFFFFL)
                    view.updatePosition(pos)
                }

                MSG_SEEK_TO -> {
                    val pos = (msg.arg1.toLong() shl 32) or (msg.arg2.toLong() and 0xFFFFFFFFL)
                    view.seekTo(pos)
                }

                MSG_SEND_TEXT -> view.updateText(msg.obj as? String)
                MSG_TRANSLATION_TOGGLE -> view.setDisplayTranslation(msg.arg1 == 1)
                MSG_COLOR_CHANGED -> view.onColorChanged(msg.obj as StatusBarColor)
            }
        } catch (t: Throwable) {
            YLog.error("LyricViewController: UI Update Error [type=${msg.what}]", t)
        }
        return true
    }
}