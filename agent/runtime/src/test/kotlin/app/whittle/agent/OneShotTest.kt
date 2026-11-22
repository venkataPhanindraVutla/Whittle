package app.whittle.agent

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ReasoningEffort
import app.whittle.llm.api.StopReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OneShotTest {
    private fun oneShot(adapter: ScriptedAdapter) = OneShot(ModelAdapterRegistry(setOf(adapter)), testCatalog, fixedKey)

    @Test
    fun concatenatesText() = runTest {
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("Hel"), ModelEvent.TextDelta("lo"))))

        val result = oneShot(adapter).complete(testModel(), "sys", "hi", 64)

        assertEquals("Hello", assertIs<Outcome.Success<String>>(result).value)
        val request = adapter.requests.single()
        assertEquals("sys", request.systemPrompt)
        assertEquals("hi", request.messages.single().text)
        assertEquals(64, request.maxOutputTokens)
        assertEquals(ReasoningEffort.LOW, request.reasoningEffort)
        assertTrue(request.tools.isEmpty())
    }

    @Test
    fun failureIsReported() = runTest {
        val error = HarnessError.Network("down")
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("par"), ModelEvent.Failed(error))))

        val result = oneShot(adapter).complete(testModel(), "sys", "hi", 64)

        assertEquals(error, assertIs<Outcome.Failure>(result).error)
    }

    @Test
    fun `a reply cut off by the output limit is a failure`() = runTest {
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("{\"facts\":"), ModelEvent.Completed(StopReason.MAX_TOKENS))))

        val result = oneShot(adapter).complete(testModel(), "sys", "hi", 64)

        assertIs<Outcome.Failure>(result)
    }

    @Test
    fun `the effort asked for is the one sent`() = runTest {
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("ok"))))

        oneShot(adapter).complete(testModel(), "sys", "hi", 64, ReasoningEffort.HIGH)

        assertEquals(ReasoningEffort.HIGH, adapter.requests.single().reasoningEffort)
    }
}
