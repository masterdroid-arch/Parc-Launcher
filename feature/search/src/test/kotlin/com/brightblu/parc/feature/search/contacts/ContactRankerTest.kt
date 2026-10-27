package com.brightblu.parc.feature.search.contacts

import com.brightblu.parc.model.MatchRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRankerTest {

    private fun contact(id: Long, name: String, vararg numbers: String) =
        ContactRecord(id, name, numbers.toList())

    // Deliberately invalid area code, generated so no literal contact data ships.
    private fun number(middle: String = "123", tail: String = "4567", separator: String = "-") =
        listOf("0".repeat(3), middle, tail).filter { it.isNotEmpty() }.joinToString(separator)

    private val shortNumber = number(middle = "", tail = "1234")
    private val shortDigits = shortNumber.filter(Char::isDigit)
    private val formattedNumber = "(" + "0".repeat(3) + ") 123-" + "4567"

    @Test
    fun `an exact folded name beats every other kind`() {
        val hits = ContactRanker.rank(
            "jose",
            listOf(contact(1, "Josef"), contact(2, "José"), contact(3, "María José")),
        )
        assertEquals("José", hits.first().contact.name)
    }

    @Test
    fun `match ranges point into the original accented name`() {
        val hit = ContactRanker.rank("jose", listOf(contact(1, "José"))).single()
        assertEquals(listOf(MatchRange(0, 4)), hit.matchRanges)
    }

    @Test
    fun `a prefix outranks a word prefix and a substring`() {
        val hits = ContactRanker.rank(
            "ann",
            listOf(
                contact(1, "Julianne"),
                contact(2, "Jo Anna"),
                contact(3, "Anna"),
            ),
        )
        assertEquals(listOf("Anna", "Jo Anna", "Julianne"), hits.map { it.contact.name })
        assertEquals(listOf(MatchRange(0, 3)), hits[0].matchRanges)
        assertEquals(listOf(MatchRange(3, 6)), hits[1].matchRanges)
    }

    @Test
    fun `a fuzzy name still matches`() {
        val hits = ContactRanker.rank("nra", listOf(contact(1, "Nora"), contact(2, "Jane")))
        assertEquals(listOf("Nora"), hits.map { it.contact.name })
    }

    @Test
    fun `a one letter query never uses the fuzzy path`() {
        assertTrue(ContactRanker.rank("z", listOf(contact(1, "Anna"))).isEmpty())
    }

    @Test
    fun `a full number is an exact number match`() {
        val hit = ContactRanker.rank(
            number(separator = " "),
            listOf(contact(7, "Sam", formattedNumber)),
        ).single()
        assertEquals(900.0, hit.score, 0.0)
        assertEquals(formattedNumber, hit.matchedNumber)
        assertTrue(hit.matchRanges.isEmpty())
    }

    @Test
    fun `a trailing number matches as a suffix`() {
        val hit = ContactRanker.rank("4567", listOf(contact(7, "Sam", number()))).single()
        assertEquals(700.0, hit.score, 0.0)
    }

    @Test
    fun `a middle of a number matches but ranks below a suffix`() {
        val hits = ContactRanker.rank(
            "123",
            listOf(contact(1, "Mid", number(tail = "9999")), contact(2, "Tail", number(middle = "999", tail = "0123"))),
        )
        assertEquals(listOf("Tail", "Mid"), hits.map { it.contact.name })
        assertEquals(700.0, hits.first().score, 0.0)
        assertEquals(500.0, hits.last().score, 0.0)
    }

    @Test
    fun `two digit fragments do not run a number search`() {
        assertTrue(ContactRanker.rank("00", listOf(contact(1, "Sam", shortNumber))).isEmpty())
    }

    @Test
    fun `a query mixing letters and digits is not treated as a phone number`() {
        assertTrue(ContactRanker.rank("sam5", listOf(contact(1, "Sam", shortNumber))).isEmpty())
    }

    @Test
    fun `an exact name outranks an exact number`() {
        val hits = ContactRanker.rank(
            shortDigits,
            listOf(contact(1, shortDigits, number(middle = "", tail = "0000")), contact(2, "Other", shortNumber)),
        )
        assertEquals(shortDigits, hits.first().contact.name)
        assertEquals(1000.0, hits.first().score, 0.0)
    }

    @Test
    fun `a duplicate name falls back to the id for a stable order`() {
        val hits = ContactRanker.rank(
            "sam",
            listOf(contact(9, "Sam"), contact(2, "Sam"), contact(4, "Sam")),
        )
        assertEquals(listOf(2L, 4L, 9L), hits.map { it.contact.id })
    }

    @Test
    fun `the limit keeps the best matches`() {
        val hits = ContactRanker.rank(
            "sam",
            listOf(contact(1, "Samuel"), contact(2, "Sam"), contact(3, "Sammy")),
            limit = 1,
        )
        assertEquals(listOf("Sam"), hits.map { it.contact.name })
    }

    @Test
    fun `a zero or negative limit returns nothing`() {
        val contacts = listOf(contact(1, "Sam"))
        assertTrue(ContactRanker.rank("sam", contacts, limit = 0).isEmpty())
        assertTrue(ContactRanker.rank("sam", contacts, limit = -3).isEmpty())
    }

    @Test
    fun `a blank query or an empty book returns nothing`() {
        assertTrue(ContactRanker.rank("   ", listOf(contact(1, "Sam"))).isEmpty())
        assertTrue(ContactRanker.rank("sam", emptyList()).isEmpty())
    }

    @Test
    fun `a contact without a name can still match a number`() {
        val hit = ContactRanker.rank(shortDigits, listOf(contact(5, "", shortNumber))).single()
        assertEquals(5L, hit.contact.id)
    }

    @Test
    fun `a contact without numbers can still match a name`() {
        val hit = ContactRanker.rank("sam", listOf(contact(5, "Sam"))).single()
        assertEquals(1000.0, hit.score, 0.0)
        assertEquals(null, hit.matchedNumber)
    }
}
