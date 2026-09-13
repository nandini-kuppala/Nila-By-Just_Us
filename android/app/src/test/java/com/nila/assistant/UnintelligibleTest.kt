package com.nila.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Letters are refused; words are not.
 *
 * The second half is the one that matters. A rule that catches "tuhgj" and
 * also catches "fingernails" has made the app worse, so every word here that a
 * parent might plausibly type has to survive -- including the ones the corpus
 * has never heard of, which is most of them.
 */
class UnintelligibleTest {

    private val index = Corpus.index

    @Test
    fun `keyboard mashing is refused`() {
        listOf(
            "tuhgj", "asdfgh", "qwrtpl", "zxcvbn", "sdfgh",
            "kkkk", "bcdfg hjklm", "xkcdz",
        ).forEach {
            assertTrue(it, index.isUnintelligible(it))
        }
    }

    @Test
    fun `real questions are never refused as letters`() {
        listOf(
            // Words the corpus does not contain.
            "hiccups", "swimming", "fingernails", "cradle cap", "teething",
            "strength", "months", "nappy rash", "reflux", "thrush",
            "growth spurt", "eczema", "wheezing", "constipation",
            // The wording people actually use.
            "why does my baby cry so much",
            "is paracetamol safe while breastfeeding",
            "when did she last feed",
            "how much crying is normal",
            "my breast is red and painful",
            "she wont stop crying",
        ).forEach {
            assertFalse(it, index.isUnintelligible(it))
        }
    }

    @Test
    fun `a typo inside a real question is still answered`() {
        assertFalse(index.isUnintelligible("is paracetmol safe while breastfeding"))
    }

    @Test
    fun `a long question is never refused as letters`() {
        assertFalse(index.isUnintelligible("zxcvbn asdfgh qwrtpl hjklm"))
    }

    /**
     * Short real words are the router's business, not this check's.
     *
     * "ok" and "help" are already routed -- as a greeting and as too vague --
     * and this must not take them off that path and call them letters.
     */
    @Test
    fun `short real words are left to the router`() {
        assertFalse(index.isUnintelligible("ok"))
        assertFalse(index.isUnintelligible("help"))
        assertNotEquals(QueryRouter.Route.Unintelligible, QueryRouter.route("ok"))
    }
}
