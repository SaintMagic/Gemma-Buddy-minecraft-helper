package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.slf4j.Logger;

import com.saintmagic.gemmabuddy.GemmaBuddy;
import com.saintmagic.gemmabuddy.ActionResult;
import com.saintmagic.gemmabuddy.GoalManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.advancements.AdvancementHolder;

/**
 * Local knowledge index for mod-aware answers.
 */
public final class KnowledgeIndex {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path knowledgeRoot;
    private final Path reportsRoot;
    private final Path manifestPath;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile IndexManifest manifest = IndexManifest.empty();
    private volatile Map<String, ModKnowledgeReport> reports = Map.of();
    private final ModKnowledgeReport minecraftReport;
    private volatile CompletableFuture<Void> currentTask;
    private volatile String lastResolvedKnowledgeTarget = "";

    private static final Set<String> FOLLOW_UP_PHRASES = Set.of(
            "what does it do",
            "what can i do with it",
            "what can i do with this",
            "what is it for",
            "how do i use it",
            "what can i craft with it",
            "what can i make with it",
            "what can it do",
            "can i craft with it",
            "how do i decay them into compost");

    public KnowledgeIndex() {
        Path configRoot = FMLPaths.CONFIGDIR.get().resolve(GemmaBuddy.MOD_ID);
        this.knowledgeRoot = configRoot.resolve("knowledge");
        this.reportsRoot = knowledgeRoot.resolve("mods");
        this.manifestPath = knowledgeRoot.resolve("index.json");
        this.minecraftReport = buildMinecraftReport();
        loadFromDisk();
    }

    public boolean isBusy() {
        return busy.get();
    }

    public String knowledgeRootPath() {
        return knowledgeRoot.toAbsolutePath().toString();
    }

    public String reportsRootPath() {
        return reportsRoot.toAbsolutePath().toString();
    }

    public String configRootPath() {
        return knowledgeRoot.getParent().toAbsolutePath().toString();
    }

    public String lastResolvedKnowledgeTarget() {
        return lastResolvedKnowledgeTarget;
    }

    public void rememberResolvedTarget(String registryId) {
        String normalized = normalize(registryId);
        if (!normalized.isBlank()) {
            lastResolvedKnowledgeTarget = normalized;
        }
    }

    public boolean isFollowUpQuery(String query) {
        String normalized = normalize(query);
        return FOLLOW_UP_PHRASES.contains(normalized);
    }

    public LookupResult resolveKnowledgeTarget(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return null;
        }

        LookupResult direct = lookup(normalized);
        if (direct != null) {
            rememberResolvedTarget(direct.registryId());
            return direct;
        }

        if (isFollowUpQuery(normalized) && !lastResolvedKnowledgeTarget.isBlank()) {
            LookupResult fallback = lookup(lastResolvedKnowledgeTarget);
            if (fallback != null) {
                LOGGER.info("Lookup \"{}\" -> {} (follow-up from {})", query, fallback.registryId(),
                        lastResolvedKnowledgeTarget);
                rememberResolvedTarget(fallback.registryId());
                return fallback;
            }
        }

        return null;
    }

    public String statusLine() {
        IndexManifest current = manifest;
        if (isBusy()) {
            return "Studying installed mods...";
        }
        if (current.generatedAt().isBlank()) {
            return "Knowledge index not built yet.";
        }
        return "Knowledge index ready. Indexed " + current.modCount() + " mods, " + current.itemCount()
                + " items, " + current.blockCount() + " blocks, " + current.recipeCount() + " recipes.";
    }

    public ActionResult studyMods(ServerPlayer player, boolean forceRebuild) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return ActionResult.failure("No server is available.");
        }

        if (isBusy()) {
            return ActionResult.failure("Studying installed mods is already in progress.");
        }

        String fingerprint = fingerprint(server);
        if (!forceRebuild && manifest.matches(fingerprint)) {
            loadFromDisk();
            GemmaBuddy.sendLine(player, "Knowledge index is already up to date.");
            GemmaBuddy.sendLine(player, statusLine());
            return ActionResult.success("Knowledge index is already up to date.");
        }

        busy.set(true);
        GemmaBuddy.sendLine(player, "Studying installed mods...");
        GoalManager goals = GemmaBuddy.goalManager();
        goals.setGoal("Studying installed mods", List.of("Scanning registries", "Writing mod reports"), true);
        goals.updateProgress("Starting scan");

        currentTask = CompletableFuture.runAsync(() -> {
            try {
                RebuildResult result = rebuild(server, fingerprint);
                manifest = result.manifest();
                reports = result.reports();
                saveToDisk(result.manifest(), result.reports());

                server.execute(() -> {
                    busy.set(false);
                    goals.markComplete("Knowledge index ready");
                    GemmaBuddy.sendLine(player, "Indexed " + result.manifest().modCount() + " mods, "
                            + result.manifest().itemCount() + " items, " + result.manifest().blockCount()
                            + " blocks, " + result.manifest().recipeCount() + " recipes.");
                    GemmaBuddy.sendLine(player, "Knowledge index ready.");
                });
            } catch (Exception ex) {
                LOGGER.error("Failed to rebuild GemmaBuddy knowledge index", ex);
                server.execute(() -> {
                    busy.set(false);
                    goals.clear();
                    GemmaBuddy.sendError(player, "Knowledge study failed. Check the log and try again.");
                });
            }
        });

        return ActionResult.success("Studying installed mods...");
    }

    public ActionResult knowledgeStatus(ServerPlayer player) {
        GemmaBuddy.sendLine(player, statusLine());
        GemmaBuddy.sendLine(player, "Knowledge folder: " + knowledgeRootPath());
        GemmaBuddy.sendLine(player, "Mod reports: " + reportsRootPath());
        return ActionResult.success(statusLine());
    }

    public ActionResult knowledgeRebuild(ServerPlayer player) {
        return studyMods(player, true);
    }

    public ActionResult modReport(ServerPlayer player, String modId) {
        String normalized = normalize(modId);
        if (normalized.isBlank()) {
            return ActionResult.failure("Use: gemma modreport <modid>");
        }

        ModKnowledgeReport report = reportById(normalized);
        if (report == null) {
            loadFromDisk();
            report = reportById(normalized);
        }

        if (report == null) {
            GemmaBuddy.sendLine(player, "No report exists for " + normalized + ". Run gemma study mods first.");
            return ActionResult.failure("No report exists for " + normalized + ".");
        }

        GemmaBuddy.sendLine(player, report.summaryLine());
        if (!report.itemRegistryEntries().isEmpty()) {
            GemmaBuddy.sendLine(player, "Items: " + StateSnippet.joinLimited(report.itemRegistryEntries(), 8));
        }
        if (!report.blockRegistryEntries().isEmpty()) {
            GemmaBuddy.sendLine(player, "Blocks: " + StateSnippet.joinLimited(report.blockRegistryEntries(), 8));
        }
        if (!report.entityRegistryEntries().isEmpty()) {
            GemmaBuddy.sendLine(player, "Entities: " + StateSnippet.joinLimited(report.entityRegistryEntries(), 8));
        }
        if (!report.recipeIds().isEmpty()) {
            GemmaBuddy.sendLine(player, "Recipes: " + StateSnippet.joinLimited(report.recipeIds(), 8));
        }
        return ActionResult.success(report.summaryLine());
    }

    public ActionResult whatModsDoWeHave(ServerPlayer player) {
        List<ModKnowledgeReport> reportList = new ArrayList<>(reports.values());
        if (reportList.isEmpty()) {
            loadFromDisk();
            reportList = new ArrayList<>(reports.values());
        }

        if (reportList.isEmpty()) {
            GemmaBuddy.sendLine(player, "No knowledge reports are built yet. Run gemma study mods.");
            return ActionResult.failure("No knowledge reports are built yet.");
        }

        reportList.sort(Comparator.comparing(ModKnowledgeReport::modId));
        GemmaBuddy.sendLine(player, "Installed mods: " + StateSnippet.joinLimited(
                reportList.stream().map(ModKnowledgeReport::modId).toList(), 12));
        GemmaBuddy.sendLine(player, "Reports ready: " + reportList.size() + " mods.");
        return ActionResult.success("Installed mods listed.");
    }

    public List<String> searchSnippets(String query, int limit) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return List.of();
        }

        LookupResult lookup = lookup(normalized);
        List<ScoredSnippet> scored = new ArrayList<>();
        for (ModKnowledgeReport report : allReports()) {
            for (String snippet : report.snippetsFor(normalized)) {
                int score = scoreSnippet(report, snippet, normalized);
                scored.add(new ScoredSnippet(score, report.modId(), snippet));
            }
        }

        if (lookup != null && !lookup.snippet().isBlank()) {
            scored.add(new ScoredSnippet(250, lookup.modId(), lookup.snippet()));
        }

        scored.sort(Comparator.comparingInt(ScoredSnippet::score).reversed()
                .thenComparing(ScoredSnippet::modId)
                .thenComparing(ScoredSnippet::snippet));

        List<String> result = new ArrayList<>();
        for (ScoredSnippet snippet : scored) {
            if (result.size() >= limit) {
                break;
            }
            result.add(snippet.modId() + ": " + snippet.snippet());
        }
        return result;
    }

    public String buildKnowledgeContext(String query) {
        List<String> snippets = searchSnippets(query, 8);
        if (snippets.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Local mod knowledge snippets:\n");
        for (String snippet : snippets) {
            builder.append("- ").append(snippet).append('\n');
        }
        return builder.toString().trim();
    }

    public List<String> buildKnowledgeAnswerLines(LookupResult lookup, String query, boolean practical) {
        if (lookup == null) {
            return List.of("I could not resolve that lookup.");
        }

        List<String> lines = new ArrayList<>();
        List<String> details = new ArrayList<>();
        String normalizedQuery = normalize(query);
        if (isFollowUpQuery(normalizedQuery)) {
            normalizedQuery = lookup.registryId();
        }
        String looseQuery = normalizeLoose(normalizedQuery);

        details.add("Registry: " + lookup.registryId());
        details.add("Namespace: " + (lookup.isVanilla() ? "Minecraft/vanilla" : lookup.modId()));
        details.add("Type: " + lookup.type());
        details.add(lookup.snippet());

        if (practical) {
            if (lookup.report() != null) {
                for (String snippet : lookup.report().snippetsFor(normalizedQuery)) {
                    if (!snippet.equals(lookup.report().summaryLine())) {
                        appendUnique(details, List.of(snippet), 6);
                    }
                }
                appendMatchingDetails(details, lookup.report().recipeIds(), looseQuery, "Recipe");
                appendMatchingDetails(details, lookup.report().tagIds(), looseQuery, "Tag");
                appendMatchingDetails(details, lookup.report().creativeTabHints(), looseQuery, "Creative tab");
                appendMatchingDetails(details, lookup.report().advancementIds(), looseQuery, "Advancement");
                appendUnique(details, List.of("Recipes: " + joinLimited(lookup.report().recipeIds(), 4)), 10);
                appendUnique(details, List.of("Tags: " + joinLimited(lookup.report().tagIds(), 4)), 11);
                appendUnique(details, List.of("Creative tabs: " + joinLimited(lookup.report().creativeTabHints(), 4)), 12);
            }

            appendUnique(details, vanillaHintsFor(lookup, normalizedQuery), 8);
            lines.add("Uses: " + joinLimited(details, 5));
            if (lookup.report() != null) {
                String recipeLine = summarizeRecipeHints(lookup, looseQuery);
                if (!recipeLine.isBlank()) {
                    lines.add(recipeLine);
                }
            }
        } else {
            if (lookup.report() != null) {
                details.add(lookup.report().summaryLine());
            }
            lines.add("Basic info: " + joinLimited(details, 4));
        }

        return lines;
    }

    public void reloadFromDisk() {
        loadFromDisk();
    }

    public LookupResult lookup(String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return null;
        }

        LookupResult namespaceCandidate = lookupNamespaceCandidate(normalized);
        if (namespaceCandidate != null) {
            logLookup(namespaceCandidate);
            return namespaceCandidate;
        }

        LookupResult exact = lookupExact(normalized);
        if (exact != null) {
            logLookup(exact);
            return exact;
        }

        LookupResult reportMatch = lookupInReports(normalized);
        if (reportMatch != null) {
            logLookup(reportMatch);
            return reportMatch;
        }

        LookupResult partial = lookupByPartialRegistryMatch(normalized);
        if (partial != null) {
            logLookup(partial);
            return partial;
        }

        LOGGER.debug("Lookup \"{}\" -> no match", query);
        return null;
    }

    public String describeLookup(LookupResult lookup) {
        if (lookup == null) {
            return "I could not resolve that lookup.";
        }

        String namespace = lookup.isVanilla() ? "Minecraft/vanilla" : lookup.modId();
        String type = lookup.type();
        String display = lookup.displayName();
        return lookup.registryId() + " is a " + type + " called \"" + display + "\" from " + namespace + ".";
    }

    private RebuildResult rebuild(MinecraftServer server, String fingerprint) {
        Map<String, ModKnowledgeReport> reportsByMod = new LinkedHashMap<>();
        int itemCount = 0;
        int blockCount = 0;
        int recipeCount = 0;

        for (var modInfo : ModList.get().getMods()) {
            String modId = modInfo.getModId();
            String displayName = modInfo.getDisplayName();
            String version = modInfo.getVersion().toString();

            List<String> items = registryEntries(BuiltInRegistries.ITEM.stream(), BuiltInRegistries.ITEM, modId);
            List<String> blocks = registryEntries(BuiltInRegistries.BLOCK.stream(), BuiltInRegistries.BLOCK, modId);
            List<String> entities = registryEntries(BuiltInRegistries.ENTITY_TYPE.stream(), BuiltInRegistries.ENTITY_TYPE,
                    modId);
            List<String> creativeTabs = registryEntries(BuiltInRegistries.CREATIVE_MODE_TAB.stream(),
                    BuiltInRegistries.CREATIVE_MODE_TAB, modId);
            List<String> recipes = scanRecipes(server, modId);
            List<String> tags = scanTags(server, modId);
            List<String> advancements = scanAdvancements(server, modId);
            List<String> keywords = buildKeywords(modId, displayName, items, blocks, entities, recipes, tags,
                    advancements);

            itemCount += items.size();
            blockCount += blocks.size();
            recipeCount += recipes.size();

            reportsByMod.put(modId, new ModKnowledgeReport(
                    modId,
                    displayName,
                    version,
                    items,
                    blocks,
                    entities,
                    recipes,
                    tags,
                    advancements,
                    creativeTabs,
                    keywords));
        }

        IndexManifest nextManifest = new IndexManifest(
                fingerprint,
                SharedConstants.getCurrentVersion().getName(),
                Instant.now().toString(),
                reportsByMod.size(),
                itemCount,
                blockCount,
                recipeCount);
        return new RebuildResult(nextManifest, reportsByMod);
    }

    private ModKnowledgeReport buildMinecraftReport() {
        List<String> items = registryEntries(BuiltInRegistries.ITEM.stream(), BuiltInRegistries.ITEM, "minecraft");
        List<String> blocks = registryEntries(BuiltInRegistries.BLOCK.stream(), BuiltInRegistries.BLOCK, "minecraft");
        List<String> entities = registryEntries(BuiltInRegistries.ENTITY_TYPE.stream(), BuiltInRegistries.ENTITY_TYPE,
                "minecraft");
        List<String> creativeTabs = registryEntries(BuiltInRegistries.CREATIVE_MODE_TAB.stream(),
                BuiltInRegistries.CREATIVE_MODE_TAB, "minecraft");
        List<String> keywords = buildKeywords("minecraft", "Minecraft / Vanilla", items, blocks, entities,
                List.of(), List.of(), List.of());
        return new ModKnowledgeReport(
                "minecraft",
                "Minecraft / Vanilla",
                SharedConstants.getCurrentVersion().getName(),
                items,
                blocks,
                entities,
                List.of(),
                List.of(),
                List.of(),
                creativeTabs,
                keywords);
    }

    private List<String> registryEntries(Stream<?> stream, Object registry, String modId) {
        List<String> entries = new ArrayList<>();
        for (Object value : stream.toList()) {
            ResourceLocation id = registryId(registry, value);
            if (id != null && modId.equals(id.getNamespace())) {
                entries.add(id.toString());
            }
        }
        entries.sort(String::compareTo);
        return entries;
    }

    private static ResourceLocation registryId(Object registry, Object value) {
        if (registry instanceof net.minecraft.core.Registry<?> typed) {
            @SuppressWarnings("unchecked")
            ResourceLocation id = ((net.minecraft.core.Registry<Object>) typed).getKey(value);
            return id;
        }
        return null;
    }

    private List<String> scanRecipes(MinecraftServer server, String modId) {
        List<String> results = new ArrayList<>();
        try {
            Collection<RecipeHolder<?>> recipes = server.getRecipeManager().getRecipes();
            for (RecipeHolder<?> holder : recipes) {
                ResourceLocation id = holder.id();
                boolean matches = id != null && modId.equals(id.getNamespace());
                if (!matches) {
                    matches = recipeInvolvesMod(holder.value(), modId, server);
                }
                if (matches && id != null) {
                    results.add(id.toString());
                }
            }
        } catch (Exception ex) {
            LOGGER.debug("GemmaBuddy recipe scan skipped for {}: {}", modId, ex.toString());
        }
        results.sort(String::compareTo);
        return results;
    }

    private boolean recipeInvolvesMod(Recipe<?> recipe, String modId, MinecraftServer server) {
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                for (var stack : ingredient.getItems()) {
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (itemId != null && modId.equals(itemId.getNamespace())) {
                        return true;
                    }
                }
            }

            var result = recipe.getResultItem(server.registryAccess());
            ResourceLocation resultId = BuiltInRegistries.ITEM.getKey(result.getItem());
            return resultId != null && modId.equals(resultId.getNamespace());
        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> scanTags(MinecraftServer server, String modId) {
        List<String> tags = new ArrayList<>();
        scanRegistryTags(BuiltInRegistries.ITEM, modId, tags);
        scanRegistryTags(BuiltInRegistries.BLOCK, modId, tags);
        scanRegistryTags(BuiltInRegistries.ENTITY_TYPE, modId, tags);
        tags.sort(String::compareTo);
        return tags;
    }

    private void scanRegistryTags(net.minecraft.core.Registry<?> registry, String modId, List<String> out) {
        try {
            for (Object tagKeyObj : registry.getTagNames().toList()) {
                @SuppressWarnings("unchecked")
                net.minecraft.tags.TagKey<Object> tagKey = (net.minecraft.tags.TagKey<Object>) tagKeyObj;
                ResourceLocation tagId = tagKey.location();
                if (tagId != null && modId.equals(tagId.getNamespace())) {
                    out.add(tagId.toString());
                }
            }
        } catch (Exception ex) {
            LOGGER.debug("GemmaBuddy tag scan skipped for {}: {}", modId, ex.toString());
        }
    }

    private List<String> scanAdvancements(MinecraftServer server, String modId) {
        List<String> advancements = new ArrayList<>();
        try {
            for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
                ResourceLocation id = holder.id();
                if (id != null && modId.equals(id.getNamespace())) {
                    advancements.add(id.toString());
                }
            }
        } catch (Exception ex) {
            LOGGER.debug("GemmaBuddy advancement scan skipped for {}: {}", modId, ex.toString());
        }
        advancements.sort(String::compareTo);
        return advancements;
    }

    private List<String> buildKeywords(String modId, String displayName, List<String> items, List<String> blocks,
            List<String> entities, List<String> recipes, List<String> tags, List<String> advancements) {
        List<String> keywords = new ArrayList<>();
        keywords.add(modId);
        keywords.add(displayName);
        addPrefixKeywords(keywords, items);
        addPrefixKeywords(keywords, blocks);
        addPrefixKeywords(keywords, entities);
        addPrefixKeywords(keywords, recipes);
        addPrefixKeywords(keywords, tags);
        addPrefixKeywords(keywords, advancements);
        return keywords.stream().distinct().toList();
    }

    private void addPrefixKeywords(List<String> keywords, List<String> values) {
        for (String value : values) {
            String shortName = value.contains(":") ? value.substring(value.indexOf(':') + 1) : value;
            if (!shortName.isBlank()) {
                keywords.add(shortName);
            }
        }
    }

    private List<ModKnowledgeReport> allReports() {
        List<ModKnowledgeReport> all = new ArrayList<>(reports.values());
        all.add(minecraftReport);
        return all;
    }

    private ModKnowledgeReport reportById(String modId) {
        if ("minecraft".equalsIgnoreCase(modId)) {
            return minecraftReport;
        }
        return reports.get(modId);
    }

    private void logLookup(LookupResult lookup) {
        LOGGER.info("Lookup \"{}\" -> {}, type={}, source={}", lookup.query(), lookup.registryId(), lookup.type(),
                lookup.source());
    }

    private LookupResult lookupExact(String normalized) {
        ResourceLocation parsed = tryParse(normalized);
        if (parsed != null) {
            LookupResult result = lookupRegistryById(parsed, "exact id");
            if (result != null) {
                return result;
            }
        }

        String compact = normalizeLoose(normalized);
        for (ResourceLocation id : allKnownIds()) {
            if (normalizeLoose(id.getPath()).equals(compact) || normalizeLoose(id.toString()).equals(compact)
                    || normalizeLoose(displayNameFor(id)).equals(compact)) {
                LookupResult result = lookupRegistryById(id, "normalized registry path");
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private LookupResult lookupInReports(String normalized) {
        for (ModKnowledgeReport report : allReports()) {
            if (report.matches(normalized)) {
                String snippet = firstNonBlank(report.snippetsFor(normalized));
                if (snippet.isBlank()) {
                    snippet = report.summaryLine();
                }
                return new LookupResult(normalized, report.modId(), report.modId(), "mod", report.displayName(), snippet,
                        report.modId().equals("minecraft") ? "built-in report" : "report", report);
            }
        }
        return null;
    }

    private LookupResult lookupByPartialRegistryMatch(String normalized) {
        String compact = normalizeLoose(normalized);
        for (ResourceLocation id : allKnownIds()) {
            String path = normalizeLoose(id.getPath());
            String namespaced = normalizeLoose(id.toString());
            String display = normalizeLoose(displayNameFor(id));
            if (path.contains(compact) || namespaced.contains(compact) || display.contains(compact)) {
                LookupResult result = lookupRegistryById(id, "partial registry match");
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private LookupResult lookupRegistryById(ResourceLocation id, String source) {
        if (id == null) {
            return null;
        }

        boolean hasBlock = BuiltInRegistries.BLOCK.containsKey(id);
        boolean hasItem = BuiltInRegistries.ITEM.containsKey(id);
        boolean hasEntity = BuiltInRegistries.ENTITY_TYPE.containsKey(id);

        if (hasBlock || hasItem) {
            String type = hasBlock && hasItem ? "block/item" : hasBlock ? "block" : "item";
            return new LookupResult(id.toString(), id.getNamespace(), id.toString(), type, displayNameFor(id),
                    buildRegistrySnippet(id, type, hasBlock, hasItem), source, reportById(id.getNamespace()));
        }
        if (hasEntity) {
            return new LookupResult(id.toString(), id.getNamespace(), id.toString(), "entity", displayNameFor(id),
                    buildRegistrySnippet(id, "entity", false, false), source, reportById(id.getNamespace()));
        }
        return null;
    }

    private LookupResult lookupNamespaceCandidate(String normalized) {
        String[] parts = normalized.split(" ");
        if (parts.length < 2) {
            return null;
        }

        String namespace = parts[0];
        if (!isKnownNamespace(namespace)) {
            return null;
        }

        String path = String.join("_", java.util.Arrays.copyOfRange(parts, 1, parts.length));
        ResourceLocation candidate = tryParse(namespace + ":" + path);
        if (candidate == null) {
            return null;
        }

        return lookupRegistryById(candidate, "namespace candidate");
    }

    private boolean isKnownNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return false;
        }

        String normalized = namespace.toLowerCase(Locale.ROOT);
        if ("minecraft".equals(normalized)) {
            return true;
        }

        return reports.containsKey(normalized);
    }

    private String buildRegistrySnippet(ResourceLocation id, String type, boolean hasBlock, boolean hasItem) {
        String display = displayNameFor(id);
        String sourceText = "minecraft".equals(id.getNamespace()) ? "vanilla Minecraft" : "the " + id.getNamespace()
                + " mod";
        String kind = type;
        if (hasBlock && hasItem) {
            kind = "block and item";
        } else if (hasBlock) {
            kind = "block";
        } else if (hasItem) {
            kind = "item";
        }
        return id + " is a " + kind + " named \"" + display + "\" from " + sourceText + ".";
    }

    private List<String> vanillaHintsFor(LookupResult lookup, String query) {
        String path = ResourceLocation.parse(lookup.registryId()).getPath();
        List<String> hints = new ArrayList<>();

        if (path.endsWith("_leaves")) {
            hints.add("vanilla leaf block");
            hints.add("decorative/building block");
            hints.add("can decay when not persistent and too far from matching logs");
            hints.add("can be collected with shears or Silk Touch");
            hints.add("can drop saplings and sticks");
            hints.add("in vanilla, leaves are compostable in a composter");
            return hints;
        }

        if (path.endsWith("_log") || path.endsWith("_wood")) {
            hints.add("vanilla wood/log block");
            hints.add("good for building and crafting planks");
            hints.add("can usually be stripped with an axe");
            hints.add("burns as furnace fuel");
            hints.add("works as a basic early-game building/crafting resource");
            return hints;
        }

        if ("minecraft:stone".equals(lookup.registryId()) || "minecraft:cobblestone".equals(lookup.registryId())) {
            hints.add("common building block");
            hints.add("useful for early-game crafting and mining progress");
            hints.add("often turned into slabs, stairs, buttons, or smelted forms");
        }

        if (query.contains("compost")) {
            hints.add("I do not have a local compost recipe entry for this target.");
        }

        return hints;
    }

    private String summarizeRecipeHints(LookupResult lookup, String query) {
        if (lookup.report() == null) {
            return "";
        }

        List<String> recipeMatches = new ArrayList<>();
        List<String> tagMatches = new ArrayList<>();
        String normalizedQuery = normalizeLoose(query);

        for (String recipeId : lookup.report().recipeIds()) {
            if (normalizeLoose(recipeId).contains(normalizedQuery)) {
                recipeMatches.add(recipeId);
            }
        }
        for (String tagId : lookup.report().tagIds()) {
            if (normalizeLoose(tagId).contains(normalizedQuery)) {
                tagMatches.add(tagId);
            }
        }

        List<String> parts = new ArrayList<>();
        if (!recipeMatches.isEmpty()) {
            parts.add("Recipes: " + joinLimited(recipeMatches, 4));
        }
        if (!tagMatches.isEmpty()) {
            parts.add("Tags: " + joinLimited(tagMatches, 4));
        }
        return String.join(" | ", parts);
    }

    private static void appendUnique(List<String> out, List<String> values, int limit) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!out.contains(value)) {
                out.add(value);
            }
            if (out.size() >= limit) {
                return;
            }
        }
    }

    private static void appendMatchingDetails(List<String> out, List<String> values, String query, String label) {
        if (values.isEmpty()) {
            return;
        }

        int added = 0;
        for (String value : values) {
            if (normalizeLoose(value).contains(query)) {
                String detail = label + ": " + value;
                if (!out.contains(detail)) {
                    out.add(detail);
                }
                added++;
                if (added >= 4) {
                    return;
                }
            }
        }
    }

    private static String joinLimited(List<String> values, int limit) {
        if (values.isEmpty()) {
            return "none";
        }

        int count = Math.min(limit, values.size());
        return String.join(", ", values.subList(0, count)) + (values.size() > limit ? ", ..." : "");
    }

    private List<ResourceLocation> allKnownIds() {
        List<ResourceLocation> ids = new ArrayList<>();
        BuiltInRegistries.BLOCK.keySet().forEach(ids::add);
        BuiltInRegistries.ITEM.keySet().forEach(ids::add);
        BuiltInRegistries.ENTITY_TYPE.keySet().forEach(ids::add);
        return ids;
    }

    private static ResourceLocation tryParse(String text) {
        try {
            return ResourceLocation.parse(text);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String normalizePath(String text) {
        return normalize(text).replace(' ', '_').replaceAll("[^a-z0-9_:/-]", "");
    }

    private static String displayNameFor(ResourceLocation id) {
        String path = id.getPath().replace('_', ' ');
        StringBuilder builder = new StringBuilder();
        boolean capitalizeNext = true;
        for (char ch : path.toCharArray()) {
            if (capitalizeNext && Character.isLetter(ch)) {
                builder.append(Character.toUpperCase(ch));
                capitalizeNext = false;
            } else {
                builder.append(ch);
                capitalizeNext = ch == ' ';
            }
        }
        return builder.toString();
    }

    private static String firstNonBlank(List<String> values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String fingerprint(MinecraftServer server) {
        StringBuilder builder = new StringBuilder();
        builder.append("mc=").append(SharedConstants.getCurrentVersion().getName()).append('\n');
        ModList.get().getMods().stream()
                .sorted(Comparator.comparing(mod -> mod.getModId().toLowerCase(Locale.ROOT)))
                .forEach(mod -> builder.append(mod.getModId()).append('=')
                        .append(mod.getVersion().toString()).append('\n'));
        builder.append("gemmabuddy=").append(
                ModList.get().getModContainerById(GemmaBuddy.MOD_ID)
                        .map(container -> container.getModInfo().getVersion().toString())
                        .orElse("unknown"));
        return Integer.toHexString(builder.toString().hashCode());
    }

    private void loadFromDisk() {
        try {
            Files.createDirectories(reportsRoot);
            if (Files.exists(manifestPath)) {
                String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
                JsonObject object = JsonParser.parseString(json).getAsJsonObject();
                manifest = IndexManifest.fromJson(object);
            } else {
                manifest = IndexManifest.empty();
            }

            Map<String, ModKnowledgeReport> loaded = new HashMap<>();
            if (Files.isDirectory(reportsRoot)) {
                try (var paths = Files.list(reportsRoot)) {
                    paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .forEach(path -> {
                                try {
                                    String json = Files.readString(path, StandardCharsets.UTF_8);
                                    JsonObject object = JsonParser.parseString(json).getAsJsonObject();
                                    ModKnowledgeReport report = ModKnowledgeReport.fromJson(object);
                                    loaded.put(report.modId(), report);
                                } catch (Exception ex) {
                                    LOGGER.warn("Failed to read GemmaBuddy knowledge file {}: {}", path, ex.toString());
                                }
                            });
                }
            }
            reports = loaded;
        } catch (IOException ex) {
            LOGGER.warn("Failed to load GemmaBuddy knowledge index", ex);
            manifest = IndexManifest.empty();
            reports = Map.of();
        }
    }

    private void saveToDisk(IndexManifest nextManifest, Map<String, ModKnowledgeReport> reportsByMod) throws IOException {
        Files.createDirectories(reportsRoot);
        for (ModKnowledgeReport report : reportsByMod.values()) {
            Path jsonPath = reportsRoot.resolve(report.modId() + ".json");
            Path mdPath = reportsRoot.resolve(report.modId() + ".md");
            Files.writeString(jsonPath, GSON.toJson(report.toJson()), StandardCharsets.UTF_8);
            Files.writeString(mdPath, String.join(System.lineSeparator(), report.markdownLines()), StandardCharsets.UTF_8);
        }
        Files.writeString(manifestPath, GSON.toJson(nextManifest.toJson()), StandardCharsets.UTF_8);
    }

    private int scoreSnippet(ModKnowledgeReport report, String snippet, String query) {
        int score = 0;
        String lower = snippet.toLowerCase(Locale.ROOT);
        if (report.modId().equalsIgnoreCase(query)) {
            score += 100;
        }
        if (lower.contains(query)) {
            score += 40;
        }
        if (report.displayName().toLowerCase(Locale.ROOT).contains(query)) {
            score += 25;
        }
        if (report.keywords().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(query))) {
            score += 15;
        }
        if (snippet.startsWith("item:") || snippet.startsWith("block:")) {
            score += 10;
        }
        return score;
    }

    public record LookupResult(
            String query,
            String modId,
            String registryId,
            String type,
            String displayName,
            String snippet,
            String source,
            ModKnowledgeReport report) {
        public boolean isVanilla() {
            return "minecraft".equalsIgnoreCase(modId);
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizeLoose(String text) {
        return normalize(text).replaceAll("[^a-z0-9]+", "");
    }

    private record ScoredSnippet(int score, String modId, String snippet) {
    }

    private record RebuildResult(IndexManifest manifest, Map<String, ModKnowledgeReport> reports) {
    }

    private record IndexManifest(String fingerprint, String minecraftVersion, String generatedAt, int modCount,
            int itemCount, int blockCount, int recipeCount) {
        static IndexManifest empty() {
            return new IndexManifest("", "", "", 0, 0, 0, 0);
        }

        boolean matches(String nextFingerprint) {
            return !fingerprint.isBlank() && Objects.equals(fingerprint, nextFingerprint);
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("fingerprint", fingerprint);
            object.addProperty("minecraftVersion", minecraftVersion);
            object.addProperty("generatedAt", generatedAt);
            object.addProperty("modCount", modCount);
            object.addProperty("itemCount", itemCount);
            object.addProperty("blockCount", blockCount);
            object.addProperty("recipeCount", recipeCount);
            return object;
        }

        static IndexManifest fromJson(JsonObject object) {
            return new IndexManifest(
                    readString(object, "fingerprint"),
                    readString(object, "minecraftVersion"),
                    readString(object, "generatedAt"),
                    readInt(object, "modCount"),
                    readInt(object, "itemCount"),
                    readInt(object, "blockCount"),
                    readInt(object, "recipeCount"));
        }

        private static String readString(JsonObject object, String key) {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                return "";
            }
            return object.get(key).getAsString();
        }

        private static int readInt(JsonObject object, String key) {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                return 0;
            }
            JsonElement element = object.get(key);
            return element.getAsInt();
        }
    }

    private static final class StateSnippet {
        private StateSnippet() {
        }

        static String joinLimited(List<String> values, int limit) {
            if (values.isEmpty()) {
                return "none";
            }
            int count = Math.min(limit, values.size());
            return String.join(", ", values.subList(0, count)) + (values.size() > limit ? ", ..." : "");
        }
    }
}
