package com.saintmagic.gemmabuddy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the current high-level goal and any short subgoals.
 *
 * This stays intentionally lightweight for now so it can grow later into a
 * Baritone-style task manager without dragging movement logic into it yet.
 */
public final class GoalManager {
    private final AtomicReference<GoalState> state = new AtomicReference<>(GoalState.idle());

    public GoalState snapshot() {
        return state.get();
    }

    public void clear() {
        state.set(GoalState.idle());
    }

    public void setGoal(String title, List<String> subgoals, boolean busy) {
        state.set(new GoalState(
                normalize(title),
                List.copyOf(subgoals == null ? List.of() : subgoals),
                busy,
                Instant.now(),
                ""));
    }

    public void updateProgress(String progressMessage) {
        state.updateAndGet(current -> current.withProgress(progressMessage));
    }

    public void markComplete(String message) {
        state.updateAndGet(current -> current.withCompletion(message));
    }

    public boolean isBusy() {
        return state.get().busy();
    }

    public String statusLine() {
        GoalState current = state.get();
        if (!current.busy()) {
            return "Knowledge index ready.";
        }

        StringBuilder builder = new StringBuilder();
        if (!current.title().isBlank()) {
            builder.append(current.title());
        } else {
            builder.append("Working");
        }

        if (!current.progress().isBlank()) {
            builder.append(" - ").append(current.progress());
        }

        if (!current.subgoals().isEmpty()) {
            builder.append(" [").append(String.join(" | ", current.subgoals())).append("]");
        }

        return builder.toString();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    public record GoalState(String title, List<String> subgoals, boolean busy, Instant startedAt, String progress) {
        static GoalState idle() {
            return new GoalState("", List.of(), false, Instant.EPOCH, "");
        }

        GoalState withProgress(String newProgress) {
            return new GoalState(title, subgoals, busy, startedAt, normalize(newProgress));
        }

        GoalState withCompletion(String completionMessage) {
            return new GoalState(title, subgoals, false, startedAt, normalize(completionMessage));
        }
    }
}
