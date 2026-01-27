/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("ReplacePrintlnWithLogging")

package io.makino.lyricon.model.lyric

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.extensions.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricWordNormalizeTest {

    @Test
    fun testEmptyListReturnsEmptyList() {
        val result = emptyList<LyricWord>().normalize()
        assertTrue(result.isEmpty())
    }

    @Test
    fun test1() {
        val words = listOf(
            createWord(begin = 0, end = 100, duration = 100, text = "Hello"),
            createWord(begin = 0, end = 0, duration = 0, text = ","),
            createWord(begin = 100, end = 200, duration = 100, text = "World"),
            createWord(begin = -1, end = -1, duration = 0, text = " gon"),
        )
        val result = words.normalize()
        println("test1 Result: size: ${result.size}, text: $result")

        assertEquals(2, result.size)
        assertEquals("Hello,", result[0].text)
        assertEquals("World gon", result[1].text)
    }

    @Test
    fun test2() {
        val words = listOf(
            createWord(begin = -1, end = -1, duration = 0, text = "?"),
            createWord(begin = 0, end = 100, duration = 100, text = "Hello"),
            createWord(begin = -1, end = -1, duration = 0, text = "?"),
            createWord(begin = 101, end = 200, duration = 100, text = "Hi"),
        )
        val result = words.normalize()
        println("test2 Result: size: ${result.size}, text: $result")

        assertEquals("?Hello", result[0].text)
        assertEquals(0, result[0].begin)
        assertEquals(100, result[0].end)

        assertEquals("?", result[1].text)
        assertEquals(100, result[1].begin)
        assertEquals(101, result[1].end)

        assertEquals("Hi", result[2].text)
    }

    @Test
    fun test3() {
        val words = listOf(
            createWord(begin = 0, end = 10, duration = 10, text = "a"),
            createWord(begin = 0, end = 0, duration = 0, text = "&"),
            createWord(begin = 10, end = 20, duration = 10, text = "b"),
        )
        val result = words.normalize()
        println("test3 Result: size: ${result.size}, text: $result")

        assertEquals(2, result.size)
    }

    @Test
    fun test4() {
        val words = listOf(
            createWord(begin = 0, end = 10, text = "a"),
            createWord(begin = 10, end = 20, text = "&"),
            createWord(begin = 30, end = 40, text = " "),
        )
        val result = words.normalize()
        println("test3 Result: size: ${result.size}, text: $result")

    }

    private fun createWord(
        begin: Long,
        end: Long,
        duration: Long = end - begin,
        text: String?
    ): LyricWord {
        return LyricWord().apply {
            this.begin = begin
            this.end = end
            this.duration = duration
            this.text = text
        }
    }
}