package com.saintmagic.gemmabuddy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of the actions GemmaBuddy knows about.
 *
 * This is intentionally just metadata. The router owns execution, while the UI
 * uses this registry to build grouped buttons without duplicating command logic.
 */
public final class ActionRegistry {
    public static final String BASIC = "Basic";
    public static final String KNOWLEDGE = "Knowledge / Mods";
    public static final String BUDDY = "Buddy / Entity";
    public static final String PLANNING = "AI / Planning";
    public static final String DEBUG = "Debug / Maintenance";

    private final Map<String, ActionDefinition> actionsById = new LinkedHashMap<>();
    private final Map<String, ActionDefinition> actionsByAlias = new LinkedHashMap<>();
    private final Map<String, List<ActionDefinition>> actionsByCategory = new LinkedHashMap<>();

    public ActionRegistry() {
        register(new ActionDefinition("status", BASIC, "Status",
                "Show health, food, position, dimension, nearby entities, nearby blocks, and inventory.",
                "status", false, false, null, List.of("status")));
        register(new ActionDefinition("inventory", BASIC, "Inventory",
                "Show the compact inventory summary and item registry IDs.",
                "inventory", false, false, null, List.of("inventory")));
        register(new ActionDefinition("see", BASIC, "What do you see?",
                "Show nearby useful blocks, nearby entities, and any nearby danger.",
                "see", false, false, null, List.of("see", "what do you see")));
        register(new ActionDefinition("ask", BASIC, "Ask Gemma",
                "Send a custom question to LM Studio using the current game state.",
                "ask", false, false, null, List.of("ask")));

        register(new ActionDefinition("study_mods", KNOWLEDGE, "Study installed mods",
                "Scan the loaded mods and write local knowledge reports.",
                "study mods", true, false, null, List.of("study mods", "what mods do we have")));
        register(new ActionDefinition("knowledge_status", KNOWLEDGE, "Knowledge status",
                "Show whether the knowledge index is ready and where it is stored.",
                "knowledge status", false, false, null, List.of("knowledge status")));
        register(new ActionDefinition("knowledge_rebuild", KNOWLEDGE, "Rebuild knowledge index",
                "Force a full rebuild of the local mod knowledge reports.",
                "knowledge rebuild", true, false, null, List.of("knowledge rebuild")));
        register(new ActionDefinition("mod_report", KNOWLEDGE, "Open/select mod report",
                "Show the generated report for a specific mod id.",
                "modreport {target}", false, true, "mod id", List.of("modreport")));
        register(new ActionDefinition("what_does", KNOWLEDGE, "Ask what this item/block does",
                "Look up a modded item, block, or mod and ask GemmaBuddy to explain it using local snippets.",
                "what does {target} do", false, true, "item / block / mod", List.of("what does")));

        register(new ActionDefinition("spawn", BUDDY, "Spawn buddy",
                "Spawn the GemmaBuddy companion near the player.",
                "spawn", false, false, null, List.of("spawn")));
        register(new ActionDefinition("despawn", BUDDY, "Despawn buddy",
                "Remove the spawned GemmaBuddy companion.",
                "despawn", false, false, null, List.of("despawn")));
        register(new ActionDefinition("where", BUDDY, "Where are you?",
                "Report whether GemmaBuddy is spawned and where it is.",
                "where", false, false, null, List.of("where", "where are you")));

        register(new ActionDefinition("plan", PLANNING, "What should we do next?",
                "Ask GemmaBuddy for a plan using the current game state.",
                "what should we do next", false, false, null, List.of("what should we do next", "what should we do", "next", "plan")));

        register(new ActionDefinition("lmstudio_test", DEBUG, "LM Studio test",
                "Send a tiny request to the LM Studio endpoint and print the response.",
                "lmstudio test", false, false, null, List.of("lmstudio test")));
        register(new ActionDefinition("show_config_path", DEBUG, "Show config path",
                "Print the mod config folder path.",
                "show config path", false, false, null, List.of("show config path")));
        register(new ActionDefinition("show_knowledge_path", DEBUG, "Show knowledge folder path",
                "Print the knowledge index folder path.",
                "show knowledge folder path", false, false, null, List.of("show knowledge folder path")));
        register(new ActionDefinition("reload_config", DEBUG, "Reload config",
                "Reload local GemmaBuddy caches and report whether the knowledge index is fresh.",
                "reload config", false, false, null, List.of("reload config")));
    }

    public Collection<ActionDefinition> allActions() {
        return Collections.unmodifiableCollection(actionsById.values());
    }

    public List<String> categories() {
        return List.copyOf(actionsByCategory.keySet());
    }

    public List<ActionDefinition> actionsForCategory(String category) {
        return actionsByCategory.getOrDefault(category, List.of());
    }

    public Optional<ActionDefinition> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(actionsById.get(normalize(id)));
    }

    public Optional<ActionDefinition> findByAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(actionsByAlias.get(normalize(alias)));
    }

    public Optional<ActionDefinition> findByCommandPrefix(String text) {
        String normalized = normalize(text);
        for (ActionDefinition definition : actionsById.values()) {
            for (String alias : definition.aliases()) {
                if (normalized.equals(alias) || normalized.startsWith(alias + " ")) {
                    return Optional.of(definition);
                }
            }
        }
        return Optional.empty();
    }

    private void register(ActionDefinition definition) {
        actionsById.put(definition.id(), definition);
        actionsByCategory.computeIfAbsent(definition.category(), key -> new ArrayList<>()).add(definition);
        for (String alias : definition.aliases()) {
            actionsByAlias.put(normalize(alias), definition);
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public record ActionDefinition(
            String id,
            String category,
            String label,
            String description,
            String commandTemplate,
            boolean longRunning,
            boolean requiresInput,
            String inputPlaceholder,
            List<String> aliases) {
        public ActionDefinition {
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
        }
    }
}
