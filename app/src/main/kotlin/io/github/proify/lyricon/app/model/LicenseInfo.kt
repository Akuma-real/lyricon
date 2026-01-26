/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.model

import kotlinx.serialization.Serializable

@Serializable
data class LicenseInfo(
    val name: String? = null,
    val url: String? = null
)