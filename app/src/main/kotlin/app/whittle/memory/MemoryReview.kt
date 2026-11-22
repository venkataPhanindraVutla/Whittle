package app.whittle.memory

import app.whittle.agent.MemoryStore
import app.whittle.agent.OneShot
import app.whittle.chat.ThreadItem
import app.whittle.core.common.Outcome
import app.whittle.core.config.PromptTemplate
import app.whittle.core.model.ModelId
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.ModelCapabilities
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ReasoningEffort
import app.whittle.llm.api.TrustTier
import app.whittle.settings.MemoryReviewDefaults
import app.whittle.settings.ReviewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the user themselves typed in a chat, in order. Never options picked on a question card (the model wrote them,
 * perhaps after reading a web page), text shared in from another app or written by the app for them (Undo, Ask to fix:
 * they carry names the model chose), narration, steps, tool output, pasted content, screen text or what attachments
 * hold: the agent's side of a chat can carry words a web page or a file planted, and only the user's own words may
 * teach it about them. Chats saved before picks were marked have a pick as the User item right after its Question,
 * equal to one of the options; that counts as a pick too.
 */
fun userWords(items: List<ThreadItem>): List<String> =
    items.filterIndexed { index, item ->
        item is ThreadItem.User && !item.shared && !item.fromCard && !isOptionOf(items.getOrNull(index - 1), item.text)
    }.map { (it as ThreadItem.User).text }.filter(String::isNotBlank)

private fun isOptionOf(previous: ThreadItem?, text: String) = previous is ThreadItem.Question && text in previous.text.options

/** What a review calls: [model] with [effort] as its reasoning setting (the model is told to reason only if [ReviewModel.effort] is set). */
data class ReviewCall(val model: ModelDescriptor, val effort: ReasoningEffort)

/**
 * The cheaper model [models] names for [provider], the one the chat's own tasks ran on, never another provider's.
 * None when the chat has no known provider, [provider] can't be [reached] with an API key (Claude Code runs on the user's
 * plan), or it has no entry: a review never falls back to the chat's own, dearer model.
 */
fun reviewModel(provider: ProviderId?, models: Map<String, ReviewModel>, reached: (ProviderId) -> Boolean): ReviewCall? {
    if (provider == null || !reached(provider)) return null
    val entry = models[provider.value] ?: return null
    // No effort means a model with no reasoning setting to send; it may refuse one. No tools, no photos: it only reads words.
    val capabilities = ModelCapabilities(toolCalling = false, parallelToolCalls = false, vision = false, promptCaching = false, reasoning = entry.effort != null)
    val model = ModelDescriptor(ModelId(entry.id), provider, entry.id, contextWindowTokens = null, capabilities = capabilities, trustTier = TrustTier.C)
    return ReviewCall(model, entry.effort ?: ReasoningEffort.NONE)
}

/**
 * The memory review: after a chat, one model call reads the user's own words and proposes lasting facts about them.
 * Proposals only wait in [MemorySuggestions]; nothing becomes a memory until the user keeps it.
 */
class MemoryReview(
    private val oneShot: OneShot,
    private val suggestions: MemorySuggestions,
    private val memory: MemoryStore,
    private val prompt: PromptTemplate,
    private val inputPrompt: PromptTemplate,
    private val config: MemoryReviewDefaults,
    private val settings: () -> Boolean,
) {
    /** Whether the user has the review on; callers check it before spending any work on a chat. */
    val enabled: Boolean get() = settings()

    @Serializable
    private class Proposal(val text: String = "", val replaces: String? = null)

    @Serializable
    private class Proposals(val facts: List<Proposal> = emptyList())

    /**
     * Reviews what the user said in [items] since the last review, unless the setting is off, there's no model to call
     * (see [reviewModel]; [provider] is the one the chat's own tasks ran on), the chat is an [automation] (its words are Whittle's own
     * prompt) or nothing new was said. A failed call, a reply cut off by the output limit or one that isn't the JSON asked
     * for leaves the chat unreviewed so a later review tries again; a reply with no facts in it is a review.
     */
    suspend fun review(chatId: String, items: List<ThreadItem>, provider: ProviderId?, automation: Boolean) {
        if (!enabled || automation) return
        val call = reviewModel(provider, config.models, oneShot::reaches) ?: return
        val words = userWords(items)
        val reviewed = suggestions.reviewedCount(chatId)
        if (words.size <= reviewed) return
        val remembered = memory.current()
        val system = (prompt.render(mapOf("maxSuggestions" to config.maxSuggestions.toString())) as? Outcome.Success)?.value ?: return
        val seen = suggestions.seen()
        val input = input(remembered.map { "${it.id}: ${it.text}" }, seen, before = words.take(reviewed), new = words.drop(reviewed)) ?: return
        val reply = oneShot.complete(call.model, system, input, config.maxOutputTokens, call.effort)
        val text = (reply as? Outcome.Success)?.value ?: return
        val known = remembered.map { it.id }.toSet()
        val facts = (parse(text) ?: return).filter { it.text.isNotBlank() }.take(config.maxSuggestions)
            .map { it.text to it.replaces?.takeIf(known::contains) }
        if (facts.isNotEmpty() && suggestions.add(chatId, facts) is Outcome.Failure) return
        suggestions.markReviewed(chatId, words.size)
    }

    /**
     * The remembered lines, what was already suggested or declined, the end of what the user said [before] (reviewed
     * already, cut to [MemoryReviewDefaults.contextChars]), then their [new] words with the newest kept: the oldest end
     * is cut to fit [MemoryReviewDefaults.maxInputChars]. Words reviewed before are never sent whole again.
     */
    private fun input(remembered: List<String>, seen: SeenSuggestions, before: List<String>, new: List<String>): String? {
        val variables = mapOf(
            "remembered" to remembered.joinToString("\n"),
            "waiting" to seen.waiting.joinToString("\n"),
            "declined" to seen.declined.joinToString("\n"),
            "context" to before.joinToString("\n").takeLast(config.contextChars),
            "words" to new.joinToString("\n").takeLast(config.maxInputChars),
        )
        return (inputPrompt.render(variables) as? Outcome.Success)?.value?.trimEnd()
    }

    /** The reply from its first `{` to its last `}`, read once, so prose around the JSON is fine; null when it isn't JSON. */
    private fun parse(text: String): List<Proposal>? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString<Proposals>(text.substring(start, end + 1)) }.getOrNull()?.facts
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
