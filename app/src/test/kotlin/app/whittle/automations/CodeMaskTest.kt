package app.whittle.automations

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeMaskTest {
    private val mask = CodeMask(testCodeMaskConfig)

    @Test
    fun findsOtpFormats() {
        listOf(
            "123456 is your OTP for txn of Rs 500.00",
            "Your verification code is 4821",
            "G-123456 is your Google verification code",
            "Use 908712 to log in to Swiggy",
            "OTP: 7781. Do not share",
            "Your WhatsApp code: 123-456. Don't share it",
            "123 456 is your verification code",
        ).forEach { assertTrue(mask.looksLikeCode(it), it) }
    }

    @Test
    fun findsCodesOfLettersAndDigits() {
        assertTrue(mask.looksLikeCode("Your login code is A7K29Q"))
        assertTrue(mask.looksLikeCode("Verification code: 4XK-29QB, valid 10 minutes"))
        // Without a keyword a mixed token is a reference, not a code.
        assertFalse(mask.looksLikeCode("Your parcel AB12CD is out for delivery"))
        // A currency and its amount run together aren't a code, even beside a keyword.
        assertFalse(mask.looksLikeCode("INR500 debited, verify it in the app if this wasn't you"))
    }

    @Test
    fun amountsAndDatesAreNotCodes() {
        assertFalse(mask.looksLikeCode("Rs 1,250.00 debited from A/c XX1234 on 24-10-26 to VPA x@ybl. Not you? Call 18002586161"))
        // Even beside a keyword, an amount is not a code.
        assertFalse(mask.looksLikeCode("Rs 12500.00 paid, ref code abc"))
        assertFalse(mask.looksLikeCode("Rs. 4500 debited, verify it"))
    }

    @Test
    fun aBankTextWithTheOtpBoilerplateIsKept() {
        listOf(
            "Rs.500.00 debited from A/c XX1234 on 24-10-26 to VPA shop@ybl. Never share your OTP/PIN with anyone. Not you? Call 18002586161",
            "Rs.500.00 debited from A/c **1234. Never share your OTP/PIN",
            "Rs.500.00 debited from A/c XX1234. Never share your OTP, PIN or password",
            "INR 420 spent on card ending 4821 at Zomato. Never share your OTP",
            "Rs 450 paid at Zomato, UPI ref 845123. Never share your PIN",
        ).forEach { assertFalse(mask.looksLikeCode(it), it) }
    }

    @Test
    fun aReferenceNumberIsNotACode() {
        assertFalse(mask.looksLikeCode("Your parcel AWB 123456 is out for delivery"))
        assertFalse(mask.looksLikeCode("Payment received, ref 845123"))
        assertFalse(mask.looksLikeCode("Txn ID: 845123"))
        // A keyword right before the label keeps it a code.
        assertTrue(mask.looksLikeCode("Your OTP ref 845123"))
        assertTrue(mask.looksLikeCode("Your OTP for order 40781234 is 7781"))
    }

    @Test
    fun keywordsAreWholeWords() {
        assertFalse(mask.looksLikeCode("Ping me later", app = "com.whatsapp"))
        assertFalse(mask.looksLikeCode("Is the codec ready? Pinterest link", app = "com.whatsapp"))
        assertTrue(mask.looksLikeCode("My PIN is on the fridge", app = "com.whatsapp"))
        assertTrue(mask.looksLikeCode("Your codes: 4821", app = null))
        assertTrue(mask.looksLikeCode("Your login code is A7K29Q"))
    }

    @Test
    fun deliveryWithAnOrderNumberIsKept() {
        assertFalse(mask.looksLikeCode("Your order 40781234 is out for delivery"))
    }

    @Test
    fun aKeywordFarAwayDoesNotCount() {
        assertFalse(mask.looksLikeCode("Your OTP is below. " + "x".repeat(60) + " ticket 55512 ends"))
    }

    @Test
    fun aLongMessageWithASixDigitNumberAndNoKeywordIsKept() {
        assertFalse(mask.looksLikeCode("The invoice 123456 for the quarterly report was sent to the whole team this morning and they all read it"))
        // A short one is taken for a code, keyword or not.
        assertTrue(mask.looksLikeCode("482913 is yours"))
    }

    @Test
    fun aSplitNumberWithoutAKeywordIsKept() {
        assertFalse(mask.looksLikeCode("Call me on 555 123 or 555-123 after the long meeting about the roadmap and then the budget review"))
    }

    @Test
    fun aMessagingAppsNotificationWithAnyKeywordCounts() {
        val text = "Mum: here's the login for the bank, use the one I sent"
        assertTrue(mask.looksLikeCode(text, app = "com.whatsapp"))
        assertFalse(mask.looksLikeCode(text, app = "com.bank"))
        assertFalse(mask.looksLikeCode(text))
        assertFalse(mask.looksLikeCode("Mum: see you at 7", app = "com.whatsapp"))
    }

    @Test
    fun badPatternIsRejected() {
        assertFailsWith<IllegalArgumentException> { testCodeMaskConfig.copy(codePattern = "(") }
        assertFailsWith<IllegalArgumentException> { testCodeMaskConfig.copy(mixedCodePattern = "(") }
        assertFailsWith<IllegalArgumentException> { testCodeMaskConfig.copy(amountPattern = "(") }
        assertFailsWith<IllegalArgumentException> { testCodeMaskConfig.copy(datePattern = "(") }
        assertFailsWith<IllegalArgumentException> { testCodeMaskConfig.copy(currencyBeforePattern = "(") }
        assertFailsWith<IllegalArgumentException> { testCodeMaskConfig.copy(maskedAccountPattern = "(") }
        assertFailsWith<IllegalArgumentException> { testCodeMaskConfig.copy(referenceLabelPattern = "(") }
        assertFailsWith<IllegalArgumentException> { testCodeMaskConfig.copy(shortMessageWords = 0) }
    }
}
