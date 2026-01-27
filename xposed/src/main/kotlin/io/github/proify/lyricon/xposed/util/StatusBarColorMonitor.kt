/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package io.github.proify.lyricon.xposed.util

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.proify.android.extensions.setColorAlpha
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 状态栏颜色监听器
 * 用于监控系统状态栏颜色的变化
 */
object StatusBarColorMonitor {

    // 颜色亮度缓存，避免重复计算
    private val colorBrightnessCache = mutableMapOf<Int, Boolean>()

    // 颜色变化监听器集合
    private val listeners = CopyOnWriteArraySet<OnColorChangeListener>()

    // Hook 解锁器集合
    private val hookEntries = CopyOnWriteArraySet<XC_MethodHook.Unhook>()

    /**
     * 注册颜色变化监听器
     */
    fun register(listener: OnColorChangeListener) {
        listeners.add(listener)
    }

    /**
     * 注销颜色变化监听器
     */
    fun unregister(listener: OnColorChangeListener) {
        listeners.remove(listener)
    }

    /**
     * Hook 目标类的 onDarkChanged 方法
     */
    fun hook(targetClass: Class<out View>) {
        unhookAll()

        val classLoader = targetClass.classLoader
        val targetMethods = findDarkChangedMethods(targetClass)

        YLog.debug("找到 ${targetMethods.size} 个 onDarkChanged 方法")

        targetMethods.forEach { method ->
            val hook = XposedBridge.hookMethod(method, DarkChangedHookCallback(classLoader))
            hookEntries.add(hook)
            YLog.debug("已 Hook 方法: $method")
        }
    }

    /**
     * 取消所有 Hook
     */
    fun unhookAll() {
        hookEntries.forEach { it.unhook() }
        hookEntries.clear()
    }

    /**
     * 查找所有 onDarkChanged 方法
     */
    private fun findDarkChangedMethods(clazz: Class<*>): List<Method> {
        return clazz.methods
            .filter { it.name == "onDarkChanged" }
            .toList()
    }

    /**
     * 检查颜色相对于背景是否呈现深色
     */
    private fun Int.isDarkAgainst(@ColorInt background: Int = Color.BLACK): Boolean {
        val alpha = Color.alpha(this)

        // 完全透明时，直接判断背景亮度
        if (alpha == 0) {
            return ColorUtils.calculateLuminance(background) < 0.5
        }

        // 不透明时直接判断，半透明时与背景合成后判断
        val finalColor = if (alpha == 255) {
            this
        } else {
            ColorUtils.compositeColors(this, background)
        }

        return ColorUtils.calculateLuminance(finalColor) < 0.5
    }

    /**
     * 颜色变化监听器接口
     */
    fun interface OnColorChangeListener {
        fun onColorChanged(colorInfo: StatusBarColor)
    }

    private class DarkChangedHookCallback(
        private val classLoader: ClassLoader?
    ) : XC_MethodHook() {

        private var nonAdaptedColorFieldAvailable = true
        private var tintMethodAvailable = true

        private var darkIconDispatcherClass: Class<*>? = null

        override fun afterHookedMethod(param: MethodHookParam) {
            val target = param.thisObject
            val color = extractColor(param, target)

            if (color == 0 || listeners.isEmpty()) return

            val lightMode = colorBrightnessCache.getOrPut(color) {
                color.isDarkAgainst(Color.BLACK)
            }

            val colorInfo = StatusBarColor(color, lightMode)

            listeners.forEach { listener ->
                runCatching {
                    listener.onColorChanged(colorInfo)
                }.onFailure { e ->
                    YLog.error("监听器回调失败", e)
                }
            }
        }

        /**
         * 从各种来源提取颜色值
         */
        private fun extractColor(param: MethodHookParam, target: Any): Int {
            // 1. 尝试从 mNonAdaptedColor 字段获取
            var color = extractNonAdaptedColor(target)

            // 2. 尝试从 tint 方法获取
            if (color == 0) {
                color = extractTintColor(param)
            }

            // 3. 如果目标是 TextView，尝试获取文本颜色
            if (color == 0 && target is TextView) {
                color = target.currentTextColor
            }

            return color
        }

        /**
         * 从 mNonAdaptedColor 字段提取颜色
         */
        @SuppressLint("PrivateApi")
        private fun extractNonAdaptedColor(target: Any): Int {
            if (!nonAdaptedColorFieldAvailable) return 0

            return runCatching {
                XposedHelpers.getIntField(target, "mNonAdaptedColor")
            }.onFailure {
                nonAdaptedColorFieldAvailable = false
                YLog.warn("mNonAdaptedColor 字段不可用: ${it.message}")
            }.getOrElse { 0 }
        }

        /**
         * 通过 DarkIconDispatcher.getTint 提取颜色
         */
        @SuppressLint("PrivateApi")
        private fun extractTintColor(param: MethodHookParam): Int {
            if (!tintMethodAvailable || param.args.size != 3) return 0

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
            }.getOrElse { 0 }
        }
    }
}

/**
 * 状态颜色信息
 */
data class StatusBarColor(
    @field:ColorInt var color: Int = 0,
    var lightMode: Boolean = false
) {
    val translucentColor: Int by lazy { color.setColorAlpha(0.5f) }
}