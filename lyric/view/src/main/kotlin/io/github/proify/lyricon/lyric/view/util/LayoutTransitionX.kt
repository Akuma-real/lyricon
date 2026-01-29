/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("SpellCheckingInspection", "ConstPropertyName", "unused")

package io.github.proify.lyricon.lyric.view.util

import android.animation.LayoutTransition
import android.animation.TimeInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

class LayoutTransitionX : LayoutTransition {

    constructor(config: Config = Smooth) : super() {
        val transitions = listOf(
            APPEARING to (config.appearingDuration to config.appearingInterpolator),
            DISAPPEARING to (config.disappearingDuration to config.disappearingInterpolator),
            CHANGE_APPEARING to (config.changeAppearingDuration to config.changeAppearingInterpolator),
            CHANGE_DISAPPEARING to (config.changeDisappearingDuration to config.changeDisappearingInterpolator),
            CHANGING to (config.changingDuration to config.changingInterpolator)
        )

        transitions.forEach { (type, pair) ->
            val (duration, interpolator) = pair
            setDuration(type, duration)
            setInterpolator(type, interpolator)
        }
    }

    constructor(type: String?) : this(
        when (type) {
            TRANSITION_CONFIG_FAST -> Fast
            TRANSITION_CONFIG_SLOW -> Slow
            TRANSITION_CONFIG_SMOOTH -> Smooth
            TRANSITION_CONFIG_NONE -> None
            else -> Smooth
        }
    )

    data class Config(
        val appearingDuration: Long = 300,
        val disappearingDuration: Long = 300,
        val changeAppearingDuration: Long = 300,
        val changeDisappearingDuration: Long = 300,
        val changingDuration: Long = 250,

        val appearingInterpolator: TimeInterpolator = INTERPOLATOR_ACCEL_DECEL,
        val disappearingInterpolator: TimeInterpolator = INTERPOLATOR_ACCEL_DECEL,
        val changeAppearingInterpolator: TimeInterpolator = INTERPOLATOR_DECEL,
        val changeDisappearingInterpolator: TimeInterpolator = INTERPOLATOR_DECEL,
        val changingInterpolator: TimeInterpolator = INTERPOLATOR_DECEL
    )

    companion object {
        const val TRANSITION_CONFIG_NONE = "none"
        const val TRANSITION_CONFIG_FAST = "fast"
        const val TRANSITION_CONFIG_SMOOTH = "smooth"
        const val TRANSITION_CONFIG_SLOW = "slow"

        val INTERPOLATOR_ACCEL_DECEL: TimeInterpolator = AccelerateDecelerateInterpolator()
        val INTERPOLATOR_DECEL: TimeInterpolator = DecelerateInterpolator()
        val INTERPOLATOR_LINEAR: TimeInterpolator = LinearInterpolator()

        val Fast = Config(
            appearingDuration = 180,
            disappearingDuration = 180,
            changeAppearingDuration = 150,
            changeDisappearingDuration = 150,
            changingDuration = 140,
        )

        val Smooth = Config(
            appearingDuration = 300,
            disappearingDuration = 300,
            changeAppearingDuration = 280,
            changeDisappearingDuration = 280,
            changingDuration = 250,
        )

        val Slow = Config(
            appearingDuration = 400,
            disappearingDuration = 400,
            changeAppearingDuration = 350,
            changeDisappearingDuration = 350,
            changingDuration = 320,
        )

        val None = Config(
            appearingDuration = 0,
            disappearingDuration = 0,
            changeAppearingDuration = 0,
            changeDisappearingDuration = 0,
            changingDuration = 0,
            appearingInterpolator = INTERPOLATOR_LINEAR,
            disappearingInterpolator = INTERPOLATOR_LINEAR,
            changeAppearingInterpolator = INTERPOLATOR_LINEAR,
            changeDisappearingInterpolator = INTERPOLATOR_LINEAR,
            changingInterpolator = INTERPOLATOR_LINEAR
        )
    }
}