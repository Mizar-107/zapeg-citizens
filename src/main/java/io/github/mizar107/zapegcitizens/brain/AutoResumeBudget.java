package io.github.mizar107.zapegcitizens.brain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime bookkeeping for the bounded NEEDS_INPUT auto-resume budget.
 *
 * <p>The budget is per <em>requirement</em>, keyed by the declared question
 * text. The system prompt mandates a read-only verification action after every
 * auto-resume answer, so a compliant cycle looks like resume → read-only
 * dispatch → the same question again; if that cycle re-armed the counter, a
 * junk-item drip (tossed trash, drops auto-picked-up near farms) could burn
 * the whole job budget through endless resume→verify→needs_input loops and the
 * attempt cap would never bind. Therefore only two events re-arm the budget:
 * a <em>mutating</em> dispatched action (the planner accepted the answer as
 * satisfying the requirement and moved on to real work), or a <em>distinct new
 * requirement</em>. A read-only dispatch and a re-declared identical question
 * both keep the counter. Nothing here is persisted; a restart simply starts a
 * fresh count.
 */
final class AutoResumeBudget {

    private final int maxAttempts;
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, String> requirementKeys = new ConcurrentHashMap<>();

    AutoResumeBudget(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    int attempts(UUID jobId) {
        return attempts.getOrDefault(Objects.requireNonNull(jobId, "jobId"), 0);
    }

    boolean shouldAttempt(UUID jobId) {
        return attempts(jobId) < maxAttempts;
    }

    void recordAttempt(UUID jobId) {
        attempts.merge(Objects.requireNonNull(jobId, "jobId"), 1, Integer::sum);
    }

    /**
     * Declares the requirement behind a fresh NEEDS_INPUT. Returns {@code true}
     * (and re-arms the budget) only when the question is distinct from the one
     * already tracked; re-asking the same requirement keeps the counter.
     */
    boolean declareRequirement(UUID jobId, String question) {
        Objects.requireNonNull(jobId, "jobId");
        String key = question == null ? "" : question.strip();
        String previous = requirementKeys.put(jobId, key);
        if (previous != null && previous.equals(key)) {
            return false;
        }
        attempts.remove(jobId);
        return true;
    }

    /**
     * Observes a dispatched action. Returns {@code true} (and re-arms the
     * budget) only for a mutating action — proof the requirement was actually
     * satisfied. The mandated read-only verification keeps the counter.
     */
    boolean actionDispatched(UUID jobId, boolean readOnly) {
        Objects.requireNonNull(jobId, "jobId");
        if (readOnly) {
            return false;
        }
        attempts.remove(jobId);
        requirementKeys.remove(jobId);
        return true;
    }

    void clear(UUID jobId) {
        attempts.remove(jobId);
        requirementKeys.remove(jobId);
    }

    void clearAll() {
        attempts.clear();
        requirementKeys.clear();
    }
}
