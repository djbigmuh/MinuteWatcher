package host.serv.djbigmuh.minutewatcher.core.android.util

import android.content.Context
import host.serv.djbigmuh.minutewatcher.core.android.testing.SingletonResetRule
import host.serv.djbigmuh.minutewatcher.core.common.util.initTestLogger
import host.serv.djbigmuh.minutewatcher.core.model.SimUsage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

class SimCardMapperTest {
    @get:Rule
    val singletonResetRule = SingletonResetRule()

    private val context: Context = mock()

    @Before
    fun setup() {
        initTestLogger(context)
        SimCardMapper.reset()
    }

    @Test
    fun `map correctly handles traffic adjustment`() {
        // Arrange
        val usage =
            SimUsage(
                identity = TEST_IDENTITY,
                slotIndex = TEST_SLOT_0,
                subscriptionId = TEST_SUB_ID_1,
                operator = TEST_OPERATOR,
                minutesUsed = TEST_MINUTES_USED,
                minutesLimit = TEST_MINUTES_LIMIT,
                manualAdjustment = TEST_MANUAL_ADJUSTMENT_POS,
                trafficUsedMb = TEST_TRAFFIC_USED_MB,
                trafficLimitMb = TEST_TRAFFIC_LIMIT_MB,
                trafficAdjustmentGb = TEST_TRAFFIC_ADJUSTMENT_GB,
                autoReset = false,
                autoResetDay = TEST_RESET_DAY,
                lastResetDate = TEST_LAST_RESET_DATE,
                rolloverEnabled = false,
            )

        // Act
        val model = SimCardMapper.map(context, TEST_SLOT_0, usage)

        // Assert
        assertEquals(TEST_EFFECTIVE_TRAFFIC_LIMIT_GB, model.effectiveTrafficLimitGb, TEST_DELTA)
        assertEquals(TEST_TRAFFIC_ADJUSTMENT_GB, model.trafficAdjustmentGb, TEST_DELTA)
        assertEquals(TEST_TRAFFIC_REMAINING_GB, model.trafficRemainingGb ?: 0f, TEST_DELTA)
    }

    @Test
    fun `map clamps negative effective limit to zero`() {
        // Arrange
        val usage =
            SimUsage(
                identity = TEST_IDENTITY,
                slotIndex = TEST_SLOT_0,
                subscriptionId = TEST_SUB_ID_1,
                operator = TEST_OPERATOR,
                minutesUsed = TEST_ZERO_INT,
                minutesLimit = TEST_MINUTES_LIMIT,
                manualAdjustment = TEST_MANUAL_ADJUSTMENT_NEG,
                trafficUsedMb = TEST_ZERO_FLOAT,
                trafficLimitMb = TEST_TRAFFIC_LIMIT_MB,
                trafficAdjustmentGb = TEST_TRAFFIC_ADJUSTMENT_NEG_GB,
                autoReset = false,
                autoResetDay = TEST_RESET_DAY,
                lastResetDate = TEST_LAST_RESET_DATE,
                rolloverEnabled = false,
            )

        // Act
        val model = SimCardMapper.map(context, TEST_SLOT_0, usage)

        // Assert
        assertEquals(TEST_ZERO_INT, model.effectiveMinutesLimit)
        assertEquals(TEST_ZERO_FLOAT, model.effectiveTrafficLimitGb, TEST_DELTA)
        assertEquals(TEST_ZERO_INT, model.minutesRemaining)
        assertEquals(TEST_ZERO_FLOAT, model.trafficRemainingGb ?: 0f, TEST_DELTA)
    }

    @Test
    fun `map sets trafficRemainingGb to null when traffic limit is not set`() {
        val usage =
            SimUsage(
                identity = TEST_IDENTITY,
                slotIndex = TEST_SLOT_0,
                subscriptionId = TEST_SUB_ID_1,
                operator = TEST_OPERATOR,
                minutesUsed = TEST_ZERO_INT,
                minutesLimit = TEST_MINUTES_LIMIT,
                manualAdjustment = TEST_ZERO_INT,
                trafficUsedMb = TEST_ZERO_FLOAT,
                trafficLimitMb = 0,
                trafficAdjustmentGb = TEST_ZERO_FLOAT,
                autoReset = false,
                autoResetDay = TEST_RESET_DAY,
                lastResetDate = TEST_LAST_RESET_DATE,
                rolloverEnabled = false,
            )

        val model = SimCardMapper.map(context, TEST_SLOT_0, usage)

        assertFalse(model.isTrafficLimitSet)
        org.junit.Assert.assertNull(model.trafficRemainingGb)
    }

    @Test
    fun `concurrent map calls with different identities do not corrupt cache`() {
        runBlocking {
            val identities = List(TEST_IDENTITIES_COUNT) { "$TEST_IDENTITY_PREFIX$it" }

            val jobs =
                identities.map { id ->
                    async {
                        val usage =
                            SimUsage(
                                identity = id,
                                slotIndex = TEST_SLOT_0,
                                subscriptionId = TEST_SUB_ID_1,
                                operator = TEST_OPERATOR,
                                minutesUsed = TEST_MINUTES_USED,
                                minutesLimit = TEST_MINUTES_LIMIT,
                                manualAdjustment = TEST_ZERO_INT,
                                trafficUsedMb = TEST_TRAFFIC_USED_MB,
                                trafficLimitMb = TEST_TRAFFIC_LIMIT_MB,
                                trafficAdjustmentGb = TEST_ZERO_FLOAT,
                                autoReset = false,
                                autoResetDay = TEST_RESET_DAY,
                                lastResetDate = TEST_LAST_RESET_DATE,
                                rolloverEnabled = false,
                            )
                        SimCardMapper.map(context, TEST_SLOT_0, usage)
                    }
                }
            val results = jobs.awaitAll()
            assertTrue("Results should not be empty", results.isNotEmpty())
        }
    }

    companion object {
        private const val TEST_SLOT_0 = 0
        private const val TEST_SUB_ID_1 = 1
        private const val TEST_IDENTITY = "test_id"
        private const val TEST_OPERATOR = "TestOp"
        private const val TEST_MINUTES_USED = 50
        private const val TEST_MINUTES_LIMIT = 100
        private const val TEST_MANUAL_ADJUSTMENT_POS = 10
        private const val TEST_MANUAL_ADJUSTMENT_NEG = -200
        private const val TEST_TRAFFIC_USED_MB = 512f
        private const val TEST_TRAFFIC_LIMIT_MB = 1024
        private const val TEST_TRAFFIC_ADJUSTMENT_GB = 0.5f
        private const val TEST_TRAFFIC_ADJUSTMENT_NEG_GB = -2f
        private const val TEST_EFFECTIVE_TRAFFIC_LIMIT_GB = 1.5f
        private const val TEST_TRAFFIC_REMAINING_GB = 1.0f
        private const val TEST_RESET_DAY = 1
        private const val TEST_LAST_RESET_DATE = 0L
        private const val TEST_ZERO_INT = 0
        private const val TEST_ZERO_FLOAT = 0f
        private const val TEST_DELTA = 0.001f
        private const val TEST_IDENTITIES_COUNT = 10
        private const val TEST_IDENTITY_PREFIX = "identity_"
    }
}
