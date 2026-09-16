package com.example.minutewatcher.core.data.util

import com.example.minutewatcher.core.model.ActiveSimInfo
import com.example.minutewatcher.core.model.IdentitySource
import com.example.minutewatcher.core.model.SimIdentity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SimIdentityHashLocaleTest {
    @Test
    fun `test identity hash stability across locales`() {
        val seed = "test-seed-123"

        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val identityUs =
                SimIdentityUtils.createIdentityFromInfo(
                    info = createMockSimInfo(),
                    seed = seed,
                )

            // Arabic locale might have different digit representation
            Locale.setDefault(Locale.forLanguageTag("ar"))
            val identityAr =
                SimIdentityUtils.createIdentityFromInfo(
                    info = createMockSimInfo(),
                    seed = seed,
                )

            // Turkish locale has different 'i' casing
            Locale.setDefault(Locale.forLanguageTag("tr"))
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
        } finally {
            Locale.setDefault(originalLocale)
        }
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
            mccMnc = "25001",
            isDefaultData = true,
            isDefaultVoice = true,
        )
    }
}
