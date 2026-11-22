package app.whittle.automations.ui

import app.whittle.automations.Edge
import app.whittle.automations.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals

class KeptToolsTest {
    private val choices = listOf(
        ToolChoice("web", "travel_explore", "Web", toolIds = listOf("web_fetch"), web = true),
        ToolChoice("clock", "schedule", "Clock", toolIds = listOf("device_clock_now")),
        ToolChoice("changeFiles", "edit_document", "Change files", toolIds = listOf("write_file"), writes = true),
    )
    private val all = setOf("web_fetch", "device_clock_now", "write_file")

    @Test
    fun aTextOrNotificationMadeInTheEditorHasNoWebAndNoWrites() {
        assertEquals(setOf("device_clock_now"), keptTools(Trigger.Sms(listOf("HDFCBK")), all, choices))
        assertEquals(setOf("device_clock_now"), keptTools(Trigger.Notification("com.bank", "Bank"), all, choices))
    }

    @Test
    fun wifiOnAnyNetworkKeepsTheWebButNotWrites() {
        assertEquals(setOf("web_fetch", "device_clock_now"), keptTools(Trigger.Wifi(null, Edge.START), all, choices))
        assertEquals(all, keptTools(Trigger.Wifi("Home", Edge.START), all, choices))
    }

    @Test
    fun otherTriggersKeepEverything() {
        assertEquals(all, keptTools(null, all, choices))
        assertEquals(all, keptTools(Trigger.Charging(Edge.START), all, choices))
        assertEquals(all, keptTools(Trigger.Every(60), all, choices))
    }
}
