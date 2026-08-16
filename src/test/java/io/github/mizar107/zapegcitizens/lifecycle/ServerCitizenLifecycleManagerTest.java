package io.github.mizar107.zapegcitizens.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerCitizenLifecycleManagerTest {

    @Test
    void deathRecoveryStartsOnlyAfterTheFullPinnedDelay() {
        long diedAt = 1_000L;

        assertFalse(ServerCitizenLifecycleManager.recoveryDelayElapsed(diedAt, diedAt));
        assertFalse(ServerCitizenLifecycleManager.recoveryDelayElapsed(
                diedAt + ServerCitizenLifecycleManager.DEATH_RESPAWN_DELAY_TICKS - 1L,
                diedAt));
        assertTrue(ServerCitizenLifecycleManager.recoveryDelayElapsed(
                diedAt + ServerCitizenLifecycleManager.DEATH_RESPAWN_DELAY_TICKS,
                diedAt));
    }

    @Test
    void deathRecoveryRejectsMissingOrFutureDeathTimestamps() {
        assertFalse(ServerCitizenLifecycleManager.recoveryDelayElapsed(10_000L, 0L));
        assertFalse(ServerCitizenLifecycleManager.recoveryDelayElapsed(999L, 1_000L));
    }

    @Test
    void snapshotsImmediatelyThenAtTheConfiguredCadence() {
        assertTrue(ServerCitizenLifecycleManager.snapshotDue(10L, null));
        assertFalse(ServerCitizenLifecycleManager.snapshotDue(
                10L + ServerCitizenLifecycleManager.POSITION_SNAPSHOT_INTERVAL_TICKS - 1L,
                10L));
        assertTrue(ServerCitizenLifecycleManager.snapshotDue(
                10L + ServerCitizenLifecycleManager.POSITION_SNAPSHOT_INTERVAL_TICKS,
                10L));
        assertFalse(ServerCitizenLifecycleManager.snapshotDue(9L, 10L));
    }

    @Test
    void failedOperationsRespectTheirRetryDeadline() {
        assertFalse(ServerCitizenLifecycleManager.retryDue(99L, 100L));
        assertTrue(ServerCitizenLifecycleManager.retryDue(100L, 100L));
        assertTrue(ServerCitizenLifecycleManager.retryDue(101L, 100L));
    }
}
