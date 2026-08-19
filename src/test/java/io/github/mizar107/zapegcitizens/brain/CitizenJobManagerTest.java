package io.github.mizar107.zapegcitizens.brain;

import io.github.mizar107.zapegcitizens.data.CitizenJobData.ActorContext;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobBudget;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobProgress;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobRecord;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void orphanedMidPlanningJobIsFlaggedForRecovery() {
        JobRecord acknowledged = baseJob(JobState.RUNNING, new JobProgress(
                "gathering", "The brain acknowledged the first action."));
        // Still marked RUNNING with a non-queued plan and no pending action: orphaned.
        assertTrue(CitizenJobManager.needsPlanningRecovery(acknowledged));

        // The initial-start window stays with the idempotent /start retry path instead.
        assertFalse(CitizenJobManager.needsPlanningRecovery(
                baseJob(JobState.RUNNING, JobProgress.queued())));
        assertFalse(CitizenJobManager.needsPlanningRecovery(
                baseJob(JobState.QUEUED, JobProgress.queued())));

        // A job holding a pending action is recovered through the uncertain-action path.
        JobRecord withPending = acknowledged.transition(
                JobState.WAITING_ACTION,
                acknowledged.progress(),
                0,
                0,
                Optional.of(pendingAction()),
                Optional.empty(),
                Optional.empty(),
                30);
        assertFalse(CitizenJobManager.needsPlanningRecovery(withPending));

        // Paused/terminal states are driven by their own resume/finish paths.
        assertFalse(CitizenJobManager.needsPlanningRecovery(acknowledged.transition(
                JobState.PAUSED,
                acknowledged.progress(),
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.of("paused"),
                30)));
        assertFalse(CitizenJobManager.needsPlanningRecovery(null));
    }

    @Test
    void brainRetryBackoffIsBoundedAndCapped() {
        assertTrue(CitizenJobManager.shouldRetryBrain(0));
        assertTrue(CitizenJobManager.shouldRetryBrain(5));
        assertFalse(CitizenJobManager.shouldRetryBrain(6));
        assertFalse(CitizenJobManager.shouldRetryBrain(-1));

        long previous = -1;
        for (int attempt = 0; attempt < 6; attempt++) {
            long delay = CitizenJobManager.brainRetryDelayTicks(attempt);
            assertTrue(delay > 0, "delay must be positive");
            assertTrue(delay <= 1_200L, "delay must be capped at the maximum");
            assertTrue(delay >= previous, "backoff must never shrink");
            previous = delay;
        }
        assertTrue(CitizenJobManager.brainRetryDelayTicks(5) == 1_200L);
        // A pathological attempt index can never overflow into a negative delay.
        assertTrue(CitizenJobManager.brainRetryDelayTicks(40) == 1_200L);
    }

    @Test
    void inventoryChangeDescriptionIsSignedCompleteAndBounded() {
        Map<String, Integer> baseline = Map.of(
                "minecraft:oak_log", 3,
                "minecraft:stick", 8);
        Map<String, Integer> current = Map.of(
                "minecraft:oak_log", 1,
                "minecraft:iron_axe", 1);

        assertEquals(
                "+1x minecraft:iron_axe, -2x minecraft:oak_log, -8x minecraft:stick",
                CitizenJobManager.describeInventoryChange(baseline, current));
        assertEquals(
                "items moved",
                CitizenJobManager.describeInventoryChange(baseline, baseline));

        // A pathologically large inventory can never produce an unbounded chat message.
        Map<String, Integer> empty = Map.of();
        Map<String, Integer> huge = new TreeMap<>();
        for (int index = 0; index < 500; index++) {
            huge.put("minecraft:item_" + String.format("%03d", index), index);
        }
        String described = CitizenJobManager.describeInventoryChange(empty, huge);
        assertTrue(described.length() < 320, "diff must stay bounded");
        assertTrue(described.endsWith("..."), "truncated diff must be marked");
    }

    @Test
    void autoResumeAttemptsAreBounded() {
        assertTrue(CitizenJobManager.shouldAttemptAutoResume(0));
        assertTrue(CitizenJobManager.shouldAttemptAutoResume(3));
        assertFalse(CitizenJobManager.shouldAttemptAutoResume(4));
        assertFalse(CitizenJobManager.shouldAttemptAutoResume(7));
        assertFalse(CitizenJobManager.shouldAttemptAutoResume(-1));
    }

    private static JobRecord baseJob(JobState state, JobProgress progress) {
        return new JobRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Alice",
                "collect iron",
                new ActorContext("minecraft:overworld", 1, 64, 2, 0, 0, Optional.empty()),
                new JobBudget(128, 192, 10_800),
                state,
                progress,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                10,
                20);
    }

    private static io.github.mizar107.zapegcitizens.data.CitizenJobData.PendingAction
            pendingAction() {
        return new io.github.mizar107.zapegcitizens.data.CitizenJobData.PendingAction(
                "action-1",
                "exec-1",
                "mine",
                "{}",
                false,
                false,
                Optional.empty());
    }
}
