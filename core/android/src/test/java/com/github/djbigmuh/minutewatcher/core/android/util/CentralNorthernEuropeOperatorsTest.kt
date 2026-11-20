package com.github.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.djbigmuh.minutewatcher.core.resources.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CentralNorthernEuropeOperatorsTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `test Central and Northern Europe PLMN database mappings`() {
        assertEquals("Telekom", resolveDisplayName("26201"))
        assertEquals("Telemach", resolveDisplayName("21902"))
        assertEquals("Telemach", resolveDisplayName("29370"))
        assertEquals("4ka", resolveDisplayName("23103"))
        assertEquals("1&1", resolveDisplayName("26223"))
        assertEquals("Magenta", resolveDisplayName("23203"))
        assertEquals("Yettel", resolveDisplayName("21601"))
        assertEquals("Digi", resolveDisplayName("21603"))
        assertEquals("bite", resolveDisplayName("24705"))
        assertEquals("operator_telia", resolvePlmn("24801"))
        assertEquals("operator_tele2", resolvePlmn("24603"))
        assertEquals("operator_tele2", resolvePlmn("24007"))
        assertEquals("operator_enreach", resolvePlmn("26220"))
        assertEquals("operator_sweden", resolvePlmn("24004"))
        assertEquals("operator_sweden_3g", resolvePlmn("24005"))
        assertEquals("Nema", resolveDisplayName("28802"))

        assertEquals("LMT", OperatorResolver.resolve(context, "", listOf("24701"), 0).displayName)
        assertEquals("operator_lmt", OperatorResolver.getOperatorBrandKey("LMT"))

        assertEquals("Síminn", OperatorResolver.resolve(context, "", listOf("27401"), 0).displayName)
        assertEquals("operator_siminn", OperatorResolver.getOperatorBrandKey("Síminn"))
    }

    @Test
    fun `test Romania operator mappings`() {
        val result22603 = OperatorResolver.resolve(context, "", listOf("22603"), 0)
        assertEquals("Vodafone", result22603.displayName)
        assertEquals("operator_vodafone", result22603.brandKey)

        val result22601 = OperatorResolver.resolve(context, "", listOf("22601"), 0)
        assertEquals("operator_vodafone", result22601.brandKey)

        val result22610 = OperatorResolver.resolve(context, "", listOf("22610"), 0)
        assertEquals("operator_orange", result22610.brandKey)
    }

    @Test
    fun `test Liechtenstein operator mappings`() {
        val res29501 = OperatorResolver.resolve(context, "", listOf("29501"), 0)
        assertEquals("operator_swisscom", res29501.brandKey)

        val res29502 = OperatorResolver.resolve(context, "", listOf("29502"), 0)
        assertEquals("operator_salt_li", res29502.brandKey)

        val res29505 = OperatorResolver.resolve(context, "", listOf("29505"), 0)
        assertEquals("FL1", res29505.displayName)
        assertEquals("operator_fl1", res29505.brandKey)
    }

    @Test
    fun `test Telekom Slovenije brand mappings`() {
        val res29341 = OperatorResolver.resolve(context, "", listOf("29341"), 0)
        assertEquals(context.getString(R.string.operator_telekom_si), res29341.displayName)
        assertEquals("operator_telekom_si", res29341.brandKey)

        val res29341Named = OperatorResolver.resolve(context, "Telekom Slovenije", emptyList(), 0)
        assertEquals("operator_telekom_si", res29341Named.brandKey)

        assertEquals("operator_a1", resolvePlmn("29340"))
        assertEquals("operator_telemach", resolvePlmn("29370"))
        assertEquals("operator_telekom", resolvePlmn("26201"))
    }

    @Test
    fun `test Central and Northern Europe brand tokens resolve to resource strings`() {
        assertEquals(R.string.operator_magenta, OperatorResolver.getOperatorResourceId("magenta") as Int)
        assertEquals(R.string.operator_magenta, OperatorResolver.getOperatorResourceId("magenta telekom") as Int)
        assertEquals(R.string.operator_oneandone, OperatorResolver.getOperatorResourceId("1&1") as Int)
        assertEquals(R.string.operator_oneandone, OperatorResolver.getOperatorResourceId("1und1") as Int)
        assertEquals(R.string.operator_oneandone, OperatorResolver.getOperatorResourceId("eins und eins") as Int)
        assertEquals(R.string.operator_yettel, OperatorResolver.getOperatorResourceId("yettel") as Int)
        assertEquals(R.string.operator_lmt, OperatorResolver.getOperatorResourceId("lmt") as Int)
        assertEquals(R.string.operator_siminn, OperatorResolver.getOperatorResourceId("siminn") as Int)
    }

    @Test
    fun `test Central and Northern Europe operator icon resolution`() {
        assertEquals(R.drawable.ic_operator_magenta, SimUtils.getOperatorIconResource("magenta"))
        assertEquals(R.drawable.ic_operator_oneandone, SimUtils.getOperatorIconResource("1&1"))
        assertEquals(R.drawable.ic_operator_yettel, SimUtils.getOperatorIconResource("yettel"))
        assertEquals(R.drawable.ic_operator_lmt, SimUtils.getOperatorIconResource("lmt"))
        assertEquals(R.drawable.ic_operator_siminn, SimUtils.getOperatorIconResource("siminn"))
    }

    private fun resolvePlmn(plmn: String): String? {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.brandKey
    }

    private fun resolveDisplayName(plmn: String): String {
        val result = OperatorResolver.resolve(context, "", listOf(plmn), 0)
        return result.displayName
    }
}
