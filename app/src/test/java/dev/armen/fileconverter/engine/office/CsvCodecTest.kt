package dev.armen.fileconverter.engine.office

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvCodecTest {
    @Test fun `parses simple rows`() =
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), CsvCodec.parse("a,b\n1,2\n"))

    @Test fun `keeps empty fields`() =
        assertEquals(listOf(listOf("a", "", "b", "")), CsvCodec.parse("a,,b,"))

    @Test fun `handles quoted delimiters, escaped quotes and newlines`() =
        assertEquals(
            listOf(listOf("x, y", "say \"hi\"", "line1\nline2")),
            CsvCodec.parse("\"x, y\",\"say \"\"hi\"\"\",\"line1\nline2\"\r\n"),
        )

    @Test fun `handles CRLF`() = assertEquals(listOf(listOf("a"), listOf("b")), CsvCodec.parse("a\r\nb\r\n"))

    @Test fun `strips a byte order mark`() = assertEquals(listOf(listOf("id")), CsvCodec.parse("﻿id"))

    @Test fun `detects semicolons and prefers them on ties`() {
        assertEquals(';', CsvCodec.detectDelimiter("a;b;c\n1;2;3"))
        assertEquals(listOf(listOf("1,5", "2")), CsvCodec.parse("1,5;2"))
    }

    @Test fun `detects tabs`() = assertEquals('\t', CsvCodec.detectDelimiter("a\tb\n"))

    @Test fun `ignores delimiters inside quotes when detecting`() =
        assertEquals(',', CsvCodec.detectDelimiter("\"a;b;c\",d\n"))

    @Test fun `defaults to comma`() = assertEquals(',', CsvCodec.detectDelimiter("single"))

    @Test fun `empty input has no rows`() = assertEquals(emptyList<List<String>>(), CsvCodec.parse(""))

    @Test fun `writes with quoting where needed`() =
        assertEquals(
            "a,\"b,c\",\"d\"\"e\"\r\n\" f\",\"x\ny\"\r\n",
            CsvCodec.write(listOf(listOf("a", "b,c", "d\"e"), listOf(" f", "x\ny"))),
        )

    @Test fun `round trips`() {
        val rows = listOf(listOf("id", "note"), listOf("1", "He said \"ok\", then left\nfor good"), listOf("2", ""))
        assertEquals(rows, CsvCodec.parse(CsvCodec.write(rows)))
    }
}
