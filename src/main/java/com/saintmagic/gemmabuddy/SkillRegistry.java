package com.saintmagic.gemmabuddy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authored, deterministic plan-only skills. No skill can execute world changes
 * in this alpha.
 */
public final class SkillRegistry {
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    public SkillRegistry() {
        register(new SkillDefinition("build_basic_shelter", "Build basic shelter",
                "A compact lit shelter with a door and basic utility space.",
                List.of(
                        new MaterialEstimate("minecraft:planks", 64, "any plank family"),
                        new MaterialEstimate("minecraft:torch", 8, "lighting"),
                        new MaterialEstimate("minecraft:door", 1, "matching door preferred")),
                List.of("Choose a flat safe site.", "Confirm the footprint and material family.",
                        "Build floor, walls, doorway, roof, and lighting.", "Verify spawn safety and exits.")));
        register(new SkillDefinition("make_starter_tools", "Make starter tools",
                "Prepare a basic pickaxe, axe, shovel, sword, and crafting table.",
                List.of(
                        new MaterialEstimate("minecraft:planks", 13, "any plank family"),
                        new MaterialEstimate("minecraft:stick", 9, "crafted from planks")),
                List.of("Confirm available wood family.", "Craft planks and sticks.",
                        "Craft a table and starter wooden tools.", "Upgrade to stone when 11 cobblestone is available.")));
        register(new SkillDefinition("prepare_enchanting_setup", "Prepare enchanting setup",
                "Plan the full vanilla enchanting-table area.",
                List.of(
                        new MaterialEstimate("minecraft:enchanting_table", 1, "exact item"),
                        new MaterialEstimate("minecraft:bookshelf", 15, "maximum table power"),
                        new MaterialEstimate("minecraft:lapis_lazuli", 3, "initial enchanting reserve")),
                List.of("Craft or obtain the enchanting table.", "Prepare 15 bookshelves.",
                        "Leave one air block between shelves and table.", "Bring lapis and experience levels.")));
        register(new SkillDefinition("organize_next_steps", "Organize next steps",
                "Turn the current goal into a safe read-only checklist.",
                List.of(), List.of("Review current goal and inventory.", "Check nearby danger and useful blocks.",
                        "Resolve missing recipe facts.", "Create a validated plan before requesting any action.")));
    }

    public SkillPlan plan(String skillId, StateSnapshot snapshot) {
        SkillDefinition definition = skills.get(skillId);
        if (definition == null) {
            return new SkillPlan(skillId, "Unknown skill.", List.of(), List.of(), List.of(), true, false);
        }
        List<String> materials = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Map<String, Integer> inventory = new LinkedHashMap<>();
        snapshot.inventoryItems().forEach(item -> inventory.put(item.id(), item.count()));

        for (MaterialEstimate estimate : definition.materials()) {
            materials.add(estimate.registryId() + " x" + estimate.count() + " (" + estimate.note() + ")");
            int available = inventory.getOrDefault(estimate.registryId(), 0);
            if (available < estimate.count()) {
                missing.add(estimate.registryId() + " x" + (estimate.count() - available));
            }
        }
        return new SkillPlan(definition.id(), definition.label(), materials, missing, definition.steps(), true, false);
    }

    public SkillDefinition find(String id) {
        return skills.get(id);
    }

    public List<SkillDefinition> all() {
        return List.copyOf(skills.values());
    }

    private void register(SkillDefinition skill) {
        if (skills.putIfAbsent(skill.id(), skill) != null) {
            throw new IllegalStateException("Duplicate GemmaBuddy skill id: " + skill.id());
        }
    }

    public record SkillDefinition(String id, String label, String description, List<MaterialEstimate> materials,
            List<String> steps) {
    }

    public record MaterialEstimate(String registryId, int count, String note) {
    }

    public record SkillPlan(String skillId, String label, List<String> materials, List<String> missing,
            List<String> steps, boolean approvalRequiredForExecution, boolean canExecute) {
    }
}
