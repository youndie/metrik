package ru.workinprogress.metrik.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusTest {
    @Test
    fun `successful statuses should collapse into their class`() {
        // Given / When / Then — 200, 201 и 204 это один ряд: различать их незачем.
        assertEquals(2, encodeStatus(200))
        assertEquals(2, encodeStatus(201))
        assertEquals(2, encodeStatus(204))
        assertEquals(3, encodeStatus(302))
    }

    @Test
    fun `error statuses should keep the exact code`() {
        // Given / When / Then — 401 против 404 и 500 против 503 это разные инциденты.
        assertEquals(401, encodeStatus(401))
        assertEquals(404, encodeStatus(404))
        assertEquals(500, encodeStatus(500))
        assertEquals(503, encodeStatus(503))
    }

    @Test
    fun `a missing response should encode as zero`() {
        // Given / When / Then
        assertEquals(STATUS_NO_RESPONSE, encodeStatus(0))
        assertEquals(STATUS_NO_RESPONSE, encodeStatus(-1))
    }

    @Test
    fun `non-standard codes should fall back to a class`() {
        // Given / When / Then
        assertEquals(5, encodeStatus(600))
    }

    @Test
    fun `class and error checks should work for both encodings`() {
        // Given / When / Then
        assertEquals(2, statusClassOf(encodeStatus(200)))
        assertEquals(4, statusClassOf(encodeStatus(404)))
        assertEquals(5, statusClassOf(encodeStatus(503)))

        assertTrue(isServerError(encodeStatus(500)))
        assertFalse(isServerError(encodeStatus(404)))
        assertFalse(isServerError(encodeStatus(200)))
    }
}
