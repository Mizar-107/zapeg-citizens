package io.github.mizar107.zapegcitizens.brain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AutoResumeBudgetTest {

    @Test
    void attemptsSurviveVerificationDispatchesAndResetOnMutatingDispatch() {
        AutoResumeBudget budget = new AutoResumeBudget(4);
        UUID jobId = UUID.randomUUID();

        budget.declareRequirement(jobId, "I need 96 oak planks.");
        budget.recordAttempt(jobId);
        budget.recordAttempt(jobId);
        assertEquals(2, budget.attempts(jobId));

        // The mandated read-only verification after an auto-resume answer must
        // NOT re-arm the budget (this was the CIT-01 no-op: dispatchAction
        // cleared the counter on every action).
        assertFalse(budget.actionDispatched(jobId, true));
        assertEquals(2, budget.attempts(jobId));

        // A mutating action proves the requirement was satisfied: re-armed.
        assertTrue(budget.actionDispatched(jobId, false));
        assertEquals(0, budget.attempts(jobId));
    }

    @Test
    void junkInventoryDripBurnsAtMostFourAttemptsPerRequirement() {
        AutoResumeBudget budget = new AutoResumeBudget(4);
        UUID jobId = UUID.randomUUID();
        String requirement = "I need an iron pickaxe before I can mine.";

        assertTrue(budget.declareRequirement(jobId, requirement));
        // Simulate the live drip: inventory churn resumes the job, the planner
        // verifies read-only and re-declares the SAME requirement, repeatedly.
        int granted = 0;
        for (int churn = 0; churn < 10; churn++) {
            if (!budget.shouldAttempt(jobId)) {
                break;
            }
            granted++;
            budget.recordAttempt(jobId);
            // The compliant cycle: a read-only verification is dispatched...
            assertFalse(budget.actionDispatched(jobId, true));
            // ...and the identical question is re-declared; the counter holds.
            assertFalse(budget.declareRequirement(jobId, requirement));
        }
        assertEquals(4, granted);
        assertFalse(budget.shouldAttempt(jobId));

        // A DISTINCT new requirement legitimately re-arms the budget.
        assertTrue(budget.declareRequirement(jobId, "Now I also need torches."));
        assertTrue(budget.shouldAttempt(jobId));
        assertEquals(0, budget.attempts(jobId));
    }

    @Test
    void requirementKeyIgnoresSurroundingWhitespaceAndHandlesNull() {
        AutoResumeBudget budget = new AutoResumeBudget(4);
        UUID jobId = UUID.randomUUID();

        assertTrue(budget.declareRequirement(jobId, "Need planks."));
        budget.recordAttempt(jobId);
        assertFalse(budget.declareRequirement(jobId, "  Need planks.  "));
        assertEquals(1, budget.attempts(jobId));
        assertTrue(budget.declareRequirement(jobId, null));
        assertEquals(0, budget.attempts(jobId));
    }

    @Test
    void clearingForgetsBothCounterAndRequirementKey() {
        AutoResumeBudget budget = new AutoResumeBudget(2);
        UUID jobId = UUID.randomUUID();
        budget.declareRequirement(jobId, "Need planks.");
        budget.recordAttempt(jobId);
        budget.recordAttempt(jobId);
        assertFalse(budget.shouldAttempt(jobId));

        budget.clear(jobId);
        assertTrue(budget.shouldAttempt(jobId));
        // After clearing, even the previously identical question re-arms fresh.
        assertTrue(budget.declareRequirement(jobId, "Need planks."));

        budget.recordAttempt(jobId);
        budget.clearAll();
        assertEquals(0, budget.attempts(jobId));

        assertThrows(IllegalArgumentException.class, () -> new AutoResumeBudget(0));
    }
}
