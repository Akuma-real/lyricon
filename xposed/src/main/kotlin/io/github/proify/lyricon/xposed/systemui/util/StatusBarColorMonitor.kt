/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.xposed.systemui.util

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.proify.lyricon.common.util.ResourceMapper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object StatusBarColorMonitor {
    private const val TAG = "StatusBarColorMonitor"

    private val listeners = CopyOnWriteArraySet<OnColorChangeListener>()
    private val hookEntries = CopyOnWriteArraySet<XC_MethodHook.Unhook>()
    private val hooked = AtomicBoolean(false)

    fun registerListener(listener: OnColorChangeListener) = listeners.add(listener)
    //fun unregisterListener(listener: OnColorChangeListener) = listeners.remove(listener)

    private fun hook(targetClass: Class<out View>) {
        unhookAll()
        val methods = targetClass.methods.filter { it.name == "onDarkChanged" }
        YLog.debug("找到 ${methods.size} 个 onDarkChanged 方法")

        methods.forEach { method ->
            XposedBridge.hookMethod(method, DarkChangedHookCallback(targetClass.classLoader))
                .also { hookEntries.add(it) }
            YLog.debug("已 Hook 方法: $method")
        }
    }

    fun unhookAll() {
        hookEntries.forEach { it.unhook() }
        hookEntries.clear()
        hooked.set(false)
    }

    fun hookFromClock(view: ViewGroup) {
        if (hooked.get()) return
        val clockId = ResourceMapper.getIdByName(view.context, "clock")
        view.findViewById<View>(clockId)?.let {
            hook(it.javaClass)
            hooked.set(true)
        } ?: YLog.warn(tag = TAG, msg = "找不到时钟控件！")
    }

    private class DarkChangedHookCallback(
        private val classLoader: ClassLoader?
    ) : XC_MethodHook() {

        private var nonAdaptedColorAvailable = true
        private var tintMethodAvailable = true
        private var darkIconDispatcherClass: Class<*>? = null
        private var lastColor = 0

        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val darkIntensity = param.args.getOrNull(1) as? Float ?: return run {
                    YLog.warn(
                        tag = TAG,
                        msg = "DarkIconDispatcher.onDarkChanged: Failed to get darkIntensity"
                    )
                }
                val color = extractColor(param, param.thisObject)
                if (color == 0 || color == lastColor || listeners.isEmpty()) return

                lastColor = color
                val lightMode = darkIntensity > 0.5f

                listeners.forEach { listener ->
                    runCatching { listener.onColorChanged(color, lightMode) }
                        .onFailure { YLog.error("监听器回调失败", it) }
                }
            } catch (e: Exception) {
                YLog.error(TAG, e)
            }
        }

        private fun extractColor(param: MethodHookParam, target: Any): Int {
            return extractNonAdaptedColor(target)
                .takeIf { it != 0 }
                ?: extractTintColor(param)
                    .takeIf { it != 0 }
                ?: (target as? TextView)?.currentTextColor ?: 0
        }

        @SuppressLint("PrivateApi")
        private fun extractNonAdaptedColor(target: Any): Int {
            if (!nonAdaptedColorAvailable) return 0
            return runCatching { XposedHelpers.getIntField(target, "mNonAdaptedColor") }
                .onFailure {
                    nonAdaptedColorAvailable = false
                    YLog.warn("mNonAdaptedColor 字段不可用: ${it.message}")
                }.getOrDefault(0)
        }

        @SuppressLint("PrivateApi")
        private fun extractTintColor(param: MethodHookParam): Int {
            if (!tintMethodAvailable || param.args.size < 3) return 0
            return runCatching {
                val dispatcherClass = darkIconDispatcherClass ?: classLoader
                    ?.loadClass("com.android.systemui.plugins.DarkIconDispatcher")
                    ?.also { darkIconDispatcherClass = it }
                ?: return 0
                XposedHelpers.callStaticMethod(
                    dispatcherClass,
                    "getTint",
                    param.args[0],
                    param.thisObject,
                    param.args[2]
                ) as Int
            }.onFailure {
                tintMethodAvailable = false
                YLog.warn("DarkIconDispatcher.getTint 方法不可用: ${it.message}")
            }.getOrDefault(0)
        }
    }
}