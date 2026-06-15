package com.saintmagic.gemmabuddy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.server.level.ServerPlayer;

/**
 * Deterministic progression reasoning built only from Java-owned game facts.
 */
public final class ProgressionBrain {
    private final KnowledgeRepository repository;
    private final MemoryManager memory;

    public ProgressionBrain(KnowledgeRepository repository, MemoryManager memory) {
        this.repository = repository;
        this.memory = memory;
    }

    public ProgressionReport analyze(ServerPlayer player, String requestedTarget) {
        String query = normalize(requestedTarget);
        if (query.isBlank()) {
            query = normalize(memory.currentGoal());
        }
        if (query.isBlank() || player.getServer() == null) {
            return ProgressionReport.unresolved(query, "Set a goal or provide a target first.");
        }

        Optional<KnowledgeDataverse.KnowledgeEntry> resolved = repository.resolveEntry(player.getServer(), query);
        if (resolved.isEmpty()) {
            return ProgressionReport.unresolved(query, "The local registry could not resolve that target.");
        }

        KnowledgeDataverse.KnowledgeEntry entry = resolved.get();
        Optional<KnowledgeDataverse.RecipeRecord> recipe = repository.findRecipeForOutput(player.getServer(),
                entry.registryId());
        Map<String, Integer> inventory = inventoryCounts(StateSnapshot.capture(player));
        List<String> missing = new ArrayList<>();
        List<String> recipeParts = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> dependencyPath = new ArrayList<>();

        if (recipe.isPresent()) {
            for (KnowledgeDataverse.IngredientCount ingredient : recipe.get().ingredients()) {
                recipeParts.add(ingredient.count() + " " + ingredient.label());
                int available = ingredient.itemIds().stream().mapToInt(id -> inventory.getOrDefault(id, 0)).sum();
                if (available < ingredient.count()) {
                    missing.add((ingredient.count() - available) + " " + ingredient.label());
                    addKnownSources(player, ingredient, sources);
                    addDependencyRecipe(player, ingredient, dependencyPath, 0, new java.util.LinkedHashSet<>());
                }
            }
        }

        boolean craftable = recipe.isPresent() && missing.isEmpty();
        String nextAction;
        if (recipe.isEmpty()) {
            nextAction = "No exact local recipe is indexed; use find/scan or ask for a plan-only explanation.";
        } else if (craftable) {
            nextAction = "The ingredients are available; start an assisted crafting work order.";
        } else if (!sources.isEmpty()) {
            nextAction = "Use the nearest known source for " + missing.get(0) + ".";
        } else {
            nextAction = "Scout or use find for " + missing.get(0) + "; no location will be invented.";
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("registry:" + entry.registryId());
        if (recipe.isPresent()) {
            evidence.add("recipe:" + recipe.get().recipeId());
        }
        evidence.add("inventory_snapshot");
        if (!sources.isEmpty()) {
            evidence.add("local_memory");
        }

        return new ProgressionReport(query, entry.registryId(), entry.displayName(),
                recipeParts, craftable, missing, sources, dependencyPath, nextAction, evidence,
                recipe.isPresent() ? 0.92D : 0.45D, "");
    }

    public List<String> playerLines(ProgressionReport report) {
        if (!report.error().isBlank()) {
            return List.of("Progression: " + report.error());
        }
        List<String> lines = new ArrayList<>();
        lines.add("Target: " + report.displayName() + " (" + report.resolvedTargetId() + ").");
        lines.add(report.recipe().isEmpty()
                ? "Recipe: no exact local recipe indexed."
                : "Recipe: " + String.join(", ", report.recipe()) + ".");
        lines.add("Craftable now: " + (report.craftableNow() ? "yes" : "no") + ".");
        if (!report.missingMaterials().isEmpty()) {
            lines.add("Missing: " + String.join(", ", report.missingMaterials()) + ".");
        }
        if (!report.knownSources().isEmpty()) {
            lines.add("Known sources: " + String.join(" | ", report.knownSources()) + ".");
        }
        lines.addAll(report.dependencyPath());
        lines.add("Next: " + report.nextRecommendedAction());
        lines.add("Evidence: " + String.join(", ", report.evidence())
                + " | confidence=" + String.format(java.util.Locale.ROOT, "%.2f", report.confidence()) + ".");
        return lines;
    }

    public List<String> craftableNow(ServerPlayer player, int limit) {
        if (player.getServer() == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (StateSnapshot.ItemEntry item : StateSnapshot.capture(player).inventoryItems()) {
            for (KnowledgeDataverse.RecipeRecord usage : repository.findUsagesForInput(player.getServer(), item.id())) {
                ProgressionReport report = analyze(player, usage.outputId());
                if (report.craftableNow() && !result.contains(usage.outputName())) {
                    result.add(usage.outputName());
                    if (result.size() >= limit) {
                        return result;
                    }
                }
            }
        }
        return result;
    }

    private void addKnownSources(ServerPlayer player, KnowledgeDataverse.IngredientCount ingredient,
            List<String> sources) {
        String dimension = player.level().dimension().location().toString();
        for (String id : ingredient.itemIds()) {
            for (MemoryManager.ContainerMemory container : memory.containersContaining(id, dimension)) {
                String label = container.label().isBlank() ? container.containerType() : container.label();
                sources.add(label + " at " + container.position().toShortString() + " has "
                        + container.contents().getOrDefault(id, 0) + " " + id);
            }
            for (MemoryManager.Discovery discovery : memory.discoveriesFor(id)) {
                if (dimension.equals(discovery.dimension())) {
                    sources.add(id + " last seen at " + discovery.position().toShortString());
                }
            }
        }
    }

    private void addDependencyRecipe(ServerPlayer player, KnowledgeDataverse.IngredientCount ingredient,
            List<String> dependencyPath, int depth, java.util.Set<String> visited) {
        if (ingredient.itemIds().isEmpty() || depth >= 2) {
            return;
        }
        String outputId = ingredient.itemIds().get(0);
        if (!visited.add(outputId)) {
            return;
        }
        Optional<KnowledgeDataverse.RecipeRecord> dependency = repository.findRecipeForOutput(player.getServer(), outputId);
        if (dependency.isEmpty()) {
            return;
        }
        String parts = dependency.get().ingredients().stream()
                .map(value -> value.count() + " " + value.label())
                .collect(java.util.stream.Collectors.joining(", "));
        dependencyPath.add(ingredient.label() + " path: " + parts + ".");
        dependency.get().ingredients().forEach(child ->
                addDependencyRecipe(player, child, dependencyPath, depth + 1, visited));
    }

    private Map<String, Integer> inventoryCounts(StateSnapshot snapshot) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        snapshot.inventoryItems().forEach(item -> counts.put(item.id(), item.count()));
        return counts;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    public record ProgressionReport(String requestedTarget, String resolvedTargetId, String displayName,
            List<String> recipe, boolean craftableNow, List<String> missingMaterials, List<String> knownSources,
            List<String> dependencyPath, String nextRecommendedAction, List<String> evidence, double confidence,
            String error) {
        static ProgressionReport unresolved(String target, String error) {
            return new ProgressionReport(target, "", target, List.of(), false, List.of(), List.of(), List.of(),
                    "", List.of(), 0.0D, error);
        }
    }
}
