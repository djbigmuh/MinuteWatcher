package host.serv.djbigmuh.minutewatcher.core.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingStatusMachineTest {
    @Test
    fun `merge should return new status if force is true`() {
        val current = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
        val new = TrackingStatus.Idle
        assertEquals(new, TrackingStatusMachine.merge(current, new, force = true))
    }

    @Test
    fun `merge should return current if same`() {
        val current = TrackingStatus.Active
        val new = TrackingStatus.Active
        assertEquals(current, TrackingStatusMachine.merge(current, new, force = false))
    }

    @Test
    fun `merge should return new if priority is higher`() {
        val current = TrackingStatus.Active
        val new = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR)
        assertEquals(new, TrackingStatusMachine.merge(current, new, force = false))
    }

    @Test
    fun `merge should return current if priority is lower`() {
        val current = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
        val new = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR)
        assertEquals(current, TrackingStatusMachine.merge(current, new, force = false))
    }

    @Test
    fun `recovery - healthy should clear transient error`() {
        val current = TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN)
        val new = TrackingStatus.Active
        assertEquals(new, TrackingStatusMachine.merge(current, new, force = false))
    }

    @Test
    fun `recovery - healthy should NOT clear sticky database error`() {
        val current = TrackingStatus.Error(TrackingStatus.ErrorType.DATABASE_ERROR)
        val new = TrackingStatus.Active
        assertEquals(current, TrackingStatusMachine.merge(current, new, force = false))
    }

    @Test
    fun `recovery - healthy should NOT clear sticky permission error`() {
        val current = TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING)
        val new = TrackingStatus.Active
        assertEquals(current, TrackingStatusMachine.merge(current, new, force = false))
    }

    @Test
    fun `transition updates status correctly`() =
        runTest {
            val machine = TrackingStatusMachine(TrackingStatus.Idle)
            assertEquals(TrackingStatus.Idle, machine.current)

            machine.transition(TrackingStatus.Active)
            assertEquals(TrackingStatus.Active, machine.current)

            machine.transition(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN))
            assertEquals(TrackingStatus.Error(TrackingStatus.ErrorType.UNKNOWN), machine.current)

            // Recovery
            machine.transition(TrackingStatus.Idle)
            assertEquals(TrackingStatus.Idle, machine.current)

            // Sticky error
            machine.transition(TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING))
            machine.transition(TrackingStatus.Active)
            assertEquals(TrackingStatus.Error(TrackingStatus.ErrorType.PERMISSION_MISSING), machine.current)

            // Force override
            machine.transition(TrackingStatus.Idle, force = true)
            assertEquals(TrackingStatus.Idle, machine.current)
        }

    @Test
    fun `error with RENDER_FAILURE code is cleared by healthy status`() {
        val current =
            TrackingStatus.Error(
                TrackingStatus.ErrorType.UNKNOWN,
                code = TrackingStatus.ErrorCode.RENDER_FAILURE,
            )
        val new = TrackingStatus.Active
        assertEquals(new, TrackingStatusMachine.merge(current, new, force = false))
    }
}
