package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.loading.FMLPaths;

/**
 * One bounded, persistent, supervised work order per player.
 *
 * This milestone is intentionally assisted for mining, building, and crafting.
 * Approval covers the exact task scope once; individual assisted steps never
 * request repeated approval.
 */
public final class WorkOrderService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve(GemmaBuddy.MOD_ID)
            .resolve("work-orders").resolve("latest.json");
    private final Map<UUID, WorkOrder> active = new ConcurrentHashMap<>();
    private final GemmaBuddyConfig config;
    private final SafetyManager safety;
    private final FindService find;
    private final KnowledgeRepository repository;
    private final ProgressionBrain progression;
    private final MemoryManager memory;
    private long sequence;

    public WorkOrderService(GemmaBuddyConfig config, SafetyManager safety, FindService find,
            KnowledgeRepository repository, ProgressionBrain progression, MemoryManager memory) {
        this.config = config;
        this.safety = safety;
        this.find = find;
        this.repository = repository;
        this.progression = progression;
        this.memory = memory;
        load();
    }

    public ActionResult create(ServerPlayer player, String request) {
        String normalized = normalize(request);
        WorkOrder existing = active.get(player.getUUID());
        if (existing != null && List.of(WorkOrder.WorkOrderStatus.PLANNED,
                WorkOrder.WorkOrderStatus.WAITING_FOR_APPROVAL, WorkOrder.WorkOrderStatus.RUNNING,
                WorkOrder.WorkOrderStatus.PAUSED).contains(existing.status())) {
            return fail(player, "A Work Order is already active. Stop it before starting another.");
        }
        if (!config.workOrdersEnabled()) {
            return fail(player, "Work Orders are disabled in GemmaBuddy settings.");
        }
        if (config.autonomyMode() == GemmaBuddyConfig.AutonomyMode.READ_ONLY
                || safety.permissions().state(player.getUUID()).level() == PermissionManager.PermissionLevel.READ_ONLY) {
            return fail(player, "Work Orders are locked by read-only mode. Set permissions to ask-before-action "
                    + "and choose assisted or approved-batch autonomy.");
        }
        if (normalized.isBlank()) {
            return fail(player, "Use: gemma work <small bounded request>");
        }

        WorkOrder order = plan(player, normalized);
        active.put(player.getUUID(), order);
        save();
        showPreview(player, order);
        if (order.status() == WorkOrder.WorkOrderStatus.BLOCKED
                || order.status() == WorkOrder.WorkOrderStatus.PLAN_ONLY
                || order.status() == WorkOrder.WorkOrderStatus.COMPLETED) {
            return order.status() == WorkOrder.WorkOrderStatus.COMPLETED
                    ? ActionResult.success(order.resultMessage())
                    : ActionResult.failure(order.resultMessage());
        }
        return requestSingleApproval(player, order);
    }

    public ActionResult status(ServerPlayer player) {
        WorkOrder order = active.get(player.getUUID());
        if (order == null) {
            GemmaBuddy.sendLine(player, "No active Work Order.");
            return ActionResult.failure("No active Work Order.");
        }
        playerLines(order).forEach(line -> GemmaBuddy.sendLine(player, line));
        return ActionResult.success("Work Order status shown.");
    }

    public ActionResult approve(ServerPlayer player) {
        return safety.approve(player);
    }

    public ActionResult deny(ServerPlayer player) {
        ActionResult result = safety.deny(player);
        cancel(player, "Work Order denied.");
        return result;
    }

    public ActionResult cancel(ServerPlayer player, String reason) {
        WorkOrder order = active.get(player.getUUID());
        if (order == null) {
            return ActionResult.failure("No active Work Order.");
        }
        WorkOrder cancelled = order.withStatus(WorkOrder.WorkOrderStatus.CANCELLED, reason);
        active.put(player.getUUID(), cancelled);
        save();
        GemmaBuddy.sendLine(player, "Work Order cancelled: " + reason);
        return ActionResult.success("Work Order cancelled.");
    }

    public ActionResult pause(ServerPlayer player) {
        WorkOrder order = active.get(player.getUUID());
        if (order == null || order.status() != WorkOrder.WorkOrderStatus.RUNNING) {
            return fail(player, "No running Work Order to pause.");
        }
        active.put(player.getUUID(), order.withStatus(WorkOrder.WorkOrderStatus.PAUSED, "Paused by player."));
        save();
        GemmaBuddy.sendLine(player, "Work Order paused.");
        return ActionResult.success("Work Order paused.");
    }

    public ActionResult resume(ServerPlayer player) {
        WorkOrder order = active.get(player.getUUID());
        if (order == null || order.status() != WorkOrder.WorkOrderStatus.PAUSED) {
            return fail(player, "No paused Work Order to resume.");
        }
        active.put(player.getUUID(), order.withStatus(WorkOrder.WorkOrderStatus.RUNNING, "Resumed by player."));
        save();
        GemmaBuddy.sendLine(player, "Work Order resumed.");
        return ActionResult.success("Work Order resumed.");
    }

    public ActionResult nextStep(ServerPlayer player) {
        WorkOrder order = active.get(player.getUUID());
        if (order == null || !List.of(WorkOrder.WorkOrderStatus.RUNNING, WorkOrder.WorkOrderStatus.PAUSED)
                .contains(order.status())) {
            return fail(player, "No approved assisted Work Order is ready for a next step.");
        }
        WorkOrder advanced = order.advance();
        active.put(player.getUUID(), advanced);
        save();
        if (advanced.status() == WorkOrder.WorkOrderStatus.COMPLETED) {
            GemmaBuddy.sendLine(player, "Done: " + advanced.request() + ".");
        } else {
            GemmaBuddy.sendLine(player, "Next: " + advanced.steps().get(advanced.currentStep()).instruction());
        }
        return ActionResult.success("Advanced Work Order.");
    }

    public WorkOrder current(UUID playerId) {
        return active.get(playerId);
    }

    public String compactStatus(UUID playerId) {
        WorkOrder order = active.get(playerId);
        if (order == null) {
            return "Work: none";
        }
        return "Work: " + order.type().name().toLowerCase(Locale.ROOT) + " "
                + order.status().name().toLowerCase(Locale.ROOT) + " "
                + Math.min(order.currentStep(), order.steps().size()) + "/" + order.steps().size();
    }

    public void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            WorkOrder order = active.get(player.getUUID());
            if (order == null) {
                continue;
            }
            if (order.status() == WorkOrder.WorkOrderStatus.WAITING_FOR_APPROVAL
                    && Duration.between(order.updatedAt(), Instant.now()).toSeconds() > 65) {
                safety.stopAll(player);
                interrupt(player, order, WorkOrder.WorkOrderStatus.BLOCKED, "approval expired");
                continue;
            }
            if (order.status() != WorkOrder.WorkOrderStatus.RUNNING) {
                continue;
            }
            if (Duration.between(order.createdAt(), Instant.now()).toSeconds() > order.maxSeconds()) {
                interrupt(player, order, WorkOrder.WorkOrderStatus.BLOCKED, "time budget reached");
                continue;
            }
            if (config.autoPauseWhenPlayerMovesFarAway() && order.targetPosition() != null
                    && !order.targetPosition().equals(BlockPos.ZERO)
                    && order.targetPosition().distSqr(player.blockPosition()) > order.maxDistance() * order.maxDistance()) {
                interrupt(player, order, WorkOrder.WorkOrderStatus.PAUSED, "player moved outside approved distance");
                continue;
            }
            if (config.autoPauseOnPlayerCombat() && player.getLastHurtByMob() != null
                    && player.tickCount - player.getLastHurtByMobTimestamp() < 100) {
                interrupt(player, order, WorkOrder.WorkOrderStatus.PAUSED, "player entered combat");
                continue;
            }
            if (config.autoPauseOnInventoryFull() && player.getInventory().getFreeSlot() < 0) {
                interrupt(player, order, WorkOrder.WorkOrderStatus.PAUSED, "inventory is full");
                continue;
            }
            detectAssistedProgress(player, order);
        }
    }

    public List<String> playerLines(WorkOrder order) {
        List<String> lines = new ArrayList<>();
        lines.add("Work Order: " + order.request() + " [" + order.status().name().toLowerCase(Locale.ROOT) + "].");
        lines.add("Mode: " + config.autonomyMode().configValue() + " | approved scope: per_task | assisted="
                + order.assisted() + ".");
        lines.add("Budget: " + order.maxActions() + " actions, " + order.maxDistance() + " blocks, "
                + order.maxSeconds() + " seconds.");
        lines.add("Preview: " + order.preview());
        if (!order.requiredMaterials().isEmpty()) {
            lines.add("Required: " + String.join(", ", order.requiredMaterials()) + ".");
        }
        if (!order.missingMaterials().isEmpty()) {
            lines.add("Missing: " + String.join(", ", order.missingMaterials()) + ".");
        }
        if (!order.safetyWarnings().isEmpty()) {
            lines.add("Safety: " + String.join(" | ", order.safetyWarnings()) + ".");
        }
        if (order.currentStep() < order.steps().size()) {
            lines.add("Current step: " + order.steps().get(order.currentStep()).instruction());
        }
        if (!order.resultMessage().isBlank()) {
            lines.add("Result: " + order.resultMessage());
        }
        return lines;
    }

    private WorkOrder plan(ServerPlayer player, String request) {
        String lower = request.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mine ")) {
            return planMining(player, request, false);
        }
        if (lower.startsWith("gather ")) {
            return planMining(player, request, true);
        }
        if (lower.contains("build basic shelter") || lower.contains("build a basic shelter")
                || lower.equals("build shelter") || lower.equals("basic shelter")
                || lower.contains("prepare camp") || lower.contains("prepare survival setup")) {
            return planShelter(player, request);
        }
        if (lower.startsWith("craft ") || lower.startsWith("make ")) {
            String target = request.substring(request.indexOf(' ') + 1).trim();
            return planCrafting(player, request, target);
        }
        if (lower.contains("prepare starter tools")) {
            return planSkill(player, request, "make_starter_tools");
        }
        if (lower.contains("prepare enchanting")) {
            return planSkill(player, request, "prepare_enchanting_setup");
        }
        if (lower.contains("current goal") || lower.equals("work on goal")
                || lower.equals("follow current goal")) {
            return planGoal(player, request);
        }
        if (lower.startsWith("work toward ")) {
            return planProgressTarget(player, request, request.substring("work toward ".length()).trim());
        }
        if (lower.startsWith("work on ")) {
            return planProgressTarget(player, request, request.substring("work on ".length()).trim());
        }
        if (lower.startsWith("work ")) {
            return plan(player, request.substring(5).trim());
        }
        return blocked(player, WorkOrder.WorkOrderType.GOAL, request, "", "",
                "Unsupported bounded Work Order. Try mine, gather, build shelter, craft, or work on current goal.");
    }

    private WorkOrder planMining(ServerPlayer player, String request, boolean gathering) {
        ParsedCountTarget parsed = parseCountTarget(request);
        String targetQuery = parsed.target();
        if (targetQuery.equalsIgnoreCase("cobblestone")) {
            targetQuery = "stone";
        }
        int count = Math.min(parsed.count(), Math.min(16, config.maxWorkOrderBlocks()));
        FindService.FindResult result = find.find(player, targetQuery, config.maxWorkOrderDistance());
        if (!result.resolvedId().isBlank() && !result.trackable()) {
            return new WorkOrder(nextId(), gathering ? WorkOrder.WorkOrderType.GATHERING
                    : WorkOrder.WorkOrderType.MINING, request, result.resolvedId(), targetQuery, count,
                    WorkOrder.WorkOrderStatus.COMPLETED, true, result.message(), List.of(), List.of(), List.of(),
                    List.of(), 0, player.level().dimension().location().toString(), BlockPos.ZERO, result.source(),
                    count, config.maxWorkOrderDistance(), config.maxWorkOrderSeconds(), 0, Instant.now(), Instant.now(),
                    result.message());
        }
        if (result.resolvedId().isBlank() || !result.trackable()) {
            return blocked(player, gathering ? WorkOrder.WorkOrderType.GATHERING : WorkOrder.WorkOrderType.MINING,
                    request, result.resolvedId(), targetQuery, result.message());
        }
        WorkOrderSafetyRules.Validation validation = new WorkOrderSafetyRules().validateMining(player,
                result.resolvedId(), count, result.position(), config);
        if (!validation.allowed()) {
            return blocked(player, gathering ? WorkOrder.WorkOrderType.GATHERING : WorkOrder.WorkOrderType.MINING,
                    request, result.resolvedId(), targetQuery, validation.reason());
        }
        List<WorkOrder.WorkOrderStep> steps = List.of(
                new WorkOrder.WorkOrderStep("Guide", "Follow GemmaBuddy to " + result.position().toShortString() + "."),
                new WorkOrder.WorkOrderStep("Mine", "Player mines up to " + count + " valid "
                        + result.resolvedId() + " blocks inside the approved scope."),
                new WorkOrder.WorkOrderStep("Verify", "GemmaBuddy verifies block/item progress and reports once."));
        return planned(player, gathering ? WorkOrder.WorkOrderType.GATHERING : WorkOrder.WorkOrderType.MINING,
                request, result.resolvedId(), targetQuery, count,
                (gathering ? "Gather" : "Mine") + " up to " + count + " nearby " + result.resolvedId()
                        + " within " + config.maxWorkOrderDistance() + " blocks.",
                List.of(), List.of(), validation.warnings(), steps, result.position(), result.source());
    }

    private WorkOrder planShelter(ServerPlayer player, String request) {
        List<BlockPos> positions = shelterBlueprint(findShelterOrigin(player));
        WorkOrderSafetyRules.Validation validation = new WorkOrderSafetyRules().validateBuildPositions(player,
                positions, config);
        Map<String, Integer> materials = chooseBuildingMaterial(player, positions.size());
        List<String> required = materials.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue()).toList();
        List<String> missing = missingFromInventory(player, materials);
        if (!validation.allowed()) {
            return blocked(player, WorkOrder.WorkOrderType.BUILDING, request, "", "Basic Shelter",
                    validation.reason());
        }
        List<WorkOrder.WorkOrderStep> steps = new ArrayList<>();
        steps.add(new WorkOrder.WorkOrderStep("Review", "Review the 5x5, 3-high shelter outline."));
        positions.stream().limit(12).forEach(pos -> steps.add(
                new WorkOrder.WorkOrderStep("Place", "Place an approved building block at " + pos.toShortString())));
        steps.add(new WorkOrder.WorkOrderStep("Continue",
                "Continue the saved blueprint coordinates; no non-air block may be overwritten."));
        WorkOrder order = planned(player, WorkOrder.WorkOrderType.BUILDING, request, "", "Basic Shelter",
                positions.size(), "5x5 shelter, 3-high walls, doorway, simple roof at "
                        + positions.get(0).toShortString() + "; assisted placement only.",
                required, missing, validation.warnings(), steps, positions.get(0), "generated_blueprint");
        return missing.isEmpty() ? order : order.withStatus(WorkOrder.WorkOrderStatus.BLOCKED,
                "Materials are missing; preview saved but execution cannot start.");
    }

    private WorkOrder planCrafting(ServerPlayer player, String request, String target) {
        ProgressionBrain.ProgressionReport report = progression.analyze(player, target);
        if (!report.error().isBlank()) {
            return blocked(player, WorkOrder.WorkOrderType.CRAFTING, request, report.resolvedTargetId(),
                    target, report.error());
        }
        List<WorkOrder.WorkOrderStep> steps = List.of(
                new WorkOrder.WorkOrderStep("Recipe", report.recipe().isEmpty()
                        ? "No exact recipe is indexed."
                        : "Use " + String.join(", ", report.recipe()) + "."),
                new WorkOrder.WorkOrderStep("Craft", "Player performs the craft; GemmaBuddy will not fake inventory changes."),
                new WorkOrder.WorkOrderStep("Verify", "GemmaBuddy verifies that the output appeared in inventory."));
        WorkOrder order = planned(player, WorkOrder.WorkOrderType.CRAFTING, request, report.resolvedTargetId(),
                report.displayName(), 1, "Assisted crafting for " + report.displayName() + ".",
                report.recipe(), report.missingMaterials(), List.of("Recipe counts are Java-derived",
                        "No automatic inventory manipulation"), steps, BlockPos.ZERO, "recipe");
        return report.missingMaterials().isEmpty() ? order : order.withStatus(WorkOrder.WorkOrderStatus.BLOCKED,
                "Missing materials; assisted recipe remains available.");
    }

    private WorkOrder planSkill(ServerPlayer player, String request, String skillId) {
        if ("prepare_enchanting_setup".equals(skillId)) {
            ProgressionBrain.ProgressionReport report = progression.analyze(player, "enchanting table");
            List<WorkOrder.WorkOrderStep> steps = new ArrayList<>();
            progression.playerLines(report).forEach(line -> steps.add(new WorkOrder.WorkOrderStep("Progression", line)));
            steps.add(new WorkOrder.WorkOrderStep("Reminder", "Prepare bookshelves and lapis after the table."));
            return planned(player, WorkOrder.WorkOrderType.GOAL, request, report.resolvedTargetId(),
                    "Enchanting Setup", steps.size(), "Progression-backed assisted enchanting setup.",
                    report.recipe(), report.missingMaterials(), List.of("No resource location is invented"),
                    steps, BlockPos.ZERO, "progression");
        }
        SkillRegistry.SkillPlan plan = GemmaBuddy.skillRegistry().plan(skillId, StateSnapshot.capture(player));
        List<WorkOrder.WorkOrderStep> steps = plan.steps().stream()
                .map(value -> new WorkOrder.WorkOrderStep("Skill", value)).toList();
        WorkOrder order = planned(player, WorkOrder.WorkOrderType.CRAFTING, request, skillId, plan.label(),
                steps.size(), plan.label() + " assisted plan.", plan.materials(), plan.missing(),
                List.of("No inventory manipulation", "Player confirms each manual craft"), steps, BlockPos.ZERO,
                "skill");
        return plan.missing().isEmpty() ? order : order.withStatus(WorkOrder.WorkOrderStatus.BLOCKED,
                "Missing materials; plan saved.");
    }

    private WorkOrder planGoal(ServerPlayer player, String request) {
        String goal = memory.currentGoal();
        if (goal.isBlank()) {
            return blocked(player, WorkOrder.WorkOrderType.GOAL, request, "", "", "No active goal is set.");
        }
        ProgressionBrain.ProgressionReport report = progression.analyze(player, goal);
        if (!report.error().isBlank()) {
            return blocked(player, WorkOrder.WorkOrderType.GOAL, request, "", goal, report.error());
        }
        if (report.craftableNow()) {
            return planCrafting(player, "craft " + goal, goal);
        }
        List<WorkOrder.WorkOrderStep> steps = progression.playerLines(report).stream()
                .map(value -> new WorkOrder.WorkOrderStep("Progression", value)).toList();
        return planned(player, WorkOrder.WorkOrderType.GOAL, request, report.resolvedTargetId(),
                report.displayName(), steps.size(), "Follow current goal using deterministic progression evidence.",
                report.recipe(), report.missingMaterials(), List.of("Plan/assisted only when source is unknown"),
                steps, BlockPos.ZERO, "progression");
    }

    private WorkOrder planProgressTarget(ServerPlayer player, String request, String target) {
        ProgressionBrain.ProgressionReport report = progression.analyze(player, target);
        if (!report.error().isBlank()) {
            return blocked(player, WorkOrder.WorkOrderType.GOAL, request, "", target, report.error());
        }
        if (report.craftableNow()) {
            return planCrafting(player, "craft " + target, target);
        }
        List<WorkOrder.WorkOrderStep> steps = progression.playerLines(report).stream()
                .map(value -> new WorkOrder.WorkOrderStep("Progression", value)).toList();
        return planned(player, WorkOrder.WorkOrderType.GOAL, request, report.resolvedTargetId(),
                report.displayName(), steps.size(), "Work toward " + report.displayName()
                        + " using deterministic local evidence.",
                report.recipe(), report.missingMaterials(), List.of("Assisted progression only"),
                steps, BlockPos.ZERO, "progression");
    }

    private WorkOrder planned(ServerPlayer player, WorkOrder.WorkOrderType type, String request, String targetId,
            String targetName, int count, String preview, List<String> required, List<String> missing,
            List<String> warnings, List<WorkOrder.WorkOrderStep> steps, BlockPos target, String source) {
        boolean manual = config.autonomyMode() == GemmaBuddyConfig.AutonomyMode.MANUAL;
        return new WorkOrder(nextId(), type, request, targetId, targetName, count,
                manual ? WorkOrder.WorkOrderStatus.PLAN_ONLY : WorkOrder.WorkOrderStatus.PLANNED,
                true, preview, List.copyOf(required), List.copyOf(missing), List.copyOf(warnings),
                List.copyOf(steps), 0, player.level().dimension().location().toString(),
                target == null ? BlockPos.ZERO : target.immutable(), source, Math.max(1, count),
                config.maxWorkOrderDistance(), config.maxWorkOrderSeconds(), 0, Instant.now(), Instant.now(),
                manual ? "Manual autonomy mode: preview only." : "");
    }

    private WorkOrder blocked(ServerPlayer player, WorkOrder.WorkOrderType type, String request, String targetId,
            String targetName, String reason) {
        return new WorkOrder(nextId(), type, request, targetId, targetName, 0, WorkOrder.WorkOrderStatus.BLOCKED,
                true, reason, List.of(), List.of(), List.of(reason), List.of(), 0,
                player.level().dimension().location().toString(), BlockPos.ZERO, "blocked", 0,
                config.maxWorkOrderDistance(), config.maxWorkOrderSeconds(), 0, Instant.now(), Instant.now(), reason);
    }

    private ActionResult requestSingleApproval(ServerPlayer player, WorkOrder order) {
        WorkOrder waiting = order.withStatus(WorkOrder.WorkOrderStatus.WAITING_FOR_APPROVAL,
                "One approval covers only this exact bounded scope.");
        active.put(player.getUUID(), waiting);
        save();
        String scope = waiting.preview() + " Budget: " + waiting.maxActions() + " actions, "
                + waiting.maxDistance() + " blocks, " + waiting.maxSeconds()
                + " seconds. Allowed target=" + waiting.targetId()
                + ". Forbidden: scope expansion, containers, machines, entities, non-air overwrite. "
                + "Use gemma stop at any time.";
        SafetyManager.SafetyLevel level = waiting.type() == WorkOrder.WorkOrderType.CRAFTING
                ? SafetyManager.SafetyLevel.INVENTORY
                : SafetyManager.SafetyLevel.WORLD_CHANGE;
        return safety.requestBoundedApproval(player, "work_order", scope, level, () -> startApproved(player));
    }

    private ActionResult startApproved(ServerPlayer player) {
        WorkOrder order = active.get(player.getUUID());
        if (order == null || order.status() != WorkOrder.WorkOrderStatus.WAITING_FOR_APPROVAL) {
            return ActionResult.failure("No matching Work Order is waiting for approval.");
        }
        WorkOrder running = order.withStatus(WorkOrder.WorkOrderStatus.RUNNING,
                "Approved once for the exact saved scope; assisted execution started.");
        active.put(player.getUUID(), running);
        save();
        if (running.targetPosition() != null && !running.targetPosition().equals(BlockPos.ZERO)
                && List.of(WorkOrder.WorkOrderType.MINING, WorkOrder.WorkOrderType.GATHERING)
                        .contains(running.type())) {
            GemmaBuddy.guideBuddyTo(player, running.targetPosition());
        }
        if (!config.silentDuringWork() || !config.reportOnlyOnMilestones()) {
            GemmaBuddy.sendLine(player, "Work Order started: " + running.request() + ".");
        } else if (!running.steps().isEmpty()) {
            GemmaBuddy.sendLine(player, "Started: " + running.steps().get(0).instruction());
        }
        return ActionResult.success("Work Order running in assisted mode.");
    }

    private void detectAssistedProgress(ServerPlayer player, WorkOrder order) {
        if (order.targetPosition() == null || order.targetPosition().equals(BlockPos.ZERO)) {
            return;
        }
        if (List.of(WorkOrder.WorkOrderType.MINING, WorkOrder.WorkOrderType.GATHERING).contains(order.type())) {
            String current = BuiltInRegistries.BLOCK.getKey(
                    player.level().getBlockState(order.targetPosition()).getBlock()).toString();
            if (!current.equals(order.targetId())) {
                WorkOrder advanced = order.advance();
                active.put(player.getUUID(), advanced);
                save();
                GemmaBuddy.sendLine(player, advanced.status() == WorkOrder.WorkOrderStatus.COMPLETED
                        ? "Done: " + advanced.request() + "."
                        : "Milestone: target block changed; use Work > Next step for the remaining assisted scope.");
            }
        }
    }

    private void interrupt(ServerPlayer player, WorkOrder order, WorkOrder.WorkOrderStatus status, String reason) {
        WorkOrder interrupted = order.interrupt(status, reason);
        if (interrupted.interruptions() >= config.maxInterruptionsPerWorkOrder()
                && status == WorkOrder.WorkOrderStatus.PAUSED) {
            interrupted = interrupted.withStatus(WorkOrder.WorkOrderStatus.BLOCKED,
                    "Interruption limit reached: " + reason);
        }
        active.put(player.getUUID(), interrupted);
        save();
        GemmaBuddy.sendLine(player, (interrupted.status() == WorkOrder.WorkOrderStatus.PAUSED ? "Paused: " : "Blocked: ")
                + reason + ".");
    }

    private void showPreview(ServerPlayer player, WorkOrder order) {
        GemmaBuddy.sendLine(player, "Work Order planned: " + order.request() + ".");
        GemmaBuddy.sendLine(player, order.preview());
        GemmaBuddy.sendLine(player, "Mode=" + config.autonomyMode().configValue()
                + ", approval scope=per_task, assisted=true.");
        if (!order.missingMaterials().isEmpty()) {
            GemmaBuddy.sendLine(player, "Missing: " + String.join(", ", order.missingMaterials()) + ".");
        }
    }

    private ActionResult fail(ServerPlayer player, String message) {
        GemmaBuddy.sendError(player, message);
        return ActionResult.failure(message);
    }

    private ParsedCountTarget parseCountTarget(String request) {
        String[] parts = normalize(request).split(" ");
        int index = 1;
        int count = Math.min(8, config.maxWorkOrderBlocks());
        if (parts.length > 1) {
            try {
                count = Integer.parseInt(parts[1]);
                index = 2;
            } catch (NumberFormatException ignored) {
                if ("some".equalsIgnoreCase(parts[1])) {
                    index = 2;
                }
            }
        }
        String target = String.join(" ", java.util.Arrays.copyOfRange(parts, index, parts.length));
        return new ParsedCountTarget(Math.max(1, count), target);
    }

    private BlockPos findShelterOrigin(ServerPlayer player) {
        BlockPos base = player.blockPosition().relative(player.getDirection(), 4);
        return base.above();
    }

    private List<BlockPos> shelterBlueprint(BlockPos origin) {
        List<BlockPos> positions = new ArrayList<>();
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 5; x++) {
                for (int z = 0; z < 5; z++) {
                    boolean wall = x == 0 || x == 4 || z == 0 || z == 4;
                    boolean door = z == 0 && x == 2 && y < 2;
                    if (wall && !door) {
                        positions.add(origin.offset(x, y, z));
                    }
                }
            }
        }
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                positions.add(origin.offset(x, 3, z));
            }
        }
        return positions;
    }

    private Map<String, Integer> chooseBuildingMaterial(ServerPlayer player, int needed) {
        List<String> preferred = List.of("minecraft:spruce_planks", "minecraft:oak_planks",
                "minecraft:cobblestone", "minecraft:dirt");
        Map<String, Integer> inventory = inventory(player);
        String selected = preferred.stream().max(Comparator.comparingInt(id -> inventory.getOrDefault(id, 0)))
                .orElse("minecraft:oak_planks");
        return Map.of(selected, needed);
    }

    private List<String> missingFromInventory(ServerPlayer player, Map<String, Integer> required) {
        Map<String, Integer> inventory = inventory(player);
        List<String> missing = new ArrayList<>();
        required.forEach((id, count) -> {
            int held = inventory.getOrDefault(id, 0);
            if (held < count) {
                missing.add(id + " x" + (count - held));
            }
        });
        return missing;
    }

    private Map<String, Integer> inventory(ServerPlayer player) {
        Map<String, Integer> inventory = new LinkedHashMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                inventory.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(),
                        Integer::sum);
            }
        }
        return inventory;
    }

    private synchronized String nextId() {
        return "work-" + ++sequence;
    }

    private synchronized void load() {
        try {
            if (Files.notExists(file)) {
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            sequence = root.has("sequence") ? root.get("sequence").getAsLong() : 0;
            if (root.has("orders") && root.get("orders").isJsonArray()) {
                for (var element : root.getAsJsonArray("orders")) {
                    JsonObject value = element.getAsJsonObject();
                    active.put(UUID.fromString(value.get("player").getAsString()), fromJson(value.getAsJsonObject("order")));
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("GemmaBuddy Work Orders could not be loaded.", ex);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("sequence", sequence);
            JsonArray orders = new JsonArray();
            active.forEach((player, order) -> {
                JsonObject value = new JsonObject();
                value.addProperty("player", player.toString());
                value.add("order", toJson(order));
                orders.add(value);
            });
            root.add("orders", orders);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("GemmaBuddy Work Orders could not be saved.", ex);
        }
    }

    private JsonObject toJson(WorkOrder order) {
        JsonObject value = new JsonObject();
        value.addProperty("id", order.id());
        value.addProperty("type", order.type().name());
        value.addProperty("request", order.request());
        value.addProperty("targetId", order.targetId());
        value.addProperty("targetName", order.targetName());
        value.addProperty("count", order.count());
        value.addProperty("status", order.status().name());
        value.addProperty("assisted", order.assisted());
        value.addProperty("preview", order.preview());
        value.add("required", strings(order.requiredMaterials()));
        value.add("missing", strings(order.missingMaterials()));
        value.add("warnings", strings(order.safetyWarnings()));
        JsonArray steps = new JsonArray();
        order.steps().forEach(step -> {
            JsonObject item = new JsonObject();
            item.addProperty("label", step.label());
            item.addProperty("instruction", step.instruction());
            steps.add(item);
        });
        value.add("steps", steps);
        value.addProperty("currentStep", order.currentStep());
        value.addProperty("dimension", order.dimension());
        value.addProperty("x", order.targetPosition().getX());
        value.addProperty("y", order.targetPosition().getY());
        value.addProperty("z", order.targetPosition().getZ());
        value.addProperty("source", order.source());
        value.addProperty("maxActions", order.maxActions());
        value.addProperty("maxDistance", order.maxDistance());
        value.addProperty("maxSeconds", order.maxSeconds());
        value.addProperty("interruptions", order.interruptions());
        value.addProperty("createdAt", order.createdAt().toString());
        value.addProperty("updatedAt", order.updatedAt().toString());
        value.addProperty("result", order.resultMessage());
        return value;
    }

    private WorkOrder fromJson(JsonObject value) {
        List<WorkOrder.WorkOrderStep> steps = new ArrayList<>();
        value.getAsJsonArray("steps").forEach(element -> {
            JsonObject item = element.getAsJsonObject();
            steps.add(new WorkOrder.WorkOrderStep(item.get("label").getAsString(),
                    item.get("instruction").getAsString()));
        });
        return new WorkOrder(value.get("id").getAsString(),
                WorkOrder.WorkOrderType.valueOf(value.get("type").getAsString()),
                value.get("request").getAsString(), value.get("targetId").getAsString(),
                value.get("targetName").getAsString(), value.get("count").getAsInt(),
                WorkOrder.WorkOrderStatus.valueOf(value.get("status").getAsString()),
                value.get("assisted").getAsBoolean(), value.get("preview").getAsString(),
                readStrings(value, "required"), readStrings(value, "missing"), readStrings(value, "warnings"),
                steps, value.get("currentStep").getAsInt(), value.get("dimension").getAsString(),
                new BlockPos(value.get("x").getAsInt(), value.get("y").getAsInt(), value.get("z").getAsInt()),
                value.get("source").getAsString(), value.get("maxActions").getAsInt(),
                value.get("maxDistance").getAsInt(), value.get("maxSeconds").getAsInt(),
                value.get("interruptions").getAsInt(), Instant.parse(value.get("createdAt").getAsString()),
                Instant.parse(value.get("updatedAt").getAsString()), value.get("result").getAsString());
    }

    private JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private List<String> readStrings(JsonObject value, String key) {
        List<String> result = new ArrayList<>();
        value.getAsJsonArray(key).forEach(item -> result.add(item.getAsString()));
        return result;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private record ParsedCountTarget(int count, String target) {
    }
}
