/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.proify.lyricon.app.LyriconApp
import io.github.proify.lyricon.app.R
import io.github.proify.lyricon.app.compose.AppToolBarListContainer
import io.github.proify.lyricon.app.compose.custom.miuix.basic.Card
import io.github.proify.lyricon.app.compose.custom.miuix.extra.IconActions
import io.github.proify.lyricon.app.compose.custom.miuix.extra.SpinnerEntry
import io.github.proify.lyricon.app.compose.custom.miuix.extra.SuperSpinner
import io.github.proify.lyricon.app.compose.custom.miuix.extra.SuperSwitch
import io.github.proify.lyricon.app.event.SettingChangedEvent
import io.github.proify.lyricon.app.util.AppLangUtils
import io.github.proify.lyricon.app.util.AppThemeUtils
import io.github.proify.lyricon.app.util.EventBus
import top.yukonga.miuix.kmp.extra.SuperDropdown
import java.util.Locale

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SettingsScreen(onSettingsChanged = ::restartActivity)
        }
    }

    private fun restartActivity() {
        EventBus.post(SettingChangedEvent)
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }
}

@Composable
internal fun SettingsScreen(onSettingsChanged: () -> Unit) {
    AppToolBarListContainer(
        title = stringResource(id = R.string.activity_settings),
        canBack = true
    ) {
        item("language_section") {
            SettingsCard {
                LanguageSelector(onSettingsChanged)
            }
        }
        item("theme_section") {
            SettingsCard(topPadding = 16.dp) {
                ThemeModeSelector(onSettingsChanged)
            }
        }
    }
}

@Composable
private fun SettingsCard(
    topPadding: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .padding(start = 16.dp, top = topPadding, end = 16.dp)
            .fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
private fun ThemeModeSelector(onChanged: () -> Unit) {
    val context = LocalContext.current
    val themeOptions = listOf(
        R.string.option_app_theme_mode_follow_system to AppThemeUtils.MODE_SYSTEM,
        R.string.option_app_theme_mode_light to AppThemeUtils.MODE_LIGHT,
        R.string.option_app_theme_mode_dark to AppThemeUtils.MODE_DARK
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val isMonetEnabled = remember { AppThemeUtils.isEnableMonetColor(context) }
        SuperSwitch(
            leftAction = { IconActions(painterResource(R.drawable.ic_palette)) },
            title = stringResource(R.string.item_app_theme_monet_color),
            checked = isMonetEnabled,
            onCheckedChange = {
                AppThemeUtils.setEnableMonetColor(context, it)
                onChanged()
            }
        )
    }

    val currentMode = remember { AppThemeUtils.getMode(context) }
    val selectedIndex = remember(currentMode) {
        themeOptions.indexOfFirst { it.second == currentMode }.coerceAtLeast(0)
    }

    SuperDropdown(
        leftAction = { IconActions(painterResource(R.drawable.ic_routine)) },
        title = stringResource(id = R.string.item_app_theme_mode),
        items = themeOptions.map { stringResource(it.first) },
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            if (index != selectedIndex) {
                AppThemeUtils.setMode(context, themeOptions[index].second)
                onChanged()
            }
        }
    )
}

@Composable
private fun LanguageSelector(onChanged: () -> Unit) {
    val context = LocalContext.current
    val languages = getLanguages().toMutableList().apply {
        sortBy { it }
        add(0, AppLangUtils.DEFAULT_LANGUAGE)
    }
    val currentLanguage = remember { AppLangUtils.getCustomizeLang(context) }

    val spinnerEntries = remember(languages) {
        languages.map { code ->
            val title = context.getLanguageDisplayName(code)
            val summary = context.getLanguageDisplayName(code, AppLangUtils.DEFAULT_LOCALE)
            SpinnerEntry(
                title = title,
                summary = if (summary == title) null else summary
            )
        }
    }

    val selectedIndex = remember(currentLanguage) {
        languages.indexOf(currentLanguage).coerceAtLeast(0)
    }

    SuperSpinner(
        leftAction = { IconActions(painterResource(R.drawable.ic_language)) },
        title = stringResource(id = R.string.item_app_language),
        items = spinnerEntries,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            AppLangUtils.saveCustomizeLanguage(context, languages[index])
            onChanged()
        }
    )
}

fun getLanguages() =
    LyriconApp.instance.resources.getStringArray(R.array.language_codes)

private fun Context.getLanguageDisplayName(
    languageCode: String,
    nameLocal: Locale? = null
): String {
    if (languageCode == AppLangUtils.DEFAULT_LANGUAGE) {
        return getString(R.string.option_language_follow_system)
    }
    return runCatching {
        val locale = Locale.forLanguageTag(languageCode)
        locale.getDisplayName(nameLocal ?: locale).capitalize(locale)
    }.getOrDefault(languageCode)
}

private fun String.capitalize(locale: Locale): String = replaceFirstChar {
    if (it.isLowerCase()) it.titlecase(locale) else it.toString()
}