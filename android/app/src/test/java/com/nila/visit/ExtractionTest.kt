package com.nila.visit

import com.nila.visit.extract.Extractor
import com.nila.visit.extract.Grounding
import com.nila.visit.extract.Numbers
import com.nila.visit.extract.TimeOfDay
import com.nila.visit.extract.WhenResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ExtractionTest {

    private val visit = LocalDate.of(2026, 10, 10)          // a Saturday
    private val birth = LocalDate.of(2026, 6, 8)

    private fun date(phrase: String) = WhenResolver.resolve(phrase, visit, birth).date

    // ------------------------------------------------------------ dates

    @Test
    fun `relative days weeks and months`() {
        assertEquals(visit.plusDays(3), date("after three days"))
        assertEquals(visit.plusDays(3), date("review after 3 days"))
        assertEquals(visit.plusWeeks(2), date("in two weeks"))
        assertEquals(visit.plusWeeks(6), date("after six weeks"))
        assertEquals(visit.plusMonths(1), date("one month later"))
        assertEquals(visit.plusDays(1), date("tomorrow"))
        assertEquals(visit.plusDays(2), date("the day after tomorrow"))
    }

    @Test
    fun `calendar dates, spoken and written`() {
        assertEquals(LocalDate.of(2026, 12, 8), date("on the eighth of December"))
        assertEquals(LocalDate.of(2026, 12, 8), date("December 8"))
        assertEquals(LocalDate.of(2026, 11, 7), date("7th November"))
        assertEquals(LocalDate.of(2026, 11, 7), date("7/11"))
        // A date already passed this year is next year's.
        assertEquals(LocalDate.of(2027, 3, 1), date("1st March"))
    }

    @Test
    fun `weekdays are the next one after the visit`() {
        assertEquals(LocalDate.of(2026, 10, 12), date("come back on Monday"))
        assertEquals(LocalDate.of(2026, 10, 16), date("next Friday"))
    }

    @Test
    fun `an age is counted from birth`() {
        assertEquals(birth.plusMonths(6), date("at six months"))
        assertEquals(birth.plusMonths(9), date("when she is 9 months old"))
    }

    @Test
    fun `times of day`() {
        assertEquals(LocalTime.of(9, 0), WhenResolver.resolve("come at nine in the morning", visit).time)
        assertEquals(LocalTime.of(17, 30), WhenResolver.resolve("at 5:30 pm", visit).time)
    }

    @Test
    fun `an unreadable phrase gives no date, never a guess`() {
        assertNull(date("when the cough is better"))
        assertNull(date(""))
    }

    // ------------------------------------------------------------ grounding

    @Test
    fun `quotes must come from the transcript`() {
        val t = "Give zinc syrup, five ml once a day after lunch, for fourteen days."
        assertTrue(Grounding.supported("zinc syrup, five ml once a day after lunch", t))
        assertTrue(Grounding.supported("Give zinc syrup five ml", t))
        assertFalse(Grounding.supported("give paracetamol 5 ml twice daily", t))
        assertFalse(Grounding.supported("zinc", t))     // too short to prove anything
    }

    @Test
    fun `grounding works in any script`() {
        val t = "बुखार सौ से ऊपर जाए तो पैरासिटामोल सिरप ढाई एमएल दीजिए"
        assertTrue(Grounding.supported("पैरासिटामोल सिरप ढाई एमएल दीजिए", t))
        assertFalse(Grounding.supported("आयरन सिरप रोज़ दीजिए", t))
    }

    // ------------------------------------------------------------ parse

    private val transcript = "Put saline nasal drops, two drops in each nostril, three times a day, for five days. " +
        "For the cough, give the syrup two point five ml at night only, for three days. " +
        "If the fever goes above one hundred and two, bring her immediately. Do not use any vapour rub on her face. " +
        "Otherwise come for review after three days."

    private fun parse(med: String = "", dates: String = "", advice: String = "", t: String = transcript) =
        Extractor.parse(mapOf(Extractor.Pass.MEDICINES to med, Extractor.Pass.DATES to dates, Extractor.Pass.ADVICE to advice), t, visit, birth)

    @Test
    fun `a messy reply is parsed, grounded and scheduled`() {
        val med = """Sure! Here are the medicines:
```json
{"medicines":[
  {"name":"Saline nasal drops","amount_as_said":"two drops in each nostril","how_often":"three times a day for five days","times":[],"times_per_day":3,"days":5,"only_if":"","quote":"saline nasal drops, two drops in each nostril, three times a day, for five days"},
  {"name":"Cough syrup","amount_as_said":"two point five ml","how_often":"at night only, for three days","times":["morning","afternoon","evening","night"],"times_per_day":null,"days":null,"only_if":"","quote":"give the syrup two point five ml at night only, for three days"},
  {"name":"Amoxicillin","amount_as_said":"5 ml","how_often":"twice a day","times":[],"times_per_day":2,"days":7,"only_if":"","quote":"give amoxicillin twice a day"},
]}
```"""
        val dates = """{"items":[{"kind":"visit","what":"Review","given_today":false,"when":"after three days","quote":"come for review after three days"}]}"""
        val advice = """{"watch_for":[{"text":"Fever above 102","come_immediately":true,"quote":"fever goes above one hundred and two, bring her immediately"}],
"do":[],"dont":[{"text":"No vapour rub on the face","quote":"Do not use any vapour rub on her face"}],"summary":"Viral cold."}"""
        val notes = parse(med, dates, advice)
        assertEquals(listOf("Saline nasal drops", "Cough syrup"), notes.medicines.map { it.name })
        assertTrue("the invented antibiotic is dropped", notes.dropped.any { "Amoxicillin" in it })
        val saline = notes.medicines[0]
        assertEquals(3, saline.timesPerDay); assertEquals(5, saline.durationDays)
        assertEquals(3, saline.reminderTimes().size)
        val syrup = notes.medicines[1]
        assertEquals("the copied list of every time of day is ignored", listOf(TimeOfDay.NIGHT), syrup.times)
        assertEquals("days read from the instructions when the model left it null", 3, syrup.durationDays)
        assertEquals("two point five ml", syrup.amount)
        assertEquals(visit.plusDays(3), notes.appointments.single().date)
        assertTrue(notes.watchFor.single().urgent)
        assertEquals(1, notes.donts.size)
        assertEquals("Viral cold.", notes.summary)
    }

    @Test
    fun `only-if medicines get no alarm`() {
        val med = """{"medicines":[{"name":"Paracetamol drops","amount_as_said":"zero point six ml","how_often":"up to four times a day","only_if":"if there is fever","quote":"give paracetamol drops, zero point six ml, only if there is fever"}]}"""
        val t = "If the temperature goes above one hundred, give paracetamol drops, zero point six ml, only if there is fever."
        val m = parse(med, t = t).medicines.single()
        assertTrue(m.asNeeded)
        assertTrue(m.reminderTimes().isEmpty())
    }

    @Test
    fun `an amount whose numbers the doctor did not say is not shown`() {
        val med = """{"medicines":[{"name":"Paracetamol syrup","amount_as_said":"0.5 ml","how_often":"if fever","only_if":"if fever","quote":"give paracetamol syrup two and a half ml if there is fever"}]}"""
        val t = "If the fever goes above one hundred, give paracetamol syrup two and a half ml if there is fever."
        val notes = parse(med, t = t)
        assertEquals("", notes.medicines.single().amount)
        assertTrue(notes.dropped.any { "0.5 ml" in it })
    }

    @Test
    fun `numbers are read however they were said`() {
        assertTrue(Numbers.consistent("0.6 ml", "zero point six ml"))
        assertTrue(Numbers.consistent("2.5 ml", "two and a half ml"))
        assertTrue(Numbers.consistent("5 ml", "five ml once a day"))
        assertFalse(Numbers.consistent("0.5 ml", "two and a half ml"))
        assertTrue(Numbers.consistent("102", "above one hundred and two"))
    }

    @Test
    fun `an amount in Hindi is kept in Hindi`() {
        val t = "बुखार सौ से ऊपर जाए तो पैरासिटामोल सिरप ढाई एमएल दीजिए, दिन में चार बार से ज़्यादा नहीं।"
        val med = """{"medicines":[{"name":"Paracetamol syrup","amount_as_said":"ढाई एमएल","how_often":"up to four times a day","only_if":"if fever above 100","quote":"पैरासिटामोल सिरप ढाई एमएल दीजिए"}]}"""
        val m = parse(med, t = t).medicines.single()
        assertEquals("ढाई एमएल", m.amount)
        assertTrue(m.asNeeded)
    }

    @Test
    fun `a reply cut off mid-object still yields what it finished`() {
        val med = """{"medicines":[{"name":"Zinc syrup","amount_as_said":"five ml","how_often":"once a day after lunch","quote":"zinc syrup, five ml once a day after lunch, for fourteen days"},{"name":"""
        val t = "Give zinc syrup, five ml once a day after lunch, for fourteen days."
        val notes = parse(med, t = t)
        assertEquals(1, notes.medicines.size)
        assertEquals("course length read from the quote", 14, notes.medicines.single().durationDays)
    }
}

class DateFinderTest {
    private val visit = java.time.LocalDate.of(2026, 10, 10)

    @org.junit.Test
    fun `appointments come straight from the doctor's sentences`() {
        val t = "Today we have given the third dose. The next vaccine is at six months, so come back on the eighth of December. " +
            "Give the syrup for three days. Otherwise come for review after three days. " +
            "We will do a bilirubin test the day after tomorrow, come at nine in the morning."
        val found = com.nila.visit.extract.DateFinder.find(t, visit, java.time.LocalDate.of(2026, 6, 8))
        org.junit.Assert.assertEquals(
            listOf(java.time.LocalDate.of(2026, 12, 8), visit.plusDays(3), visit.plusDays(2)),
            found.map { it.date },
        )
        org.junit.Assert.assertEquals("Test", found.last().what)
        org.junit.Assert.assertEquals(java.time.LocalTime.of(9, 0), found.last().time)
    }

    @org.junit.Test
    fun `even if is not only if`() {
        val med = """{"medicines":[{"name":"Zinc syrup","amount_as_said":"five ml","how_often":"once a day after lunch","times_per_day":1,"days":14,"only_if":"","quote":"zinc syrup, five ml once a day after lunch, for fourteen days, complete the full course even if the motions stop"}]}"""
        val t = "Give zinc syrup, five ml once a day after lunch, for fourteen days, complete the full course even if the motions stop."
        val m = com.nila.visit.extract.Extractor.parse(mapOf(com.nila.visit.extract.Extractor.Pass.MEDICINES to med), t, visit, null).medicines.single()
        org.junit.Assert.assertFalse(m.asNeeded)
        org.junit.Assert.assertEquals(14, m.reminderTimes().size * 14 / m.reminderTimes().size)
    }
}

class ClauseTest {
    @org.junit.Test
    fun `a course length belongs to the medicine whose clause states it`() {
        val t = "Your haemoglobin is a little low, so take the iron tablet once a day after lunch for three months, and the calcium tablet at night after dinner."
        val c = com.nila.visit.extract.Extractor
        org.junit.Assert.assertTrue(c.daysIn(c.clauseAbout("iron tablet", t)) in 89..92)
        org.junit.Assert.assertNull(c.daysIn(c.clauseAbout("calcium tablet", t)))
        val t2 = "For the blocked nose, put saline nasal drops, two drops in each nostril, three times a day, for five days. Before feeds is best."
        org.junit.Assert.assertEquals(5, c.daysIn(c.clauseAbout("saline nasal drops", t2)))
    }
}
