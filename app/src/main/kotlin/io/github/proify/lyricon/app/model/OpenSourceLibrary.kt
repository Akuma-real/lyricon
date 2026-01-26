/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.model

@kotlinx.serialization.Serializable
data class OpenSourceLibrary(
    val project: String? = null,
    val description: String? = null,
    val version: String? = null,
    val developers: List<String>? = null,
    val url: String? = null,
    val year: String? = null,
    val licenses: List<LicenseInfo>? = null,
    val dependency: String? = null
)