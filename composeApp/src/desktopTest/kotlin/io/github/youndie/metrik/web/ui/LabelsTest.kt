package io.github.youndie.metrik.web.ui

import io.github.youndie.metrik.web.navigation.Route
import io.github.youndie.metrik.web.navigation.fragment
import io.github.youndie.metrik.wire.encodeStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class LabelsTest {
    /** M-125: провод хранит успешные ответы классом, и чип «2» никто не читал как «2xx». */
    @Test
    fun `a status reads as its class for 1xx to 3xx and as its code for errors`() {
        assertEquals("2xx", statusLabel(encodeStatus(200)))
        assertEquals("3xx", statusLabel(encodeStatus(304)))
        assertEquals("404", statusLabel(encodeStatus(404)))
        assertEquals("503", statusLabel(encodeStatus(503)))
        assertEquals("no resp.", statusLabel(encodeStatus(0)))
    }

    /**
     * M-122: «Copy link» обязан вести туда же, куда пишет адресную строку история браузера
     * (`#service?id=3` — формат `navigation3-browser`); разойдясь, ссылка открывала бы «Обзор».
     */
    @Test
    fun `a route's link is the fragment the browser history writes`() {
        assertEquals("#overview", Route.Overview.fragment())
        assertEquals("#alerts", Route.Alerts.fragment())
        assertEquals("#services", Route.Services.fragment())
        assertEquals("#service?id=3", Route.Service(3).fragment())
    }
}
