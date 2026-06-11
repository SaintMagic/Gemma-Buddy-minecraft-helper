package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.EntityType;
import net.neoforged.fml.ModList;

/**
 * In-memory local knowledge dataverse built from registries, recipes, tags, and
 * GemmaBuddy's indexed mod reports.
 */
public final class KnowledgeDataverse implements KnowledgeRepository {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final KnowledgeIndex knowledgeIndex;
    private final Path docsRoot;
    private volatile DataverseSnapshot snapshot;
    private volatile String snapshotKey = "";

    public KnowledgeDataverse(KnowledgeIndex knowledgeIndex) {
        this.knowledgeIndex = knowledgeIndex;
        this.docsRoot = Path.of(knowledgeIndex.knowledgeRootPath()).resolve("docs");
    }

    @Override
    public Optional<KnowledgeEntry> resolveEntry(MinecraftServer server, String query) {
        DataverseSnapshot data = ensureSnapshot(server);
        return Optional.ofNullable(resolveEntryInternal(data, query));
    }

    @Override
    public Optional<RecipeRecord> findRecipeForOutput(MinecraftServer server, String queryOrRegistryId) {
        DataverseSnapshot data = ensureSnapshot(server);
        KnowledgeEntry entry = resolveEntryInternal(data, queryOrRegistryId);
        if (entry == null) {
            return Optional.empty();
        }
        List<RecipeRecord> recipes = data.recipesByOutput.getOrDefault(entry.registryId(), List.of());
        if (recipes.isEmpty()) {
            return Optional.empty();
        }
        return recipes.stream().sorted(Comparator.comparing(RecipeRecord::priority).reversed()).findFirst();
    }

    @Override
    public List<RecipeRecord> findUsagesForInput(MinecraftServer server, String queryOrRegistryId) {
        DataverseSnapshot data = ensureSnapshot(server);
        KnowledgeEntry entry = resolveEntryInternal(data, queryOrRegistryId);
        if (entry == null) {
            return List.of();
        }
        return data.recipesByInput.getOrDefault(entry.registryId(), List.of());
    }

    @Override
    public List<String> findTagsForEntry(MinecraftServer server, String queryOrRegistryId) {
        DataverseSnapshot data = ensureSnapshot(server);
        KnowledgeEntry entry = resolveEntryInternal(data, queryOrRegistryId);
        if (entry == null) {
            return List.of();
        }
        return data.tagsByEntry.getOrDefault(entry.registryId(), List.of());
    }

    @Override
    public List<KnowledgeEntry> findEntriesByMod(MinecraftServer server, String modId) {
        DataverseSnapshot data = ensureSnapshot(server);
        String normalized = normalize(modId);
        List<String> ids = data.entriesByMod.getOrDefault(normalized, List.of());
        List<KnowledgeEntry> result = new ArrayList<>();
        for (String id : ids) {
            KnowledgeEntry entry = data.entriesById.get(id);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    @Override
    public String getEntrySummary(MinecraftServer server, String queryOrRegistryId) {
        DataverseSnapshot data = ensureSnapshot(server);
        KnowledgeEntry entry = resolveEntryInternal(data, queryOrRegistryId);
        return entry == null ? "" : entrySummaryLine(entry);
    }

    @Override
    public Optional<DeterministicAnswer> answerQuestion(MinecraftServer server, String query) {
        DataverseSnapshot data = ensureSnapshot(server);
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        Intent intent = detectIntent(normalized);
        String targetQuery = extractTargetQuery(normalized, intent);
        if (targetQuery.isBlank() && intent != Intent.USAGE_FOLLOW_UP) {
            return Optional.empty();
        }

        KnowledgeEntry entry = resolveEntryInternal(data, targetQuery);
        if (entry == null && intent == Intent.USAGE_FOLLOW_UP) {
            entry = resolveEntryInternal(data, knowledgeIndex.lastResolvedKnowledgeTarget());
        }
        if (entry == null) {
            return Optional.empty();
        }

        knowledgeIndex.rememberResolvedTarget(entry.registryId());
        DocumentationCard doc = buildDocumentationCard(data, entry);
        writeDocumentationCard(doc);

        return switch (intent) {
            case RECIPE -> buildRecipeAnswer(data, entry, doc);
            case USAGE, PRACTICAL_LOOKUP, USAGE_FOLLOW_UP -> buildUsageAnswer(data, entry, doc);
            case MOD_ORIGIN -> Optional.of(new DeterministicAnswer(
                    "mod_origin",
                    List.of(entry.displayName() + " comes from " + entry.modName() + " (" + entry.modId() + ")."),
                    entry.registryId()));
            case LOOKUP -> Optional.of(new DeterministicAnswer(
                    "lookup",
                    buildLookupLines(entry, doc),
                    entry.registryId()));
        };
    }

    @Override
    public String docsRootPath() {
        return docsRoot.toAbsolutePath().toString();
    }

    @Override
    public String statusLine() {
        DataverseSnapshot current = snapshot;
        if (current == null) {
            return "Dataverse not built yet.";
        }
        return "Dataverse ready. Entries=" + current.entriesById.size()
                + ", recipe outputs=" + current.recipesByOutput.size()
                + ", input usages=" + current.recipesByInput.size() + ".";
    }

    private synchronized DataverseSnapshot ensureSnapshot(MinecraftServer server) {
        String nextKey = buildSnapshotKey(server);
        if (snapshot != null && nextKey.equals(snapshotKey)) {
            return snapshot;
        }

        DataverseSnapshot rebuilt = buildSnapshot(server);
        snapshot = rebuilt;
        snapshotKey = nextKey;
        LOGGER.info("GemmaBuddy dataverse rebuilt: entries={}, recipeOutputs={}, tags={}, docsRoot={}",
                rebuilt.entriesById.size(), rebuilt.recipesByOutput.size(), rebuilt.tagsByEntry.size(),
                docsRoot.toAbsolutePath());
        return rebuilt;
    }

    private String buildSnapshotKey(MinecraftServer server) {
        int recipeCount = server.getRecipeManager().getRecipes().size();
        int reportCount = knowledgeIndex.reportSnapshot().size();
        return knowledgeIndex.statusLine() + "|" + recipeCount + "|" + reportCount;
    }

    private DataverseSnapshot buildSnapshot(MinecraftServer server) {
        Map<String, MutableEntry> mutableEntries = new LinkedHashMap<>();
        Map<String, Set<String>> aliasesToEntries = new LinkedHashMap<>();
        Map<String, List<String>> entriesByMod = new LinkedHashMap<>();
        Map<String, List<String>> tagsByEntry = new LinkedHashMap<>();
        Map<String, List<RecipeRecord>> recipesByOutput = new LinkedHashMap<>();
        Map<String, List<RecipeRecord>> recipesByInput = new LinkedHashMap<>();
        Map<String, String> modDisplayNames = buildModDisplayNames();

        collectItems(mutableEntries, aliasesToEntries, entriesByMod, modDisplayNames);
        collectBlocks(mutableEntries, aliasesToEntries, entriesByMod, modDisplayNames);
        collectEntities(mutableEntries, aliasesToEntries, entriesByMod, modDisplayNames);
        collectTagMembership(BuiltInRegistries.ITEM, tagsByEntry);
        collectTagMembership(BuiltInRegistries.BLOCK, tagsByEntry);

        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            RecipeRecord record = toRecipeRecord(holder, server);
            if (record == null) {
                continue;
            }
            recipesByOutput.computeIfAbsent(record.outputId(), key -> new ArrayList<>()).add(record);
            for (String inputId : record.inputIds()) {
                recipesByInput.computeIfAbsent(inputId, key -> new ArrayList<>()).add(record);
            }
        }

        Map<String, KnowledgeEntry> entriesById = new LinkedHashMap<>();
        for (MutableEntry mutable : mutableEntries.values()) {
            List<String> tags = sortStrings(tagsByEntry.getOrDefault(mutable.registryId, List.of()));
            KnowledgeEntry entry = mutable.freeze(tags);
            entriesById.put(entry.registryId(), entry);
            addAlias(aliasesToEntries, entry.registryId(), entry.registryId());
            addAlias(aliasesToEntries, entry.registryId(), entry.displayName());
            addAlias(aliasesToEntries, entry.registryId(), entry.modId());
        }

        addManualAliases(aliasesToEntries, entriesById);

        recipesByOutput.replaceAll((key, value) -> value.stream()
                .sorted(Comparator.comparing(RecipeRecord::priority).reversed().thenComparing(RecipeRecord::recipeId))
                .toList());
        recipesByInput.replaceAll((key, value) -> value.stream()
                .sorted(Comparator.comparing(RecipeRecord::outputName))
                .toList());
        entriesByMod.replaceAll((key, value) -> sortStrings(value));

        return new DataverseSnapshot(entriesById, freezeAliases(aliasesToEntries), recipesByOutput, recipesByInput,
                freezeLists(tagsByEntry), freezeLists(entriesByMod), modDisplayNames);
    }

    private Map<String, String> buildModDisplayNames() {
        Map<String, String> displayNames = new LinkedHashMap<>();
        displayNames.put("minecraft", "Minecraft / Vanilla");
        for (ModKnowledgeReport report : knowledgeIndex.allReportsSnapshot()) {
            displayNames.put(normalize(report.modId()), report.displayName());
        }
        for (var modInfo : ModList.get().getMods()) {
            displayNames.putIfAbsent(normalize(modInfo.getModId()), modInfo.getDisplayName());
        }
        return displayNames;
    }

    private void collectItems(Map<String, MutableEntry> entries, Map<String, Set<String>> aliasesToEntries,
            Map<String, List<String>> entriesByMod, Map<String, String> modDisplayNames) {
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                continue;
            }
            String displayName = new ItemStack(item).getHoverName().getString();
            MutableEntry entry = entries.computeIfAbsent(id.toString(), key -> new MutableEntry(id.toString(),
                    displayName, id.getNamespace(), modDisplayNames.getOrDefault(id.getNamespace(), id.getNamespace())));
            entry.item = true;
            addCommonAliases(aliasesToEntries, entry.registryId, id, displayName);
            entriesByMod.computeIfAbsent(normalize(id.getNamespace()), key -> new ArrayList<>()).add(entry.registryId);
        }
    }

    private void collectBlocks(Map<String, MutableEntry> entries, Map<String, Set<String>> aliasesToEntries,
            Map<String, List<String>> entriesByMod, Map<String, String> modDisplayNames) {
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) {
                continue;
            }
            String displayName = itemDisplayName(block);
            MutableEntry entry = entries.computeIfAbsent(id.toString(), key -> new MutableEntry(id.toString(),
                    displayName, id.getNamespace(), modDisplayNames.getOrDefault(id.getNamespace(), id.getNamespace())));
            entry.block = true;
            if (entry.displayName.isBlank()) {
                entry.displayName = displayName;
            }
            addCommonAliases(aliasesToEntries, entry.registryId, id, displayName);
            entriesByMod.computeIfAbsent(normalize(id.getNamespace()), key -> new ArrayList<>()).add(entry.registryId);
        }
    }

    private void collectEntities(Map<String, MutableEntry> entries, Map<String, Set<String>> aliasesToEntries,
            Map<String, List<String>> entriesByMod, Map<String, String> modDisplayNames) {
        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
            if (id == null) {
                continue;
            }
            String displayName = entityType.getDescription().getString();
            MutableEntry entry = entries.computeIfAbsent(id.toString(), key -> new MutableEntry(id.toString(),
                    displayName, id.getNamespace(), modDisplayNames.getOrDefault(id.getNamespace(), id.getNamespace())));
            entry.entity = true;
            addCommonAliases(aliasesToEntries, entry.registryId, id, displayName);
            entriesByMod.computeIfAbsent(normalize(id.getNamespace()), key -> new ArrayList<>()).add(entry.registryId);
        }
    }

    private <T> void collectTagMembership(Registry<T> registry, Map<String, List<String>> tagsByEntry) {
        registry.getTags().forEach(named -> {
            String tagId = named.getFirst().location().toString();
            named.getSecond().stream().forEach(holder -> {
                ResourceLocation entryId = registry.getKey(holder.value());
                if (entryId != null) {
                    tagsByEntry.computeIfAbsent(entryId.toString(), key -> new ArrayList<>()).add(tagId);
                }
            });
        });
    }

    private RecipeRecord toRecipeRecord(RecipeHolder<?> holder, MinecraftServer server) {
        Recipe<?> recipe = holder.value();
        ItemStack outputStack = recipe.getResultItem(server.registryAccess());
        if (outputStack.isEmpty()) {
            return null;
        }

        ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(outputStack.getItem());
        if (outputId == null) {
            return null;
        }

        List<Ingredient> rawIngredients = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient != null && !ingredient.isEmpty()) {
                rawIngredients.add(ingredient);
            }
        }
        if (rawIngredients.isEmpty()) {
            return null;
        }

        List<IngredientCount> ingredients = groupIngredients(rawIngredients);
        Set<String> inputIds = new LinkedHashSet<>();
        for (Ingredient ingredient : rawIngredients) {
            for (ItemStack stack : ingredient.getItems()) {
                ResourceLocation inputId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (inputId != null) {
                    inputIds.add(inputId.toString());
                }
            }
        }

        int width = 0;
        int height = 0;
        String kind = "recipe";
        String layoutSummary = "";
        int priority = 1;
        if (recipe instanceof ShapedRecipe shaped) {
            width = shaped.getWidth();
            height = shaped.getHeight();
            kind = "shaped crafting";
            layoutSummary = buildLayoutSummary(shaped);
            priority = 5;
        } else if (recipe instanceof ShapelessRecipe) {
            kind = "shapeless crafting";
            priority = 4;
        } else {
            kind = recipe.getType().toString();
        }

        return new RecipeRecord(
                holder.id().toString(),
                outputId.toString(),
                outputStack.getHoverName().getString(),
                outputStack.getCount(),
                kind,
                List.copyOf(ingredients),
                List.copyOf(inputIds),
                width,
                height,
                layoutSummary,
                priority);
    }

    private List<IngredientCount> groupIngredients(List<Ingredient> rawIngredients) {
        Map<String, MutableIngredientCount> grouped = new LinkedHashMap<>();
        for (Ingredient ingredient : rawIngredients) {
            List<ItemStack> options = List.of(ingredient.getItems());
            if (options.isEmpty()) {
                continue;
            }
            List<String> ids = options.stream()
                    .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .filter(id -> id != null)
                    .map(ResourceLocation::toString)
                    .distinct()
                    .sorted()
                    .toList();
            if (ids.isEmpty()) {
                continue;
            }
            String key = String.join("|", ids);
            String label = ingredientLabel(options);
            MutableIngredientCount current = grouped.get(key);
            if (current == null) {
                grouped.put(key, new MutableIngredientCount(label, ids, 1));
            } else {
                current.count++;
            }
        }

        List<IngredientCount> result = new ArrayList<>();
        for (Map.Entry<String, MutableIngredientCount> entry : grouped.entrySet()) {
            MutableIngredientCount value = entry.getValue();
            result.add(new IngredientCount(entry.getKey(), value.label, List.copyOf(value.itemIds), value.count));
        }
        result.sort(Comparator.comparing(IngredientCount::label));
        return result;
    }

    private String buildLayoutSummary(ShapedRecipe recipe) {
        int width = recipe.getWidth();
        int height = recipe.getHeight();
        List<Ingredient> ingredients = recipe.getIngredients();
        Map<String, IngredientCount> byKey = groupIngredients(ingredients).stream()
                .collect(Collectors.toMap(IngredientCount::key, value -> value, (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, List<String>> positionsByKey = new LinkedHashMap<>();

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                if (index >= ingredients.size()) {
                    continue;
                }
                Ingredient ingredient = ingredients.get(index);
                if (ingredient == null || ingredient.isEmpty()) {
                    continue;
                }
                List<String> ids = List.of(ingredient.getItems()).stream()
                        .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        .filter(id -> id != null)
                        .map(ResourceLocation::toString)
                        .distinct()
                        .sorted()
                        .toList();
                if (ids.isEmpty()) {
                    continue;
                }
                String key = String.join("|", ids);
                positionsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cellName(width, height, row, col));
            }
        }

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : positionsByKey.entrySet()) {
            IngredientCount count = byKey.get(entry.getKey());
            if (count == null) {
                continue;
            }
            parts.add(toSentenceCase(count.label()) + " " + describePositions(entry.getValue()));
        }
        return String.join("; ", parts);
    }

    private String cellName(int width, int height, int row, int col) {
        if (width == 3 && height == 3) {
            String rowName = switch (row) {
                case 0 -> "top";
                case 1 -> "middle";
                default -> "bottom";
            };
            String colName = switch (col) {
                case 0 -> "left";
                case 1 -> "middle";
                default -> "right";
            };
            if (row == 1 && col == 1) {
                return "center";
            }
            return rowName + "-" + colName;
        }
        return "row " + (row + 1) + " col " + (col + 1);
    }

    private String describePositions(List<String> positions) {
        List<String> sorted = new ArrayList<>(positions);
        sorted.sort(String::compareTo);
        if (sorted.equals(List.of("bottom-left", "bottom-middle", "bottom-right"))) {
            return "across the full bottom row";
        }
        if (sorted.equals(List.of("bottom-left", "bottom-middle", "bottom-right", "center"))) {
            return "center and across the full bottom row";
        }
        if (sorted.equals(List.of("middle-left", "middle-middle", "middle-right"))) {
            return "across the full middle row";
        }
        if (sorted.equals(List.of("top-left", "top-middle", "top-right"))) {
            return "across the full top row";
        }
        if (sorted.equals(List.of("middle-left", "middle-right"))) {
            return "left and right of center";
        }
        if (sorted.equals(List.of("top-middle", "bottom-middle"))) {
            return "above and below center";
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        return joinWithAnd(sorted);
    }

    private KnowledgeEntry resolveEntryInternal(DataverseSnapshot data, String query) {
        String normalized = normalize(query);
        if (normalized.isBlank()) {
            return null;
        }

        String canonical = normalizeKey(correctTypos(normalized));
        Set<String> directMatches = data.aliasesToEntries.get(canonical);
        if (directMatches != null && !directMatches.isEmpty()) {
            return bestEntryForMatches(data, directMatches, normalized);
        }

        List<String> tokenMatches = new ArrayList<>();
        List<String> queryTokens = tokenList(canonical);
        for (Map.Entry<String, Set<String>> alias : data.aliasesToEntries.entrySet()) {
            if (alias.getKey().contains(canonical) || allTokensPresent(alias.getKey(), queryTokens)) {
                tokenMatches.addAll(alias.getValue());
            }
        }
        if (!tokenMatches.isEmpty()) {
            return bestEntryForMatches(data, new LinkedHashSet<>(tokenMatches), normalized);
        }

        String fuzzyKey = fuzzyAliasKey(data.aliasesToEntries.keySet(), canonical);
        if (!fuzzyKey.isBlank()) {
            return bestEntryForMatches(data, data.aliasesToEntries.getOrDefault(fuzzyKey, Set.of()), normalized);
        }

        KnowledgeIndex.LookupResult fallback = knowledgeIndex.resolveKnowledgeTarget(normalized);
        if (fallback != null) {
            return data.entriesById.get(fallback.registryId());
        }
        return null;
    }

    private KnowledgeEntry bestEntryForMatches(DataverseSnapshot data, Collection<String> matches, String query) {
        return matches.stream()
                .map(data.entriesById::get)
                .filter(entry -> entry != null)
                .sorted(Comparator
                        .comparingInt((KnowledgeEntry entry) -> matchScore(entry, query))
                        .reversed()
                        .thenComparing(KnowledgeEntry::registryId))
                .findFirst()
                .orElse(null);
    }

    private int matchScore(KnowledgeEntry entry, String query) {
        String normalized = normalizeKey(query);
        int score = 0;
        if (normalizeKey(entry.registryId()).equals(normalized)) {
            score += 200;
        }
        if (normalizeKey(entry.registryIdPath()).equals(normalized)) {
            score += 180;
        }
        if (normalizeKey(entry.displayName()).equals(normalized)) {
            score += 170;
        }
        if (normalizeKey(entry.registryId()).contains(normalized)) {
            score += 40;
        }
        if (normalizeKey(entry.displayName()).contains(normalized)) {
            score += 35;
        }
        if (entry.block() || entry.item()) {
            score += 15;
        }
        if (entry.isVanilla()) {
            score += 5;
        }
        return score;
    }

    private Optional<DeterministicAnswer> buildRecipeAnswer(DataverseSnapshot data, KnowledgeEntry entry,
            DocumentationCard doc) {
        RecipeRecord recipe = doc.recipe();
        if (recipe == null) {
            return Optional.empty();
        }

        List<String> lines = new ArrayList<>();
        String ingredients = joinIngredientCounts(recipe.ingredients());
        String prefix = recipe.outputName() + " recipe: " + ingredients + ".";
        if (!recipe.layoutSummary().isBlank()) {
            prefix += " Layout: " + recipe.layoutSummary() + ".";
        }
        lines.add(prefix);
        return Optional.of(new DeterministicAnswer("recipe", lines, entry.registryId()));
    }

    private Optional<DeterministicAnswer> buildUsageAnswer(DataverseSnapshot data, KnowledgeEntry entry,
            DocumentationCard doc) {
        List<String> usages = doc.usageOutputs();
        List<String> lines = new ArrayList<>();
        List<String> hints = vanillaHintsFor(entry);

        if (!usages.isEmpty()) {
            lines.add(entry.displayName() + " is used for " + joinWithAnd(limit(usages, 6)) + ".");
        } else if (!hints.isEmpty()) {
            lines.add(entry.displayName() + ": " + joinWithAnd(limit(hints, 3)) + ".");
        } else {
            lines.add(entrySummaryLine(entry));
        }

        if (doc.recipe() != null) {
            lines.add("Recipe: " + formatRecipeSummary(doc.recipe()) + ".");
        }
        if (!doc.tags().isEmpty()) {
            lines.add("Tags: " + joinWithAnd(limit(doc.tags(), 4)) + ".");
        }
        if (!hints.isEmpty() && usages.isEmpty()) {
            lines.add("Notes: " + joinWithAnd(limit(hints, 5)) + ".");
        }
        return Optional.of(new DeterministicAnswer("usage", lines, entry.registryId()));
    }

    private List<String> buildLookupLines(KnowledgeEntry entry, DocumentationCard doc) {
        List<String> lines = new ArrayList<>();
        lines.add(entrySummaryLine(entry));
        if (doc.recipe() != null) {
            lines.add("Recipe: " + formatRecipeSummary(doc.recipe()) + ".");
        }
        if (!doc.usageOutputs().isEmpty()) {
            lines.add("Used for: " + joinWithAnd(limit(doc.usageOutputs(), 6)) + ".");
        }
        if (!doc.tags().isEmpty()) {
            lines.add("Tags: " + joinWithAnd(limit(doc.tags(), 4)) + ".");
        }
        List<String> hints = vanillaHintsFor(entry);
        if (!hints.isEmpty()) {
            lines.add("Notes: " + joinWithAnd(limit(hints, 4)) + ".");
        }
        return lines;
    }

    private DocumentationCard buildDocumentationCard(DataverseSnapshot data, KnowledgeEntry entry) {
        List<RecipeRecord> outputRecipes = data.recipesByOutput.getOrDefault(entry.registryId(), List.of());
        List<RecipeRecord> usages = data.recipesByInput.getOrDefault(entry.registryId(), List.of());
        List<String> usageOutputs = usages.stream()
                .map(RecipeRecord::outputName)
                .distinct()
                .sorted()
                .toList();
        List<String> relatedEntries = new ArrayList<>(usageOutputs);
        outputRecipes.stream().findFirst().ifPresent(recipe -> {
            for (IngredientCount ingredient : recipe.ingredients()) {
                relatedEntries.add(ingredient.label());
            }
        });
        return new DocumentationCard(
                entry,
                outputRecipes.stream().findFirst().orElse(null),
                usageOutputs,
                data.tagsByEntry.getOrDefault(entry.registryId(), List.of()),
                relatedEntries.stream().distinct().sorted().toList(),
                data);
    }

    private void writeDocumentationCard(DocumentationCard doc) {
        try {
            Files.createDirectories(docsRoot.resolve(doc.entry().modId()));
            Path target = docsRoot.resolve(doc.entry().modId()).resolve(doc.entry().registryIdPath() + ".md");
            List<String> lines = new ArrayList<>();
            lines.add("# " + doc.entry().displayName());
            lines.add("");
            lines.add("- Registry ID: " + doc.entry().registryId());
            lines.add("- Type: " + doc.entry().typeSummary());
            lines.add("- Mod: " + doc.entry().modName() + " (" + doc.entry().modId() + ")");
            if (doc.recipe() != null) {
                lines.add("- Recipe: " + formatRecipeSummary(doc.recipe()));
            }
            if (!doc.usageOutputs().isEmpty()) {
                lines.add("- Used for: " + joinWithAnd(limit(doc.usageOutputs(), 8)));
            }
            if (!doc.tags().isEmpty()) {
                lines.add("- Tags: " + joinWithAnd(limit(doc.tags(), 8)));
            }
            if (!doc.relatedEntries().isEmpty()) {
                lines.add("- Related: " + joinWithAnd(limit(doc.relatedEntries(), 8)));
            }
            List<String> hints = vanillaHintsFor(doc.entry());
            if (!hints.isEmpty()) {
                lines.add("- Notes: " + joinWithAnd(limit(hints, 6)));
            }
            lines.add("- Evidence: registries, recipes, tags, local mod reports");
            Files.write(target, lines, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("Failed to write GemmaBuddy documentation card for {}: {}", doc.entry().registryId(),
                    ex.toString());
        }
    }

    private Intent detectIntent(String query) {
        String normalized = normalize(query);
        if (normalized.startsWith("how do i craft ")
                || normalized.startsWith("how do i make ")
                || normalized.startsWith("recipe for ")) {
            return Intent.RECIPE;
        }
        if (normalized.startsWith("what is ") && normalized.endsWith(" used for")) {
            return Intent.USAGE;
        }
        if (normalized.startsWith("what can i do with ")
                || normalized.startsWith("what can i craft with ")
                || normalized.startsWith("what can i make with ")
                || normalized.startsWith("how do i use ")
                || normalized.equals("what does it do")
                || normalized.equals("what can i do with it")
                || normalized.equals("what can i do with this")
                || normalized.equals("what is it for")
                || normalized.equals("what is this for")
                || normalized.equals("can i craft with it")) {
            return normalized.contains(" it") || normalized.contains(" this") ? Intent.USAGE_FOLLOW_UP : Intent.USAGE;
        }
        if (normalized.startsWith("which mod adds ")
                || normalized.startsWith("what mod added ")
                || normalized.startsWith("where is ") && normalized.endsWith(" from")) {
            return Intent.MOD_ORIGIN;
        }
        if (normalized.startsWith("what does ")
                || normalized.startsWith("what is ")
                || normalized.startsWith("what are ")) {
            return Intent.PRACTICAL_LOOKUP;
        }
        return Intent.LOOKUP;
    }

    private String extractTargetQuery(String query, Intent intent) {
        String normalized = normalize(query);
        return switch (intent) {
            case RECIPE -> stripPrefix(normalized, "how do i craft ", "how do i make ", "recipe for ");
            case USAGE -> {
                if (normalized.startsWith("what is ") && normalized.endsWith(" used for")) {
                    yield normalized.substring("what is ".length(), normalized.length() - " used for".length()).trim();
                }
                yield stripPrefix(normalized, "what can i do with ", "what can i craft with ",
                        "what can i make with ", "how do i use ");
            }
            case MOD_ORIGIN -> {
                if (normalized.startsWith("where is ") && normalized.endsWith(" from")) {
                    yield normalized.substring("where is ".length(), normalized.length() - " from".length()).trim();
                }
                yield stripPrefix(normalized, "which mod adds ", "what mod added ");
            }
            case PRACTICAL_LOOKUP -> {
                if (normalized.startsWith("what does ")) {
                    yield stripTrailingDo(normalized.substring("what does ".length()).trim());
                }
                if (normalized.startsWith("what is ")) {
                    yield stripTrailingFor(normalized.substring("what is ".length()).trim());
                }
                if (normalized.startsWith("what are ")) {
                    yield stripTrailingFor(normalized.substring("what are ".length()).trim());
                }
                yield normalized;
            }
            case USAGE_FOLLOW_UP -> knowledgeIndex.lastResolvedKnowledgeTarget();
            case LOOKUP -> normalized;
        };
    }

    private String stripPrefix(String query, String... prefixes) {
        for (String prefix : prefixes) {
            if (query.startsWith(prefix)) {
                return query.substring(prefix.length()).trim();
            }
        }
        return query;
    }

    private void addCommonAliases(Map<String, Set<String>> aliasesToEntries, String registryId, ResourceLocation id,
            String displayName) {
        addAlias(aliasesToEntries, registryId, id.toString());
        addAlias(aliasesToEntries, registryId, id.getPath());
        addAlias(aliasesToEntries, registryId, id.getNamespace() + " " + id.getPath().replace('_', ' '));
        addAlias(aliasesToEntries, registryId, displayName);
        addAlias(aliasesToEntries, registryId, displayName.replace('-', ' '));
    }

    private void addManualAliases(Map<String, Set<String>> aliasesToEntries, Map<String, KnowledgeEntry> entriesById) {
        addManualAlias(aliasesToEntries, entriesById, "minecraft:enchanting_table", "enchantment table",
                "enchant table", "enchantement table");
        addManualAlias(aliasesToEntries, entriesById, "minecraft:spruce_leaves", "spruce leaves");
        addManualAlias(aliasesToEntries, entriesById, "minecraft:oak_log", "oak log");
    }

    private void addManualAlias(Map<String, Set<String>> aliasesToEntries, Map<String, KnowledgeEntry> entriesById,
            String registryId, String... aliases) {
        if (!entriesById.containsKey(registryId)) {
            return;
        }
        for (String alias : aliases) {
            addAlias(aliasesToEntries, registryId, alias);
        }
    }

    private void addAlias(Map<String, Set<String>> aliasesToEntries, String registryId, String alias) {
        String key = normalizeKey(alias);
        if (key.isBlank()) {
            return;
        }
        aliasesToEntries.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(registryId);
    }

    private Map<String, Set<String>> freezeAliases(Map<String, Set<String>> aliases) {
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : aliases.entrySet()) {
            frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return frozen;
    }

    private Map<String, List<String>> freezeLists(Map<String, List<String>> values) {
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            frozen.put(entry.getKey(), sortStrings(entry.getValue()));
        }
        return frozen;
    }

    private String itemDisplayName(Block block) {
        Item item = block.asItem();
        if (item != null && item != net.minecraft.world.item.Items.AIR) {
            return new ItemStack(item).getHoverName().getString();
        }
        return block.getName().getString();
    }

    private String ingredientLabel(List<ItemStack> options) {
        if (options.isEmpty()) {
            return "unknown ingredient";
        }
        if (options.size() == 1) {
            return options.get(0).getHoverName().getString().toLowerCase(Locale.ROOT);
        }
        List<String> names = options.stream()
                .map(stack -> stack.getHoverName().getString().toLowerCase(Locale.ROOT))
                .distinct()
                .limit(4)
                .toList();
        return String.join(" / ", names);
    }

    private String joinIngredientCounts(List<IngredientCount> ingredients) {
        List<String> parts = ingredients.stream()
                .map(this::formatIngredientCount)
                .toList();
        return joinWithAnd(parts);
    }

    private String formatIngredientCount(IngredientCount ingredient) {
        return ingredient.count() + " " + ingredient.label();
    }

    private String formatRecipeSummary(RecipeRecord recipe) {
        String text = joinIngredientCounts(recipe.ingredients());
        if (!recipe.layoutSummary().isBlank()) {
            text += ". Layout: " + recipe.layoutSummary();
        }
        return text;
    }

    private String entrySummaryLine(KnowledgeEntry entry) {
        return entry.displayName() + " is " + articleFor(entry.typeSummary()) + " " + entry.typeSummary()
                + " from " + entry.modName() + " (" + entry.registryId() + ").";
    }

    private List<String> vanillaHintsFor(KnowledgeEntry entry) {
        String path = entry.registryIdPath();
        List<String> hints = new ArrayList<>();
        if (!entry.isVanilla()) {
            return hints;
        }
        if (path.endsWith("_leaves")) {
            hints.add("decorative/building block");
            hints.add("can decay when not persistent and too far from logs");
            hints.add("collect with shears or Silk Touch");
            hints.add("may drop saplings and sticks");
            hints.add("compostable in a composter");
        } else if (path.endsWith("_log") || path.endsWith("_wood")) {
            hints.add("basic building and crafting wood");
            hints.add("turns into planks");
            hints.add("can be stripped with an axe");
            hints.add("burns as furnace fuel");
        } else if ("enchanting_table".equals(path)) {
            hints.add("used to enchant gear with lapis and experience");
        }
        return hints;
    }

    private String fuzzyAliasKey(Set<String> aliasKeys, String query) {
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        for (String alias : aliasKeys) {
            if (Math.abs(alias.length() - query.length()) > 3) {
                continue;
            }
            int distance = editDistance(alias, query);
            if (distance < bestDistance && distance <= 2) {
                best = alias;
                bestDistance = distance;
            }
        }
        return best;
    }

    private int editDistance(String left, String right) {
        int[][] dp = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= right.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[left.length()][right.length()];
    }

    private boolean allTokensPresent(String aliasKey, List<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return false;
        }
        for (String token : queryTokens) {
            if (!aliasKey.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private List<String> tokenList(String text) {
        return List.of(normalize(text).split(" ")).stream()
                .map(KnowledgeDataverse::normalizeKey)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private List<String> limit(List<String> values, int max) {
        return values.size() <= max ? values : values.subList(0, max);
    }

    private String joinWithAnd(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        if (values.size() == 2) {
            return values.get(0) + " and " + values.get(1);
        }
        return String.join(", ", values.subList(0, values.size() - 1)) + ", and " + values.get(values.size() - 1);
    }

    private String articleFor(String value) {
        if (value == null || value.isBlank()) {
            return "a";
        }
        char first = Character.toLowerCase(value.charAt(0));
        return "aeiou".indexOf(first) >= 0 ? "an" : "a";
    }

    private String correctTypos(String text) {
        return normalize(text)
                .replace("enchantement", "enchantment")
                .replace("enchanting table", "enchantment table")
                .replace("spruceleaf", "spruce leaves");
    }

    private String stripTrailingFor(String text) {
        String cleaned = normalize(text);
        if (cleaned.endsWith(" used for")) {
            cleaned = cleaned.substring(0, cleaned.length() - " used for".length()).trim();
        }
        if (cleaned.endsWith(" for")) {
            cleaned = cleaned.substring(0, cleaned.length() - " for".length()).trim();
        }
        return cleaned;
    }

    private String stripTrailingDo(String text) {
        String cleaned = normalize(text);
        if (cleaned.endsWith(" do")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return cleaned;
    }

    private String toSentenceCase(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private List<String> sortStrings(Collection<String> values) {
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().sorted().toList();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizeKey(String text) {
        return normalize(text)
                .replace(':', ' ')
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("[^a-z0-9 ]+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private enum Intent {
        LOOKUP,
        PRACTICAL_LOOKUP,
        RECIPE,
        USAGE,
        USAGE_FOLLOW_UP,
        MOD_ORIGIN
    }

    private static final class MutableEntry {
        private final String registryId;
        private String displayName;
        private final String modId;
        private final String modName;
        private boolean item;
        private boolean block;
        private boolean entity;

        private MutableEntry(String registryId, String displayName, String modId, String modName) {
            this.registryId = registryId;
            this.displayName = displayName == null ? "" : displayName;
            this.modId = modId;
            this.modName = modName;
        }

        private KnowledgeEntry freeze(List<String> tags) {
            return new KnowledgeEntry(
                    registryId,
                    registryId.substring(registryId.indexOf(':') + 1),
                    displayName.isBlank() ? registryId : displayName,
                    modId,
                    modName,
                    item,
                    block,
                    entity,
                    tags);
        }
    }

    private static final class MutableIngredientCount {
        private final String label;
        private final List<String> itemIds;
        private int count;

        private MutableIngredientCount(String label, List<String> itemIds, int count) {
            this.label = label;
            this.itemIds = itemIds;
            this.count = count;
        }
    }

    private record DataverseSnapshot(
            Map<String, KnowledgeEntry> entriesById,
            Map<String, Set<String>> aliasesToEntries,
            Map<String, List<RecipeRecord>> recipesByOutput,
            Map<String, List<RecipeRecord>> recipesByInput,
            Map<String, List<String>> tagsByEntry,
            Map<String, List<String>> entriesByMod,
            Map<String, String> modDisplayNames) {
    }

    private record DocumentationCard(
            KnowledgeEntry entry,
            RecipeRecord recipe,
            List<String> usageOutputs,
            List<String> tags,
            List<String> relatedEntries,
            DataverseSnapshot snapshot) {
    }

    public record KnowledgeEntry(
            String registryId,
            String registryIdPath,
            String displayName,
            String modId,
            String modName,
            boolean item,
            boolean block,
            boolean entity,
            List<String> tags) {
        public boolean isVanilla() {
            return "minecraft".equalsIgnoreCase(modId);
        }

        public String typeSummary() {
            if (item && block) {
                return "block and item";
            }
            if (block) {
                return "block";
            }
            if (item) {
                return "item";
            }
            if (entity) {
                return "entity";
            }
            return "entry";
        }
    }

    public record IngredientCount(String key, String label, List<String> itemIds, int count) {
    }

    public record RecipeRecord(
            String recipeId,
            String outputId,
            String outputName,
            int outputCount,
            String kind,
            List<IngredientCount> ingredients,
            List<String> inputIds,
            int width,
            int height,
            String layoutSummary,
            int priority) {
    }

    public record DeterministicAnswer(String kind, List<String> lines, String resolvedEntryId) {
    }
}
