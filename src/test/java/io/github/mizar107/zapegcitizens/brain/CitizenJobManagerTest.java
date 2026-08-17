package io.github.mizar107.zapegcitizens.brain;

import io.github.mizar107.zapegcitizens.data.CitizenJobData.ActorContext;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobBudget;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobProgress;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobRecord;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobState;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitizenJobManagerTest {

    @Test
    void recoveryClassificationRetriesOnlyPerceptionWithoutAssumingWorldMutation() {
        assertTrue(CitizenJobManager.isReadOnlyTool("get_self_status"));
        assertTrue(CitizenJobManager.isReadOnlyTool("scan_blocks"));
        assertTrue(CitizenJobManager.isReadOnlyTool("inspect_block_storage"));
        assertTrue(CitizenJobManager.isReadOnlyTool("load_skill"));

        assertFalse(CitizenJobManager.isReadOnlyTool("mine"));
        assertFalse(CitizenJobManager.isReadOnlyTool("build"));
        assertFalse(CitizenJobManager.isReadOnlyTool("transfer"));
        assertFalse(CitizenJobManager.isReadOnlyTool("melee_attack"));
    }

    @Test
    void lostInitialStartRetriesTheIdempotentStartInsteadOfUnknownResume() {
        UUID citizenId = UUID.randomUUID();
        JobRecord lostStart = new JobRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                citizenId,
                UUID.randomUUID(),
                "Alice",
                "build here",
                new ActorContext(
                        "minecraft:overworld", 1, 64, 2, 0, 0, Optional.empty()),
                new JobBudget(128, 192, 10_800),
                JobState.PAUSED_BRAIN,
                JobProgress.queued(),
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.of("brain unavailable"),
                10,
                20);

        assertTrue(CitizenJobManager.shouldRetryInitialStart(lostStart));
        assertTrue(CitizenJobManager.shouldRetryInitialStart(lostStart.transition(
                JobState.QUEUED,
                JobProgress.queued(),
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                30)));
        assertTrue(CitizenJobManager.shouldRetryInitialStart(lostStart.transition(
                JobState.RUNNING,
                JobProgress.queued(),
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                30)));
        assertFalse(CitizenJobManager.shouldRetryInitialStart(lostStart.transition(
                JobState.PAUSED_BRAIN,
                new JobProgress("planning", "Plan was already acknowledged."),
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.of("brain unavailable"),
                30)));
    }

    @Test
    void resumeIsRestrictedToPausedOrInputStates() {
        assertTrue(CitizenJobManager.isResumableState(JobState.NEEDS_INPUT));
        assertTrue(CitizenJobManager.isResumableState(JobState.PAUSED));
        assertTrue(CitizenJobManager.isResumableState(JobState.PAUSED_BODY));
        assertTrue(CitizenJobManager.isResumableState(JobState.PAUSED_OWNER));
        assertTrue(CitizenJobManager.isResumableState(JobState.PAUSED_BRAIN));
        assertFalse(CitizenJobManager.isResumableState(JobState.PAUSED_BUDGET));
        assertTrue(CitizenJobManager.isResumableState(JobState.PAUSED_SHUTDOWN));

        assertFalse(CitizenJobManager.isResumableState(JobState.QUEUED));
        assertFalse(CitizenJobManager.isResumableState(JobState.RUNNING));
        assertFalse(CitizenJobManager.isResumableState(JobState.WAITING_ACTION));
        assertFalse(CitizenJobManager.isResumableState(JobState.REPORTING_RESULT));
        assertFalse(CitizenJobManager.isResumableState(JobState.CANCELING));
        assertFalse(CitizenJobManager.isResumableState(JobState.COMPLETED));
    }
}
