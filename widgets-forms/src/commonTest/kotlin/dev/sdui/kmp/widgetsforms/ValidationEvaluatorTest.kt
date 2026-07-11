package dev.sdui.kmp.widgetsforms

import dev.sdui.kmp.protocol.Validation
import dev.sdui.kmp.protocol.Value
import dev.sdui.kmp.runtime.StateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValidationEvaluatorTest {
    private val store = StateStore()

    @Test
    fun required_fails_on_empty_and_passes_on_nonempty() {
        val v: Validation = Validation.Required()
        assertEquals("Required", v.check("", store))
        assertNull(v.check("hello", store))
    }

    @Test
    fun min_length_enforces_lower_bound() {
        val v: Validation = Validation.MinLength(length = 3)
        assertEquals("Minimum 3 characters", v.check("ab", store))
        assertNull(v.check("abc", store))
    }

    @Test
    fun max_length_enforces_upper_bound() {
        val v: Validation = Validation.MaxLength(length = 5)
        assertEquals("Maximum 5 characters", v.check("longer input", store))
        assertNull(v.check("short", store))
    }

    @Test
    fun email_accepts_common_formats_and_rejects_obvious_non_emails() {
        val v: Validation = Validation.Email()
        assertNull(v.check("alice@example.com", store))
        assertNull(v.check("", store))
        assertEquals("Invalid email", v.check("not-an-email", store))
        assertEquals("Invalid email", v.check("@no-local.com", store))
    }

    @Test
    fun all_returns_first_failing_message() {
        val v: Validation = Validation.All(
            validations = listOf(
                Validation.Required(),
                Validation.MinLength(8),
                Validation.Email(),
            ),
        )
        assertEquals("Required", v.check("", store))
        assertEquals("Minimum 8 characters", v.check("short", store))
        assertEquals("Invalid email", v.check("longenough", store))
        assertNull(v.check("alice@example.com", store))
    }

    @Test
    fun custom_message_overrides_default() {
        val v: Validation = Validation.Required(message = Value.ofString("email required"))
        assertEquals("email required", v.check("", store))
    }
}
