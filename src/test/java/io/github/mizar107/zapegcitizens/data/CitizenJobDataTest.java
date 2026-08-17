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
