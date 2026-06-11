package com.saintmagic.gemmabuddy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Serialized knowledge report for one loaded mod.
 */
public record ModKnowledgeReport(
        String modId,
        String displayName,
        String version,
        List<String> itemRegistryEntries,
        List<String> blockRegistryEntries,
        List<String> entityRegistryEntries,
        List<String> recipeIds,
        List<String> tagIds,
        List<String> advancementIds,
        List<String> creativeTabHints,
        List<String> keywords) {

    public ModKnowledgeReport {
        itemRegistryEntries = List.copyOf(itemRegistryEntries == null ? List.of() : itemRegistryEntries);
        blockRegistryEntries = List.copyOf(blockRegistryEntries == null ? List.of() : blockRegistryEntries);
        entityRegistryEntries = List.copyOf(entityRegistryEntries == null ? List.of() : entityRegistryEntries);
        recipeIds = List.copyOf(recipeIds == null ? List.of() : recipeIds);
        tagIds = List.copyOf(tagIds == null ? List.of() : tagIds);
        advancementIds = List.copyOf(advancementIds == null ? List.of() : advancementIds);
        creativeTabHints = List.copyOf(creativeTabHints == null ? List.of() : creativeTabHints);
        keywords = List.copyOf(keywords == null ? List.of() : keywords);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("modId", modId);
        object.addProperty("displayName", displayName);
        object.addProperty("version", version);
        object.add("itemRegistryEntries", toArray(itemRegistryEntries));
        object.add("blockRegistryEntries", toArray(blockRegistryEntries));
        object.add("entityRegistryEntries", toArray(entityRegistryEntries));
        object.add("recipeIds", toArray(recipeIds));
        object.add("tagIds", toArray(tagIds));
        object.add("advancementIds", toArray(advancementIds));
        object.add("creativeTabHints", toArray(creativeTabHints));
        object.add("keywords", toArray(keywords));
        return object;
    }

    public static ModKnowledgeReport fromJson(JsonObject object) {
        return new ModKnowledgeReport(
                readString(object, "modId"),
                readString(object, "displayName"),
                readString(object, "version"),
                readList(object, "itemRegistryEntries"),
                readList(object, "blockRegistryEntries"),
                readList(object, "entityRegistryEntries"),
                readList(object, "recipeIds"),
                readList(object, "tagIds"),
                readList(object, "advancementIds"),
                readList(object, "creativeTabHints"),
                readList(object, "keywords"));
    }

    public String summaryLine() {
        return modId + " (" + displayName + " " + version + ")"
                + " - items=" + itemRegistryEntries.size()
                + ", blocks=" + blockRegistryEntries.size()
                + ", entities=" + entityRegistryEntries.size()
                + ", recipes=" + recipeIds.size();
    }

    public List<String> markdownLines() {
        List<String> lines = new ArrayList<>();
        lines.add("# " + displayName + " (" + modId + ")");
        lines.add("");
        lines.add("- Version: " + version);
        lines.add("- Items: " + itemRegistryEntries.size());
        lines.add("- Blocks: " + blockRegistryEntries.size());
        lines.add("- Entities: " + entityRegistryEntries.size());
        lines.add("- Recipes: " + recipeIds.size());
        lines.add("- Tags: " + tagIds.size());
        lines.add("- Advancements: " + advancementIds.size());
        if (!creativeTabHints.isEmpty()) {
            lines.add("- Creative tabs: " + String.join(", ", creativeTabHints));
        }
        if (!keywords.isEmpty()) {
            lines.add("");
            lines.add("## Keywords");
            lines.add(String.join(", ", keywords));
        }
        if (!itemRegistryEntries.isEmpty()) {
            lines.add("");
            lines.add("## Items");
            for (String entry : itemRegistryEntries) {
                lines.add("- " + entry);
            }
        }
        if (!blockRegistryEntries.isEmpty()) {
            lines.add("");
            lines.add("## Blocks");
            for (String entry : blockRegistryEntries) {
                lines.add("- " + entry);
            }
        }
        if (!entityRegistryEntries.isEmpty()) {
            lines.add("");
            lines.add("## Entities");
            for (String entry : entityRegistryEntries) {
                lines.add("- " + entry);
            }
        }
        return lines;
    }

    public boolean matches(String query) {
        String normalized = normalizeLoose(query);
        if (normalized.isBlank()) {
            return false;
        }

        return normalizeLoose(modId).contains(normalized)
                || normalizeLoose(displayName).contains(normalized)
                || normalizeLoose(version).contains(normalized)
                || containsAny(normalized, itemRegistryEntries)
                || containsAny(normalized, blockRegistryEntries)
                || containsAny(normalized, entityRegistryEntries)
                || containsAny(normalized, recipeIds)
                || containsAny(normalized, tagIds)
                || containsAny(normalized, advancementIds)
                || containsAny(normalized, creativeTabHints)
                || containsAny(normalized, keywords);
    }

    public List<String> snippetsFor(String query) {
        String normalized = normalizeLoose(query);
        List<String> snippets = new ArrayList<>();

        if (matches(normalized)) {
            snippets.add(summaryLine());
        }

        addMatches(snippets, "item", itemRegistryEntries, normalized, 10);
        addMatches(snippets, "block", blockRegistryEntries, normalized, 10);
        addMatches(snippets, "entity", entityRegistryEntries, normalized, 10);
        addMatches(snippets, "recipe", recipeIds, normalized, 6);
        addMatches(snippets, "tag", tagIds, normalized, 6);
        addMatches(snippets, "advancement", advancementIds, normalized, 6);
        addMatches(snippets, "creative tab", creativeTabHints, normalized, 4);
        return snippets;
    }

    private static void addMatches(List<String> snippets, String label, List<String> values, String query, int limit) {
        if (values.isEmpty()) {
            return;
        }

        int added = 0;
        for (String value : values) {
            if (normalizeLoose(value).contains(query)) {
                snippets.add(label + ": " + value);
                added++;
                if (added >= limit) {
                    break;
                }
            }
        }
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> readList(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        object.getAsJsonArray(key).forEach(element -> values.add(element.getAsString()));
        return values;
    }

    private static String readString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static boolean containsAny(String query, List<String> values) {
        for (String value : values) {
            if (normalizeLoose(value).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizeLoose(String text) {
        return normalize(text).replaceAll("[^a-z0-9]+", "");
    }
}
