package com.saintmagic.gemmabuddy;

import java.time.Instant;
import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Persistent supervised work-order state.
 */
public record WorkOrder(String id, WorkOrderType type, String request, String targetId, String targetName, int count,
        WorkOrderStatus status, boolean assisted, String preview, List<String> requiredMaterials,
        List<String> missingMaterials, List<String> safetyWarnings, List<WorkOrderStep> steps, int currentStep,
        String dimension, BlockPos targetPosition, String source, int maxActions, int maxDistance, int maxSeconds,
        int interruptions, Instant createdAt, Instant updatedAt, String resultMessage) {

    public WorkOrder withStatus(WorkOrderStatus next, String message) {
        return new WorkOrder(id, type, request, targetId, targetName, count, next, assisted, preview,
                requiredMaterials, missingMaterials, safetyWarnings, steps, currentStep, dimension, targetPosition,
                source, maxActions, maxDistance, maxSeconds, interruptions, createdAt, Instant.now(),
                message == null ? "" : message);
    }

    public WorkOrder advance() {
        int nextStep = Math.min(steps.size(), currentStep + 1);
        WorkOrderStatus nextStatus = nextStep >= steps.size() ? WorkOrderStatus.COMPLETED : WorkOrderStatus.RUNNING;
        return new WorkOrder(id, type, request, targetId, targetName, count, nextStatus, assisted, preview,
                requiredMaterials, missingMaterials, safetyWarnings, steps, nextStep, dimension, targetPosition,
                source, maxActions, maxDistance, maxSeconds, interruptions, createdAt, Instant.now(),
                nextStatus == WorkOrderStatus.COMPLETED
                        ? "All assisted steps were marked complete."
                        : "Advanced to assisted step " + (nextStep + 1) + ".");
    }

    public WorkOrder interrupt(WorkOrderStatus next, String message) {
        return new WorkOrder(id, type, request, targetId, targetName, count, next, assisted, preview,
                requiredMaterials, missingMaterials, safetyWarnings, steps, currentStep, dimension, targetPosition,
                source, maxActions, maxDistance, maxSeconds, interruptions + 1, createdAt, Instant.now(), message);
    }

    public enum WorkOrderType {
        MINING,
        GATHERING,
        BUILDING,
        CRAFTING,
        GOAL
    }

    public enum WorkOrderStatus {
        PLANNED,
        WAITING_FOR_APPROVAL,
        APPROVED,
        RUNNING,
        PAUSED,
        COMPLETED,
        CANCELLED,
        FAILED,
        BLOCKED,
        PLAN_ONLY
    }

    public record WorkOrderStep(String label, String instruction) {
    }
}
