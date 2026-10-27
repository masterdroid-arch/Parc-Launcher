package com.brightblu.parc.feature.settings.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S13 script: order, uniqueness, copy hygiene. The screen is a renderer, so anything wrong with
 * the steps is wrong here rather than in a screenshot.
 */
class OnboardingScriptTest {

    @Test
    fun `the six steps keep the S13 order after the main-app picker`() {
        assertEquals(
            listOf(
                OnboardingStep.MainApps,
                OnboardingStep.Permissions,
                OnboardingStep.DefaultHome,
                OnboardingStep.RestrictedSettings,
                OnboardingStep.Battery,
                OnboardingStep.Autostart,
            ),
            OnboardingScript.steps.map { it.step },
        )
    }

    @Test
    fun `every step id is unique, stable and round trips through byId`() {
        val ids = OnboardingScript.steps.map { it.step.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it == it.lowercase() && it.none(Char::isWhitespace) })
        OnboardingStep.entries.forEach { step ->
            assertEquals(step, OnboardingStep.byId(step.id))
        }
        assertEquals(null, OnboardingStep.byId("no_such_step"))
    }

    @Test
    fun `every step has a title and body, and every settings step can open a page`() {
        OnboardingScript.steps.forEach { spec ->
            assertTrue(spec.title.isNotBlank())
            assertTrue("${spec.step} needs a body", spec.lines.isNotEmpty())
            if (spec.step != OnboardingStep.MainApps) {
                assertTrue("${spec.step} needs an action", spec.actions.isNotEmpty())
            }
            spec.lines.forEach { assertTrue(it.isNotBlank()) }
            spec.actions.forEach {
                assertTrue(it.label.isNotBlank())
                assertTrue(OnboardingTargets.candidatesFor(it.target).isNotEmpty())
            }
        }
    }

    @Test
    fun `the main-app picker opens no system page`() {
        assertTrue(OnboardingScript.spec(OnboardingStep.MainApps).actions.isEmpty())
    }

    @Test
    fun `the copy stays plain with no em dash, no emoji and no filler openers`() {
        val bannedWords = listOf("Moreover", "Furthermore", "Additionally", "It is worth noting")
        OnboardingScript.steps.forEach { spec ->
            val copy = (listOf(spec.title) + spec.lines + spec.actions.map { it.label })
            copy.forEach { text ->
                assertTrue("em dash in: $text", !text.contains('\u2014'))
                assertTrue("emoji in: $text", text.none(::isEmoji))
                bannedWords.forEach { word ->
                    assertTrue("filler '$word' in: $text", !text.contains(word))
                }
            }
        }
    }

    @Test
    fun `spec returns the entry for each step`() {
        OnboardingStep.entries.forEach { step ->
            assertEquals(step, OnboardingScript.spec(step).step)
        }
    }

    @Test
    fun `status labels are short, and an unreadable step claims nothing`() {
        assertEquals("Set", OnboardingScript.statusLabel(OnboardingStatus.Done))
        assertEquals("Not set yet", OnboardingScript.statusLabel(OnboardingStatus.Todo))
        assertEquals("", OnboardingScript.statusLabel(OnboardingStatus.Unknown))
    }

    private fun isEmoji(c: Char): Boolean =
        c.code in 0x1F300..0x1FAFF || c.code in 0x2600..0x27BF || c.code in 0x1F000..0x1F2FF
}
