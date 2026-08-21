package io.github.mizar107.zapegcitizens.data;

import io.github.mizar107.zapegcitizens.data.CitizenJobData.ActorContext;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobBudget;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobProgress;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobRecord;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.JobState;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.LookTarget;
import io.github.mizar107.zapegcitizens.data.CitizenJobData.PendingAction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitizenJobDataTest {

    @Test
    void roundTripsTheRecoveryCriticalActionAndResult() {
        CitizenJobData data = new CitizenJobData();
        JobRecord original = job(UUID.randomUUID(), UUID.randomUUID(), JobState.REPORTING_RESULT)
                .transition(
                        JobState.REPORTING_RESULT,
                        new JobProgress("gather", "Collected the first batch"),
                        1,
                        220,
                        Optional.of(new PendingAction(
                                "action-1",
                                "mcp-zapeg-job-test-1",
                                "mine",
                                "{\"block_ids\":[\"minecraft:diamond_ore\"],\"count\":3}",
                                false,
                                false,
                                Optional.of("{\"success\":true,\"data\":{\"count\":3}}"))),
                        Optional.of("action-1"),
                        Optional.empty(),
                        250);
        data.create(original);

        CompoundTag root = data.save(new CompoundTag());
        CitizenJobData loaded = CitizenJobData.load(root);
        JobRecord restored = loaded.find(original.jobId()).orElseThrow();

        assertEquals(original, restored);
        assertEquals("mine", restored.pendingAction().orElseThrow().toolName());
        assertTrue(restored.pendingAction().orElseThrow().resultJson().orElseThrow()
                .contains("count"));
        assertEquals("BLOCK", restored.actorContext().lookTarget().orElseThrow().kind());
    }

    @Test
    void largeBuildArgumentsUseNbtByteArraysInsteadOfModifiedUtfStrings() throws IOException {
        CitizenJobData data = new CitizenJobData();
        JobRecord base = job(UUID.randomUUID(), UUID.randomUUID(), JobState.WAITING_ACTION);
        String largeArguments = "{\"design\":\"" + "x".repeat(200_000) + "\"}";
        JobRecord withLargeAction = base.transition(
                JobState.WAITING_ACTION,
                new JobProgress("building", "Persisting a detailed villa plan"),
                0,
                0,
                Optional.of(new PendingAction(
                        "large-build",
                        "mcp-zapeg-job-large-1",
                        "build",
                        largeArguments,
                        false,
                        false,
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                101);
        data.create(withLargeAction);

        CompoundTag root = data.save(new CompoundTag());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, bytes);
        assertTrue(bytes.size() > 0);

        JobRecord restored = CitizenJobData.load(root)
                .find(withLargeAction.jobId()).orElseThrow();
        assertEquals(
                largeArguments,
                restored.pendingAction().orElseThrow().argumentsJson());
    }

    @Test
    void enforcesOneActiveJobPerCitizenButRetainsTerminalHistory() {
        CitizenJobData data = new CitizenJobData();
        UUID citizenId = UUID.randomUUID();
        JobRecord active = job(UUID.randomUUID(), citizenId, JobState.RUNNING);
        data.create(active);

        assertThrows(IllegalStateException.class,
                () -> data.create(job(UUID.randomUUID(), citizenId, JobState.QUEUED)));

        data.update(active.jobId(), current -> current.transition(
                JobState.COMPLETED,
                current.progress(),
                current.actionsCompleted(),
                current.activeTicks(),
                Optional.empty(),
                current.lastConfirmedActionId(),
                Optional.of("Done"),
                500));
        JobRecord next = job(UUID.randomUUID(), citizenId, JobState.QUEUED);
        data.create(next);

        assertEquals(next.jobId(), data.activeForCitizen(citizenId).orElseThrow().jobId());
        assertEquals(2, data.forCitizen(citizenId).size());
    }

    @Test
    void waitingInLineJobsCoexistWithTheDrivingJobAndSurviveReload() {
        CitizenJobData data = new CitizenJobData();
        UUID citizenId = UUID.randomUUID();
        JobRecord driving = job(UUID.randomUUID(), citizenId, JobState.RUNNING);
        data.create(driving);

        JobRecord waiting = job(UUID.randomUUID(), citizenId, JobState.QUEUED)
                .transition(
                        JobState.QUEUED,
                        new JobProgress(
                                CitizenJobData.WAITING_PHASE,
                                "Waiting for the citizen's current job to finish."),
                        0,
                        0,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        120);
        data.create(waiting);
        assertTrue(CitizenJobData.isWaitingInLine(waiting));
        assertEquals(driving.jobId(), data.activeForCitizen(citizenId).orElseThrow().jobId());
        assertEquals(
                driving.jobId(),
                data.activeDrivingForCitizen(citizenId).orElseThrow().jobId());
        assertEquals(1, data.waitingForCitizen(citizenId).size());

        // A second non-waiting nonterminal job stays forbidden.
        assertThrows(IllegalStateException.class,
                () -> data.create(job(UUID.randomUUID(), citizenId, JobState.QUEUED)));

        // Both rows survive the save/load round trip; only true duplicates drop.
        CitizenJobData reloaded = CitizenJobData.load(data.save(new CompoundTag()));
        assertEquals(2, reloaded.forCitizen(citizenId).size());
        assertEquals(1, reloaded.waitingForCitizen(citizenId).size());
        assertEquals(
                driving.jobId(),
                reloaded.activeDrivingForCitizen(citizenId).orElseThrow().jobId());

        // Once the driving job ends, the waiting row is the next active job.
        reloaded.update(driving.jobId(), current -> current.transition(
                JobState.COMPLETED,
                current.progress(),
                current.actionsCompleted(),
                current.activeTicks(),
                Optional.empty(),
                current.lastConfirmedActionId(),
                Optional.of("done"),
                500));
        assertTrue(reloaded.activeDrivingForCitizen(citizenId).isEmpty());
        assertEquals(
                waiting.jobId(), reloaded.activeForCitizen(citizenId).orElseThrow().jobId());
    }

    @Test
    void queueMatrixKeepsOrderAndOneDrivingRowAcrossTerminalTransitions() {
        CitizenJobData data = new CitizenJobData();
        UUID citizenId = UUID.randomUUID();
        JobRecord driving = job(UUID.randomUUID(), citizenId, JobState.RUNNING);
        data.create(driving);
        JobRecord firstWaiting = waitingRow(citizenId, 120);
        JobRecord secondWaiting = waitingRow(citizenId, 140);
        data.create(firstWaiting);
        data.create(secondWaiting);

        // Oldest-first order and resolution while the driving job is live.
        assertEquals(2, data.waitingForCitizen(citizenId).size());
        assertEquals(firstWaiting.jobId(), data.waitingForCitizen(citizenId).get(0).jobId());
        assertEquals(secondWaiting.jobId(), data.waitingForCitizen(citizenId).get(1).jobId());
        assertEquals(driving.jobId(), data.activeForCitizen(citizenId).orElseThrow().jobId());
        assertEquals(
                driving.jobId(), data.activeDrivingForCitizen(citizenId).orElseThrow().jobId());

        // A third waiting row is storable while a second driving row throws.
        data.create(waitingRow(citizenId, 160));
        assertThrows(IllegalStateException.class,
                () -> data.create(job(UUID.randomUUID(), citizenId, JobState.QUEUED)));

        // Terminal the driving job: order among the waiting rows is preserved
        // and the citizen has no driving row until one is promoted.
        data.update(driving.jobId(), current -> current.transition(
                JobState.FAILED,
                current.progress(),
                current.actionsCompleted(),
                current.activeTicks(),
                Optional.empty(),
                current.lastConfirmedActionId(),
                Optional.of("failed"),
                500));
        assertTrue(data.activeDrivingForCitizen(citizenId).isEmpty());
        assertEquals(3, data.waitingForCitizen(citizenId).size());
        assertEquals(firstWaiting.jobId(), data.waitingForCitizen(citizenId).get(0).jobId());
    }

    @Test
    void budgetParkedJobsNoLongerCountAsDrivingButStayAddressable() {
        CitizenJobData data = new CitizenJobData();
        UUID citizenId = UUID.randomUUID();
        JobRecord parked = job(UUID.randomUUID(), citizenId, JobState.PAUSED_BUDGET);
        data.create(parked);
        assertTrue(CitizenJobData.isParkedByBudget(parked));

        // Parked rows are terminal for scheduling: no driving row, so queued
        // work can promote past them; they stay visible via activeForCitizen.
        assertTrue(data.activeDrivingForCitizen(citizenId).isEmpty());
        assertEquals(parked.jobId(), data.activeForCitizen(citizenId).orElseThrow().jobId());

        // The next job may start (and reload keeps both rows) while parked.
        JobRecord next = job(UUID.randomUUID(), citizenId, JobState.QUEUED);
        data.create(next);
        assertEquals(next.jobId(), data.activeDrivingForCitizen(citizenId).orElseThrow().jobId());
        CitizenJobData reloaded = CitizenJobData.load(data.save(new CompoundTag()));
        assertEquals(2, reloaded.forCitizen(citizenId).size());
        assertEquals(
                next.jobId(), reloaded.activeDrivingForCitizen(citizenId).orElseThrow().jobId());

        assertTrue(CitizenJobData.isParkedByBudget(
                reloaded.find(parked.jobId()).orElseThrow()));
        assertEquals(false, CitizenJobData.isParkedByBudget(next));
        assertEquals(false, CitizenJobData.isParkedByBudget(null));
    }

    @Test
    void transitionCannotReplaceJobOrCitizenIdentity() {
        CitizenJobData data = new CitizenJobData();
        JobRecord active = job(UUID.randomUUID(), UUID.randomUUID(), JobState.RUNNING);
        data.create(active);

        assertThrows(IllegalArgumentException.class, () -> data.update(active.jobId(), current ->
                job(UUID.randomUUID(), current.citizenId(), JobState.RUNNING)));
    }

    @Test
    void prunesTerminalHistoryPerCitizen() {
        CitizenJobData data = new CitizenJobData();
        UUID citizenId = UUID.randomUUID();
        for (int index = 0; index < CitizenJobData.MAX_TERMINAL_JOBS_PER_CITIZEN + 7; index++) {
            JobRecord terminal = job(UUID.randomUUID(), citizenId, JobState.COMPLETED);
            data.create(terminal.transition(
                    JobState.COMPLETED,
                    terminal.progress(),
                    0,
                    0,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of("done"),
                    100 + index));
        }

        assertEquals(
                CitizenJobData.MAX_TERMINAL_JOBS_PER_CITIZEN,
                data.forCitizen(citizenId).size());
    }

    private static JobRecord waitingRow(UUID citizenId, long createdAt) {
        return job(UUID.randomUUID(), citizenId, JobState.QUEUED)
                .transition(
                        JobState.QUEUED,
                        new JobProgress(
                                CitizenJobData.WAITING_PHASE,
                                "Waiting for the citizen's current job to finish."),
                        0,
                        0,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        createdAt);
    }

    private static JobRecord job(UUID jobId, UUID citizenId, JobState state) {
        return new JobRecord(
                jobId,
                UUID.randomUUID(),
                citizenId,
                UUID.randomUUID(),
                "Alice",
                "Mine three diamonds and return",
                new ActorContext(
                        "minecraft:overworld",
                        1.5,
                        64,
                        2.5,
                        0,
                        0,
                        Optional.of(new LookTarget(
                                "BLOCK",
                                "minecraft:overworld",
                                2,
                                63,
                                2,
                                Optional.of("up")))),
                new JobBudget(128, 192, 10_800),
                state,
                JobProgress.queued(),
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                100,
                100);
    }
}
