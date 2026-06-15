package com.saintmagic.gemmabuddy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Builds structured planner packets and validates model proposals in Java.
 *
 * The model only selects from supplied action_ref values. Safety and
 * prerequisite state are always recalculated here.
 */
public final class PlannerService {
    public PlannerFactPacket buildFactPacket(String request, StateSnapshot snapshot, String currentGoal,
            String knowledgeFacts) {
        List<AvailableAction> actions = List.of(
                action("inspect_state_1", "status", "", "Inspect current state", List.of(), List.of(), "read_only",
                        false, true),
                action("scan_nearby_1", "see", "", "Scan loaded nearby area", List.of(), List.of(), "read_only",
                        false, true),
                action("search_resource_wood_1", "search_for_resource", "minecraft:oak_log",
                        "Find a nearby wood source", List.of(), List.of(), "read_only", false,
                        true),
                action("search_resource_stone_1", "search_for_resource", "minecraft:stone",
                        "Find nearby stone", List.of(), List.of(), "read_only", false, true),
                action("gather_oak_logs_4", "break_block", "minecraft:oak_log", "Gather 4 oak logs", List.of(),
                        List.of("minecraft:oak_log x4"), "world_change", true, false),
                action("craft_oak_planks_16", "craft_item", "minecraft:oak_planks", "Craft 16 oak planks",
                        List.of("minecraft:oak_log x4"), List.of("minecraft:oak_planks x16"), "inventory", true,
                        false),
                action("craft_sticks_4", "craft_item", "minecraft:stick", "Craft 4 sticks",
                        List.of("minecraft:oak_planks x2"), List.of("minecraft:stick x4"), "inventory", true, false),
                action("craft_wooden_pickaxe_1", "craft_item", "minecraft:wooden_pickaxe",
                        "Craft a wooden pickaxe",
                        List.of("minecraft:oak_planks x3", "minecraft:stick x2"),
                        List.of("minecraft:wooden_pickaxe x1"), "inventory", true, false),
                action("craft_furnace_1", "craft_item", "minecraft:furnace", "Craft a furnace",
                        List.of("minecraft:cobblestone x8"), List.of("minecraft:furnace x1"), "inventory", true,
                        false),
                action("craft_bed_1", "craft_item", "minecraft:white_bed", "Craft a bed",
                        List.of("#minecraft:wool x3", "#minecraft:planks x3"), List.of("minecraft:white_bed x1"),
                        "inventory", true, false),
                action("mine_stone_for_8_cobblestone", "mine_block", "minecraft:stone",
                        "Mine stone for 8 cobblestone", List.of("minecraft:wooden_pickaxe x1"),
                        List.of("minecraft:cobblestone x8"), "world_change", true, false),
                action("follow_player_1", "follow", "player", "Follow the player", List.of(), List.of(),
                        "safe_movement", true, false));

        return new PlannerFactPacket(request, snapshot.compactSummary(), snapshot.inventoryItems(), currentGoal,
                knowledgeFacts, actions, List.of(
                        "Use only supplied action_ref values.",
                        "Do not claim a step is executable; Java validates it.",
                        "World-changing actions require approval and are blocked by default.",
                        "Never suggest cooking rotten flesh."));
    }

    public PlannerProposal parseProposal(String json) {
        JsonObject root = JsonParser.parseString(stripCodeFence(json)).getAsJsonObject();
        String answer = readString(root, "answer");
        List<ProposedStep> steps = new ArrayList<>();
        if (root.has("proposed_steps") && root.get("proposed_steps").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("proposed_steps")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject step = element.getAsJsonObject();
                steps.add(new ProposedStep(readString(step, "action_ref"), readString(step, "reason"),
                        readString(step, "depends_on_action_ref")));
            }
        }
        return new PlannerProposal(answer, steps, readStrings(root, "warnings"), readDouble(root, "confidence", 0.5D),
                readStrings(root, "questions_to_user"));
    }

    public ValidatedPlan validate(PlannerFactPacket packet, PlannerProposal proposal) {
        Map<String, AvailableAction> availableByRef = new LinkedHashMap<>();
        for (AvailableAction action : packet.availableActions()) {
            if (availableByRef.put(action.actionRef(), action) != null) {
                throw new IllegalArgumentException("Duplicate available action_ref: " + action.actionRef());
            }
        }

        Map<String, Integer> inventory = inventoryCounts(packet.inventory());
        Set<String> priorRefs = new HashSet<>();
        Set<String> proposedRefs = new HashSet<>();
        Map<String, String> priorStatuses = new HashMap<>();
        List<ValidatedStep> validated = new ArrayList<>();
        List<String> warnings = new ArrayList<>(proposal.warnings());

        for (ProposedStep proposed : proposal.proposedSteps()) {
            if (!proposedRefs.add(proposed.actionRef())) {
                validated.add(new ValidatedStep(proposed.actionRef(), "duplicate", "blocked",
                        "Duplicate action_ref in proposal.", true, List.of()));
                warnings.add("Rejected duplicate proposed action_ref " + proposed.actionRef() + ".");
                continue;
            }
            AvailableAction action = availableByRef.get(proposed.actionRef());
            if (action == null) {
                validated.add(new ValidatedStep(proposed.actionRef(), "unknown", "blocked",
                        "Unknown or invalid action_ref.", true, List.of()));
                warnings.add("Rejected unknown action_ref " + proposed.actionRef() + ".");
                continue;
            }

            List<String> missing = missingRequirements(action, inventory);
            String status = "ready";
            String reason = proposed.reason();
            if (!proposed.dependsOnActionRef().isBlank()) {
                if (!priorRefs.contains(proposed.dependsOnActionRef())) {
                    status = "blocked";
                    reason = "Dependency is not an earlier validated step.";
                } else if ("blocked".equals(priorStatuses.get(proposed.dependsOnActionRef()))) {
                    status = "blocked";
                    reason = "Required previous step is blocked.";
                } else if (!missing.isEmpty()) {
                    status = "blocked";
                    reason = "Even after the previous step, missing " + String.join(", ", missing) + ".";
                } else {
                    status = "requires_previous_step";
                    reason = proposed.reason().isBlank()
                            ? "Requires completion of " + proposed.dependsOnActionRef() + "."
                            : proposed.reason();
                }
            } else if (!missing.isEmpty()) {
                status = "blocked";
                reason = "Missing " + String.join(", ", missing) + ".";
            } else if (!action.allowedNow()) {
                status = action.approvalRequired() ? "conditional" : "blocked";
                reason = action.approvalRequired()
                        ? "Plan-only until player approval and safety checks pass."
                        : "This action is currently locked.";
            }

            if ("craft_item".equals(action.actionId()) && action.target().contains("bed")
                    && countMatching(inventory, "wool") < 3) {
                status = "blocked";
                reason = "A bed requires at least 3 wool; Java found fewer.";
            }
            if ("craft_item".equals(action.actionId()) && action.target().equals("minecraft:furnace")
                    && inventory.getOrDefault("minecraft:cobblestone", 0) < 8) {
                status = "blocked";
                reason = "A furnace requires 8 cobblestone; Java found fewer.";
            }
            if (action.target().contains("rotten_flesh") && action.actionId().contains("cook")) {
                status = "blocked";
                reason = "GemmaBuddy will not propose cooking rotten flesh.";
            }

            validated.add(new ValidatedStep(action.actionRef(), action.label(), status, reason,
                    action.approvalRequired(), missing));
            priorRefs.add(action.actionRef());
            priorStatuses.put(action.actionRef(), status);
            if (!"blocked".equals(status)) {
                applyProducedItems(inventory, action.produces());
            }
        }

        double confidence = proposal.confidence();
        if (validated.stream().anyMatch(step -> step.status().equals("blocked"))) {
            confidence = Math.min(confidence, 0.55D);
        }
        return new ValidatedPlan(proposal.answer(), validated, warnings, confidence, proposal.questionsToUser());
    }

    public String promptFor(PlannerFactPacket packet) {
        JsonObject root = new JsonObject();
        root.addProperty("request", packet.request());
        root.addProperty("player_state", packet.playerState());
        root.addProperty("current_goal", packet.currentGoal());
        root.addProperty("knowledge_facts", packet.knowledgeFacts());
        JsonArray actions = new JsonArray();
        for (AvailableAction action : packet.availableActions()) {
            JsonObject value = new JsonObject();
            value.addProperty("action_ref", action.actionRef());
            value.addProperty("action_id", action.actionId());
            value.addProperty("target", action.target());
            value.addProperty("label", action.label());
            value.add("requires", toArray(action.requires()));
            value.add("produces", toArray(action.produces()));
            value.addProperty("safety", action.safety());
            value.addProperty("approval_required", action.approvalRequired());
            value.addProperty("allowed_now", action.allowedNow());
            actions.add(value);
        }
        root.add("available_actions", actions);
        root.add("safety_rules", toArray(packet.safetyRules()));
        root.addProperty("output_schema",
                "{\"answer\":\"short text\",\"proposed_steps\":[{\"action_ref\":\"supplied ref\",\"reason\":\"why\",\"depends_on_action_ref\":\"optional earlier ref\"}],\"warnings\":[],\"confidence\":0.0,\"questions_to_user\":[]}");
        return root.toString();
    }

    public List<String> playerLines(ValidatedPlan plan) {
        List<String> lines = new ArrayList<>();
        lines.add(plan.answer().isBlank() ? "Here is a validated plan:" : plan.answer());
        int number = 1;
        for (ValidatedStep step : plan.steps()) {
            String approval = step.approvalRequired() ? " [approval]" : "";
            lines.add(number++ + ". " + step.label() + " - " + step.status() + approval + ": " + step.reason());
        }
        if (!plan.warnings().isEmpty()) {
            lines.add("Warnings: " + String.join(" | ", plan.warnings()));
        }
        return lines;
    }

    private static AvailableAction action(String ref, String id, String target, String label, List<String> requires,
            List<String> produces, String safety, boolean approval, boolean allowed) {
        return new AvailableAction(ref, id, target, label, requires, produces, safety, approval, allowed);
    }

    private static Map<String, Integer> inventoryCounts(List<StateSnapshot.ItemEntry> entries) {
        Map<String, Integer> counts = new HashMap<>();
        for (StateSnapshot.ItemEntry entry : entries) {
            counts.put(entry.id(), entry.count());
        }
        return counts;
    }

    private static List<String> missingRequirements(AvailableAction action, Map<String, Integer> inventory) {
        List<String> missing = new ArrayList<>();
        for (String requirement : action.requires()) {
            String[] parts = requirement.split(" x");
            String id = parts[0];
            int required = parts.length > 1 ? parseInt(parts[1], 1) : 1;
            int available = id.startsWith("#") ? countMatching(inventory, id.substring(1)) : inventory.getOrDefault(id, 0);
            if (available < required) {
                missing.add(id + " x" + (required - available));
            }
        }
        return missing;
    }

    private static void applyProducedItems(Map<String, Integer> inventory, List<String> produced) {
        for (String output : produced) {
            String[] parts = output.split(" x");
            if (parts.length != 2 || parts[0].startsWith("#")) {
                continue;
            }
            int count = parseInt(parts[1], 0);
            if (count > 0) {
                inventory.merge(parts[0], count, Integer::sum);
            }
        }
    }

    private static int countMatching(Map<String, Integer> inventory, String token) {
        String needle = token.toLowerCase(Locale.ROOT).replace("#minecraft:", "");
        return inventory.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).contains(needle))
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static List<String> readStrings(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        object.getAsJsonArray(key).forEach(value -> values.add(value.getAsString()));
        return values;
    }

    private static String readString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static double readDouble(JsonObject object, String key, double fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String stripCodeFence(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.startsWith("```")) {
            int newline = cleaned.indexOf('\n');
            int end = cleaned.lastIndexOf("```");
            if (newline >= 0 && end > newline) {
                return cleaned.substring(newline + 1, end).trim();
            }
        }
        return cleaned;
    }

    public record PlannerFactPacket(String request, String playerState, List<StateSnapshot.ItemEntry> inventory,
            String currentGoal, String knowledgeFacts, List<AvailableAction> availableActions,
            List<String> safetyRules) {
    }

    public record AvailableAction(String actionRef, String actionId, String target, String label,
            List<String> requires, List<String> produces, String safety, boolean approvalRequired,
            boolean allowedNow) {
    }

    public record PlannerProposal(String answer, List<ProposedStep> proposedSteps, List<String> warnings,
            double confidence, List<String> questionsToUser) {
    }

    public record ProposedStep(String actionRef, String reason, String dependsOnActionRef) {
    }

    public record ValidatedPlan(String answer, List<ValidatedStep> steps, List<String> warnings, double confidence,
            List<String> questionsToUser) {
    }

    public record ValidatedStep(String actionRef, String label, String status, String reason,
            boolean approvalRequired, List<String> missing) {
    }
}
