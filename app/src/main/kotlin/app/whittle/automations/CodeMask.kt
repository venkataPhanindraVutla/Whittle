package app.whittle.automations

import java.util.regex.Pattern

/**
 * Keeps one-time codes from the model: [looksLikeCode] says when an event seems to carry one, and such an event is
 * dropped whole, never stored. (Hiding the code and keeping the rest missed codes of letters and digits.) A number that
 * looks like a code ([CodeMaskConfig.codePattern]) counts when a keyword such as "OTP" is within
 * [CodeMaskConfig.nearChars] of it, and so does a code of letters and digits ([CodeMaskConfig.mixedCodePattern]).
 * Amounts, dates, masked account numbers ("XX1234") and numbers a label calls a reference ("ref 845123") never count,
 * unless a keyword stands right before the label. When unsure it counts: a lone 6-digit number in a short message does even with no
 * keyword.
 */
class CodeMask(private val config: CodeMaskConfig) {
    private val code = Pattern.compile(config.codePattern, Pattern.CASE_INSENSITIVE)
    private val mixedCode = Pattern.compile(config.mixedCodePattern, Pattern.CASE_INSENSITIVE)
    private val amount = Pattern.compile(config.amountPattern)
    private val date = Pattern.compile(config.datePattern)
    private val currencyBefore = Pattern.compile(config.currencyBeforePattern, Pattern.CASE_INSENSITIVE)
    private val maskedAccount = Pattern.compile(config.maskedAccountPattern, Pattern.CASE_INSENSITIVE)
    private val referenceLabel = Pattern.compile(config.referenceLabelPattern, Pattern.CASE_INSENSITIVE)
    // A keyword is a whole word, with an optional plural: "pins" counts, "ping" and "codec" don't.
    private val keywords = config.keywords.joinToString("|", prefix = "(?<![\\p{L}\\p{N}])(?:", postfix = ")s?(?![\\p{L}\\p{N}])") { Pattern.quote(it) }
    private val keyword = Pattern.compile(keywords, Pattern.CASE_INSENSITIVE)
    // A keyword that ends right where a label begins ("OTP ref 845123", "code is ref 845123").
    private val keywordJustBefore = Pattern.compile("$keywords\\W*(?:is\\W+)?$", Pattern.CASE_INSENSITIVE)

    /**
     * Whether [text] seems to hold a one-time code: a number or a letters-and-digits code beside a keyword, a lone
     * six-digit number in a short message, or, in a notification from one of [CodeMaskConfig.messagingApps] ([app]),
     * any keyword at all, since codes are forwarded and sent through those too.
     */
    fun looksLikeCode(text: String, app: String? = null): Boolean =
        hasNumericCode(text) || hasMixedCode(text) || (app != null && app in config.messagingApps && keyword.matcher(text).find())

    private fun hasNumericCode(text: String): Boolean {
        val found = code.matcher(text)
        val candidates = buildList {
            while (found.find()) if (!isAmountOrDate(text, found.start(), found.end())) add(found.start() to found.end())
        }
        if (candidates.isEmpty()) return false
        val loneSixDigits = candidates.size == 1 && wordCount(text) <= config.shortMessageWords && isSixDigits(text, candidates.single())
        return loneSixDigits || candidates.any { (start, end) -> keywordNear(text, start, end) }
    }

    /** A code like "A7K29Q" beside a keyword; a currency and its amount run together ("INR500") isn't one. */
    private fun hasMixedCode(text: String): Boolean {
        val found = mixedCode.matcher(text)
        while (found.find()) {
            val letters = found.group().takeWhile { !it.isDigit() }
            if (letters.isNotEmpty() && currencyBefore.matcher(letters).find()) continue
            if (!isAmountOrDate(text, found.start(), found.end()) && keywordNear(text, found.start(), found.end())) return true
        }
        return false
    }

    private fun keywordNear(text: String, start: Int, end: Int): Boolean {
        val before = keyword.matcher(text).region(maxOf(0, start - config.nearChars), start)
        val after = keyword.matcher(text).region(end, minOf(text.length, end + config.nearChars))
        // Transparent bounds so a keyword's first letter is judged by what really precedes it, not the window edge.
        return listOf(before, after).any { it.useTransparentBounds(true).useAnchoringBounds(false).find() }
    }

    private fun isAmountOrDate(text: String, start: Int, end: Int): Boolean =
        overlaps(amount, text, start, end) || overlaps(date, text, start, end) || overlaps(maskedAccount, text, start, end) ||
            currencyBefore.matcher(text).region(maxOf(0, start - CURRENCY_LOOKBACK), start).find() || isReference(text, start)

    /** A number a label such as "ref" or "AWB" stands before, when no keyword stands right before that label. */
    private fun isReference(text: String, start: Int): Boolean {
        val from = maxOf(0, start - config.nearChars)
        // Anchoring bounds stay on: both patterns end with $, which must match at the region's end.
        val label = referenceLabel.matcher(text).region(from, start).useTransparentBounds(true)
        if (!label.find()) return false
        val before = keywordJustBefore.matcher(text).region(from, label.start()).useTransparentBounds(true)
        return !before.find()
    }

    private fun overlaps(pattern: Pattern, text: String, start: Int, end: Int): Boolean {
        val m = pattern.matcher(text)
        while (m.find()) if (m.start() < end && start < m.end()) return true
        return false
    }

    private fun isSixDigits(text: String, span: Pair<Int, Int>): Boolean =
        text.substring(span.first, span.second).count { it.isDigit() } == config.loneCodeDigits

    private fun wordCount(text: String): Int = text.trim().split(WHITESPACE).count { it.isNotEmpty() }

    private companion object {
        const val CURRENCY_LOOKBACK = 8
        val WHITESPACE = Regex("\\s+")
    }
}
