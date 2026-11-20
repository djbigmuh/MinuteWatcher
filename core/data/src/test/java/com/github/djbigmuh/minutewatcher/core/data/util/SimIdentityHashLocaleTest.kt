package com.github.djbigmuh.minutewatcher.core.data.util

import com.github.djbigmuh.minutewatcher.core.common.testing.DefaultLocaleRule
import com.github.djbigmuh.minutewatcher.core.model.ActiveSimInfo
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class SimIdentityHashLocaleTest {
    @get:Rule
    val defaultLocaleRule = DefaultLocaleRule()

    @Test
    fun `test identity hash stability across locales`() {
        val seed = "test-seed-123"

        defaultLocaleRule.setLocale(Locale.US)
        val identityUs =
            SimIdentityUtils.createIdentityFromInfo(
                info = createMockSimInfo(),
                seed = seed,
            )

        // Arabic locale might have different digit representation
        defaultLocaleRule.setLocale(Locale.forLanguageTag("ar"))
        val identityAr =
            SimIdentityUtils.createIdentityFromInfo(
                info = createMockSimInfo(),
                seed = seed,
            )

        // Turkish locale has different 'i' casing
        defaultLocaleRule.setLocale(Locale.forLanguageTag("tr"))
        val identityTr =
            SimIdentityUtils.createIdentityFromInfo(
                info = createMockSimInfo(),
                seed = seed,
            )

        assertEquals(
            "Hashes should be identical regardless of locale",
            identityUs.stableId,
            identityAr.stableId,
        )
        assertEquals(
            "Hashes should be identical regardless of Turkish locale",
            identityUs.stableId,
            identityTr.stableId,
        )
    }

    private fun createMockSimInfo(): ActiveSimInfo {
        val iccid = "8970123456789012345F"
        return ActiveSimInfo(
            identity =
                SimIdentity(
                    stableId = "",
                    subscriptionId = 1,
                    slotIndex = 0,
                    source = IdentitySource.ICCID,
                    isStable = true,
                ),
            subscriptionId = 1,
            slotIndex = 0,
            iccId = iccid,
            displayName = "SIM 1",
            carrierName = "Carrier",
            isDefaultData = true,
            isDefaultVoice = true,
        )
    }
}
