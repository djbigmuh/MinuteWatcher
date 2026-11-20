package com.github.djbigmuh.minutewatcher.core.data

import com.github.djbigmuh.minutewatcher.core.common.util.TimeProvider
import com.github.djbigmuh.minutewatcher.core.model.IdentitySource
import com.github.djbigmuh.minutewatcher.core.model.SimIdentity
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock

class SimIdentityCacheTest {
    private val timeProvider: TimeProvider = mock()
    private val cache = SimIdentityCache(timeProvider)

    @Test
    fun `first call saves and returns passed instance`() {
        val identity = SimIdentity("id1", 101, 0, IdentitySource.ICCID, isStable = true)
        val result = cache.getOrCreateCanonical(identity)
        assertSame(identity, result)
    }

    @Test
    fun `call with same stableId but different slotIndex returns new instance`() {
        val identity1 = SimIdentity("id1", 101, 0, IdentitySource.ICCID, isStable = true)
        val identity2 = SimIdentity("id1", 101, 1, IdentitySource.ICCID, isStable = true)

        val result1 = cache.getOrCreateCanonical(identity1)
        val result2 = cache.getOrCreateCanonical(identity2)

        assertSame(identity1, result1)
        assertSame(identity2, result2)
        assertNotSame(result1, result2)
    }

    @Test
    fun `repeated call with same fields returns SAME instance by reference`() {
        val identity1 = SimIdentity("id1", 101, 0, IdentitySource.ICCID, isStable = true)
        val identity2 = SimIdentity("id1", 101, 0, IdentitySource.ICCID, isStable = true)

        val result1 = cache.getOrCreateCanonical(identity1)
        val result2 = cache.getOrCreateCanonical(identity2)

        assertSame(identity1, result1)
        assertSame(result1, result2)
    }

    @Test
    fun `call with same stableId but different subscriptionId returns new instance`() {
        val identity1 = SimIdentity("id1", 101, 0, IdentitySource.ICCID, isStable = true)
        val identity2 = SimIdentity("id1", 102, 0, IdentitySource.ICCID, isStable = true)

        val result1 = cache.getOrCreateCanonical(identity1)
        val result2 = cache.getOrCreateCanonical(identity2)

        assertSame(identity1, result1)
        assertSame(identity2, result2)
        assertNotSame(result1, result2)
    }

    @Test
    fun `check cache limit and evacuation of isStable == false`() {
        // Fill cache with stable IDs up to limit
        for (i in 1..64) {
            cache.getOrCreateCanonical(SimIdentity("stable_$i", i, 0, IdentitySource.ICCID, isStable = true))
        }

        // Add unstable ID - this should trigger evictUnstableIfNeeded
        val unstable = SimIdentity("unstable", null, 0, IdentitySource.SUBSCRIPTION_ID, isStable = false)
        val result = cache.getOrCreateCanonical(unstable)
        assertSame(unstable, result)

        // Add another unstable ID to trigger eviction of previous unstable
        val unstable2 = SimIdentity("unstable2", null, 1, IdentitySource.SUBSCRIPTION_ID, isStable = false)
        cache.getOrCreateCanonical(unstable2)

        // Now "unstable" should be evicted if we try to get it again (it will be added as new instance)
        val unstableAgain = SimIdentity("unstable", null, 0, IdentitySource.SUBSCRIPTION_ID, isStable = false)
        val resultAgain = cache.getOrCreateCanonical(unstableAgain)
        assertNotSame(result, resultAgain)
        assertSame(unstableAgain, resultAgain)
    }
}
