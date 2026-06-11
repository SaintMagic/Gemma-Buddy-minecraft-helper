package com.saintmagic.gemmabuddy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.saintmagic.gemmabuddy.LmStudioClient.LmStudioResponse;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registry of the actions GemmaBuddy knows about.
 *
 * This now owns the executable handlers, while the router only resolves and
 * dispatches input. That keeps slash commands, chat aliases, and UI buttons on
 * the same path.
 */
public final class ActionRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PLANNING_MAX_TOKENS = 2048;
    private static final int QUICK_MAX_TOKENS = 128;

    public static final String BASIC = "Basic";
    public static final String KNOWLEDGE = "Knowledge / Mods";
    public static final String BUDDY = "Buddy / Entity";
    public static final String PLANNING = "AI / Planning";
    public static final String DEBUG = "Debug / Maintenance";

    private final Map<String, ActionDefinition> actionsById = new LinkedHashMap<>();
    private final Map<String, ActionDefinition> actionsByAlias = new LinkedHashMap<>();
    private final Map<String, List<ActionDefinition>> actionsByCategory = new LinkedHashMap<>();

    public ActionRegistry() {
        register(action("status", BASIC, "Status",
                "Show health, food, position, dimension, nearby entities, nearby blocks, and inventory.",
                false, false, InputMode.NONE, null, List.of("status"), List.of("status"),
                this::statusAction));

        register(action("inventory", BASIC, "Inventory",
                "Show the compact inventory summary and item registry IDs.",
                false, false, InputMode.NONE, null, List.of("inventory"), List.of("inventory"),
                this::inventoryAction));

        register(action("see", BASIC, "What do you see?",
                "Show nearby useful blocks, nearby entities, and any nearby danger.",
                false, false, InputMode.NONE, null, List.of("see", "what do you see", "what do you see?"),
                List.of("see"), this::seeAction));

        register(action("ask", BASIC, "Ask Gemma",
                "Send a custom question to LM Studio using the current game state.",
                true, true, InputMode.MAIN_INPUT, "question", List.of("ask"), List.of("ask <message>"),
                this::askAction));

        register(action("study_mods", KNOWLEDGE, "Study installed mods",
                "Scan the loaded mods and write local knowledge reports.",
                true, false, InputMode.NONE, null, List.of("study mods"),
                List.of("study mods"), this::studyModsAction));

        register(action("list_mods", KNOWLEDGE, "What mods do we have",
                "List the installed mods already captured in the knowledge index.",
                false, false, InputMode.NONE, null, List.of("what mods do we have"),
                List.of(), this::listModsAction));

        register(action("knowledge_status", KNOWLEDGE, "Knowledge status",
                "Show whether the knowledge index is ready and where it is stored.",
                false, false, InputMode.NONE, null, List.of("knowledge status"),
                List.of("knowledge status"), this::knowledgeStatusAction));

        register(action("knowledge_rebuild", KNOWLEDGE, "Rebuild knowledge index",
                "Force a full rebuild of the local mod knowledge reports.",
                true, false, InputMode.NONE, null, List.of("knowledge rebuild"),
                List.of("knowledge rebuild"), this::knowledgeRebuildAction));

        register(action("mod_report", KNOWLEDGE, "Open/select mod report",
                "Show the generated report for a specific mod id.",
                false, true, InputMode.TARGET_INPUT, "mod id", List.of("modreport"),
                List.of("modreport <modid>"), this::modReportAction));

        register(action("what_does", KNOWLEDGE, "Lookup item/block/mod",
                "Look up a modded item, block, or mod and explain it using local snippets.",
                false, true, InputMode.TARGET_INPUT, "item / block / mod",
                List.of(
                        "what does",
                        "what does it do",
                        "what can i do with",
                        "what can i do with it",
                        "what can i do with this",
                        "what can it do",
                        "what is",
                        "what is it for",
                        "what is this for",
                        "what are",
                        "how do i use",
                        "how do i use it",
                        "how do i use this",
                        "what can i craft with",
                        "what can i make with",
                        "can i craft with it",
                        "which mod adds",
                        "what mod added this",
                        "what mod added it",
                        "what mod added that",
                        "how do i decay them into compost"),
                List.of("lookup <query>"), this::knowledgeLookupAction));

        register(action("recipe_lookup", KNOWLEDGE, "Recipe for target",
                "Show the exact local recipe for a target when recipe data is available.",
                false, true, InputMode.TARGET_INPUT, "item / block / output",
                List.of("how do i craft", "how do i make", "recipe for"),
                List.of("recipe <query>"), this::recipeLookupAction));

        register(action("usage_lookup", KNOWLEDGE, "Uses for target",
                "Show what recipes or uses the target appears in.",
                false, true, InputMode.TARGET_INPUT, "item / block / input",
                List.of("uses for"),
                List.of("uses <query>"), this::usageLookupAction));

        register(action("spawn", BUDDY, "Spawn buddy",
                "Spawn the GemmaBuddy companion near the player.",
                false, false, InputMode.NONE, null, List.of("spawn"), List.of("spawn"), this::spawnAction));

        register(action("despawn", BUDDY, "Despawn buddy",
                "Remove the spawned GemmaBuddy companion.",
                false, false, InputMode.NONE, null, List.of("despawn"), List.of("despawn"), this::despawnAction));

        register(action("where", BUDDY, "Where is buddy?",
                "Report whether GemmaBuddy is spawned and where it is.",
                false, false, InputMode.NONE, null, List.of("where", "where are you", "where are you?"),
                List.of("where"), this::whereAction));

        register(action("plan", PLANNING, "What should we do next?",
                "Ask GemmaBuddy for a plan using the current game state.",
                true, true, InputMode.MAIN_INPUT, "question",
                List.of("what do we do", "what should we do", "what should we do next", "next", "plan"),
                List.of(), this::planAction));

        register(action("lmstudio_test", DEBUG, "LM Studio test",
                "Send a tiny request to the LM Studio endpoint and print the response.",
                false, false, InputMode.NONE, null, List.of("lmstudio test"), List.of("lmstudio test"),
                this::lmStudioTestAction));

        register(action("show_config_path", DEBUG, "Show config path",
                "Print the mod config folder path.",
                false, false, InputMode.NONE, null, List.of("show config path"), List.of("show config path"),
                this::showConfigPathAction));

        register(action("show_knowledge_path", DEBUG, "Show knowledge folder path",
                "Print the knowledge index folder path.",
                false, false, InputMode.NONE, null, List.of("show knowledge folder path"),
                List.of("show knowledge folder path"), this::showKnowledgePathAction));

        register(action("reload_config", DEBUG, "Reload config",
                "Reload local GemmaBuddy caches and report whether the knowledge index is fresh.",
                false, false, InputMode.NONE, null, List.of("reload config"), List.of("reload config"),
                this::reloadConfigAction));
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

    public Optional<ResolvedAction> resolveChatAction(String rawInput) {
        String normalized = normalize(rawInput);
        String canonical = canonicalize(normalized);
        if (canonical.isBlank()) {
            return Optional.empty();
        }

        if (canonical.startsWith("action ")) {
            String remainder = normalized.substring("action ".length()).trim();
            if (remainder.isBlank()) {
                return Optional.empty();
            }
            String actionId;
            String argument = "";
            int split = remainder.indexOf(' ');
            if (split >= 0) {
                actionId = remainder.substring(0, split).trim();
                argument = remainder.substring(split + 1).trim();
            } else {
                actionId = remainder;
            }
            ActionDefinition definition = actionsById.get(normalize(actionId));
            if (definition == null) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedAction(definition, argument, "action"));
        }

        ResolvedAction best = null;
        int bestLength = -1;
        for (ActionDefinition definition : actionsById.values()) {
            for (String alias : definition.aliases()) {
                String normalizedAlias = canonicalize(alias);
                if (canonical.equals(normalizedAlias)) {
                    int length = normalizedAlias.length();
                    if (length > bestLength) {
                        best = new ResolvedAction(definition, "", alias);
                        bestLength = length;
                    }
                    continue;
                }

                if (canonical.startsWith(normalizedAlias + " ")) {
                    int length = normalizedAlias.length();
                    if (length > bestLength) {
                        String argument = normalized.substring(normalizedAlias.length()).trim();
                        best = new ResolvedAction(definition, argument, alias);
                        bestLength = length;
                    }
                }
            }
        }

        return Optional.ofNullable(best);
    }

    public ActionResult execute(ActionContext context, String actionId) throws Exception {
        ActionDefinition definition = actionsById.get(normalize(actionId));
        if (definition == null) {
            return ActionResult.failure("Unknown action: " + actionId);
        }
        LOGGER.info("Executing action id='{}' label='{}' argument='{}'", definition.id(), definition.label(),
                context.argument());
        return definition.handler().execute(context);
    }

    public List<String> actionSummaryLines() {
        List<String> lines = new ArrayList<>();
        for (String category : categories()) {
            List<String> labels = actionsForCategory(category).stream().map(ActionDefinition::label).toList();
            lines.add(category + ": " + String.join(", ", labels));
        }
        return lines;
    }

    private void register(ActionDefinition definition) {
        actionsById.put(definition.id(), definition);
        actionsByCategory.computeIfAbsent(definition.category(), key -> new ArrayList<>()).add(definition);
        for (String alias : definition.aliases()) {
            actionsByAlias.put(normalize(alias), definition);
        }
    }

    private ActionDefinition action(String id, String category, String label, String description, boolean longRunning,
            boolean requiresInput, InputMode inputMode, String inputPlaceholder, List<String> aliases,
            List<String> slashPaths,
            ActionHandler handler) {
        return new ActionDefinition(id, category, label, description, longRunning, requiresInput, inputMode,
                inputPlaceholder, aliases, slashPaths, handler);
    }

    private ActionResult statusAction(ActionContext context) {
        for (String line : context.snapshot().statusLines()) {
            GemmaBuddy.sendLine(context.player(), line);
        }
        return ActionResult.success("Status shown.");
    }

    private ActionResult inventoryAction(ActionContext context) {
        StateSnapshot snapshot = context.snapshot();
        GemmaBuddy.sendLine(context.player(), "Inventory: " + snapshot.formatInventorySummary());
        GemmaBuddy.sendLine(context.player(), "Items: " + StateSnapshot.joinLimited(snapshot.inventoryItems(), 10,
                StateSnapshot::formatItem));
        return ActionResult.success("Inventory shown.");
    }

    private ActionResult seeAction(ActionContext context) {
        for (String line : context.snapshot().seeLines()) {
            GemmaBuddy.sendLine(context.player(), line);
        }
        return ActionResult.success("Nearby scan shown.");
    }

    private ActionResult askAction(ActionContext context) {
        String query = firstNonBlank(context.argument(), context.normalizedInput());
        return runPlanning(context, query, "ask");
    }

    private ActionResult planAction(ActionContext context) {
        String query = firstNonBlank(context.argument(), context.normalizedInput());
        return runPlanning(context, query, "plan");
    }

    private ActionResult studyModsAction(ActionContext context) {
        return context.knowledge().studyMods(context.player(), false);
    }

    private ActionResult listModsAction(ActionContext context) {
        return context.knowledge().whatModsDoWeHave(context.player());
    }

    private ActionResult knowledgeStatusAction(ActionContext context) {
        ActionResult result = context.knowledge().knowledgeStatus(context.player());
        GemmaBuddy.sendLine(context.player(), context.repository().statusLine());
        GemmaBuddy.sendLine(context.player(), "Docs folder: " + context.repository().docsRootPath());
        return result;
    }

    private ActionResult knowledgeRebuildAction(ActionContext context) {
        return context.knowledge().knowledgeRebuild(context.player());
    }

    private ActionResult modReportAction(ActionContext context) {
        String modId = normalize(context.argument());
        if (modId.isBlank()) {
            GemmaBuddy.sendLine(context.player(), "Use: gemma modreport <modid>");
            return ActionResult.failure("Missing mod id.");
        }
        return context.knowledge().modReport(context.player(), modId);
    }

    private ActionResult knowledgeLookupAction(ActionContext context) throws Exception {
        String query = resolveKnowledgeQuery(context);
        if (query.isBlank()) {
            GemmaBuddy.sendLine(context.player(), "Use: gemma what does <item/block/mod> do");
            return ActionResult.failure("Missing query.");
        }

        String deterministicQuery = context.argument().isBlank() ? context.normalizedInput() : query;
        ActionResult deterministic = tryDeterministicKnowledgeAnswer(context, deterministicQuery);
        if (deterministic != null) {
            return deterministic;
        }

        LOGGER.info("Knowledge query original='{}' normalized='{}' target='{}' action='{}'", context.rawInput(),
                context.normalizedInput(), query, context.actionId());

        KnowledgeIndex.LookupResult lookup = context.knowledge().resolveKnowledgeTarget(query);
        if (lookup == null) {
            String failureMessage = context.knowledge().isFollowUpQuery(query)
                    ? "I do not have a previous target yet. Ask about an item or block first."
                    : "I could not resolve that target. Try a block, item, or mod id.";
            GemmaBuddy.sendError(context.player(), failureMessage);
            LOGGER.info("Knowledge lookup failed original='{}' normalized='{}' target='{}' action='{}'",
                    context.rawInput(), context.normalizedInput(), query, context.actionId());
            return ActionResult.failure("No lookup result.");
        }

        context.knowledge().rememberResolvedTarget(lookup.registryId());
        GemmaBuddy.sendLine(context.player(), context.knowledge().describeLookup(lookup));

        boolean practical = isPracticalKnowledgeQuestion(context.normalizedInput());
        List<String> lines = context.knowledge().buildKnowledgeAnswerLines(lookup, query, practical);
        LOGGER.info("Knowledge lookup resolved original='{}' normalized='{}' target='{}' action='{}' resolved='{}' lines={}",
                context.rawInput(), context.normalizedInput(), query, context.actionId(), lookup.registryId(),
                String.join(" | ", lines));

        for (String line : lines) {
            GemmaBuddy.sendLine(context.player(), line);
        }

        return ActionResult.success("Knowledge lookup shown.");
    }

    private ActionResult recipeLookupAction(ActionContext context) {
        String query = firstNonBlank(context.argument(), context.normalizedInput());
        if (normalize(query).isBlank()) {
            GemmaBuddy.sendLine(context.player(), "Use: gemma recipe for <item/block>");
            return ActionResult.failure("Missing recipe target.");
        }
        String routed = ensureRecipeIntent(query);
        ActionResult deterministic = tryDeterministicKnowledgeAnswer(context, routed);
        if (deterministic != null) {
            return deterministic;
        }
        GemmaBuddy.sendError(context.player(), "I could not find an exact local recipe for that target yet.");
        return ActionResult.failure("No deterministic recipe found.");
    }

    private ActionResult usageLookupAction(ActionContext context) {
        String query = firstNonBlank(context.argument(), context.normalizedInput());
        if (normalize(query).isBlank()) {
            GemmaBuddy.sendLine(context.player(), "Use: gemma uses for <item/block>");
            return ActionResult.failure("Missing usage target.");
        }
        String routed = ensureUsageIntent(query);
        ActionResult deterministic = tryDeterministicKnowledgeAnswer(context, routed);
        if (deterministic != null) {
            return deterministic;
        }
        GemmaBuddy.sendError(context.player(), "I could not find local usage data for that target yet.");
        return ActionResult.failure("No deterministic usage found.");
    }

    private String resolveKnowledgeQuery(ActionContext context) {
        String argument = normalize(context.argument());
        if (!argument.isBlank()) {
            return argument;
        }

        String raw = normalize(context.normalizedInput());
        if (raw.equals("what does")
                || raw.equals("what can i do with")
                || raw.equals("what is")
                || raw.equals("what are")
                || raw.equals("how do i use")
                || raw.equals("what can i craft with")
                || raw.equals("what can i make with")) {
            return "";
        }
        if (raw.startsWith("what does ")) {
            return stripTrailingDo(raw.substring("what does ".length()).trim());
        }
        if (raw.startsWith("what can i do with ")) {
            return raw.substring("what can i do with ".length()).trim();
        }
        if (raw.startsWith("what can i craft with ")) {
            return raw.substring("what can i craft with ".length()).trim();
        }
        if (raw.startsWith("what can i make with ")) {
            return raw.substring("what can i make with ".length()).trim();
        }
        if (raw.startsWith("how do i use ")) {
            return raw.substring("how do i use ".length()).trim();
        }
        if (raw.startsWith("what is ")) {
            return stripTrailingFor(raw.substring("what is ".length()).trim());
        }
        if (raw.startsWith("what are ")) {
            return stripTrailingFor(raw.substring("what are ".length()).trim());
        }
        if (raw.startsWith("what is it for") || raw.startsWith("what is this for")
                || raw.startsWith("what does it do")
                || raw.startsWith("what can i do with it")
                || raw.startsWith("what can i do with this")
                || raw.startsWith("what can it do")
                || raw.startsWith("can i craft with it")
                || raw.startsWith("how do i decay them into compost")) {
            return raw;
        }
        return raw;
    }

    private boolean isPracticalKnowledgeQuestion(String query) {
        String lower = canonicalize(query);
        return lower.startsWith("what does ")
                || lower.equals("what does")
                || lower.startsWith("what can i do with ")
                || lower.equals("what can i do with")
                || lower.startsWith("what is it for")
                || lower.startsWith("what is this for")
                || lower.startsWith("what can i craft with ")
                || lower.startsWith("what can i make with ")
                || lower.startsWith("how do i use ")
                || lower.equals("how do i decay them into compost")
                || lower.equals("how do i use")
                || lower.equals("what does it do")
                || lower.equals("what can i do with it")
                || lower.equals("what can i do with this")
                || lower.equals("what can it do")
                || lower.equals("can i craft with it");
    }

    private ActionResult spawnAction(ActionContext context) {
        GemmaBuddy.spawnBuddy(context.player());
        return ActionResult.success("Buddy spawn requested.");
    }

    private ActionResult despawnAction(ActionContext context) {
        GemmaBuddy.despawnBuddy(context.player());
        return ActionResult.success("Buddy despawn requested.");
    }

    private ActionResult whereAction(ActionContext context) {
        GemmaBuddy.reportBuddyLocation(context.player());
        return ActionResult.success("Buddy location shown.");
    }

    private ActionResult lmStudioTestAction(ActionContext context) {
        try {
            LmStudioResponse response = context.llm().complete(
                    "You are GemmaBuddy. Reply with one short Minecraft chat sentence.",
                    "Say hello in one short Minecraft chat sentence.",
                    QUICK_MAX_TOKENS);
            String reply = cleanReply(response.content());
            if (reply.isBlank() && !normalize(response.reasoningContent()).isBlank()) {
                reply = requestFinalAnswerFromReasoning(context, response.reasoningContent());
            }
            if (reply.isBlank()) {
                reply = "LM Studio replied with nothing.";
            }
            GemmaBuddy.sendLine(context.player(), reply);
            return ActionResult.success(reply);
        } catch (Exception ex) {
            LOGGER.error("GemmaBuddy LM Studio test failed", ex);
            GemmaBuddy.sendError(context.player(), "LM Studio test failed: " + friendlyError(ex)
                    + ". Check that LM Studio is running.");
            return ActionResult.failure("LM Studio test failed.");
        }
    }

    private ActionResult showConfigPathAction(ActionContext context) {
        GemmaBuddy.sendLine(context.player(), "Config folder: " + context.knowledge().configRootPath());
        return ActionResult.success("Config path shown.");
    }

    private ActionResult showKnowledgePathAction(ActionContext context) {
        GemmaBuddy.sendLine(context.player(), "Knowledge folder: " + context.knowledge().knowledgeRootPath());
        GemmaBuddy.sendLine(context.player(), "Docs folder: " + context.repository().docsRootPath());
        return ActionResult.success("Knowledge path shown.");
    }

    private ActionResult reloadConfigAction(ActionContext context) {
        GemmaBuddy.reloadConfig();
        context.knowledge().reloadFromDisk();
        GemmaBuddy.sendLine(context.player(), "GemmaBuddy caches reloaded.");
        GemmaBuddy.sendLine(context.player(),
                "Voice control is " + (GemmaBuddy.config().enableVoiceControl() ? "enabled" : "disabled") + ".");
        GemmaBuddy.sendLine(context.player(), context.knowledge().statusLine());
        GemmaBuddy.sendLine(context.player(), context.repository().statusLine());
        return ActionResult.success("Config reloaded.");
    }

    private ActionResult runPlanning(ActionContext context, String query, String mode) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank() || "ask".equals(mode) && normalizedQuery.equals("ask")) {
            GemmaBuddy.sendLine(context.player(), "Use: gemma ask <message>");
            return ActionResult.failure("Missing question.");
        }

        ActionResult deterministic = tryDeterministicKnowledgeAnswer(context, normalizedQuery);
        if (deterministic != null) {
            return deterministic;
        }

        StateSnapshot snapshot = context.snapshot();
        String knowledgeContext = context.knowledge().buildKnowledgeContext(normalizedQuery);
        context.goals().setGoal("Planning", List.of("Read the current state", "Answer in one short line"), true);
        context.goals().updateProgress("Asking LM Studio");
        GemmaBuddy.sendLine(context.player(), "Gemma is thinking...");

        MinecraftServer server = context.player().getServer();
        CompletableFuture.supplyAsync(() -> {
            try {
                return completePlanningReply(context, snapshot, knowledgeContext, normalizedQuery);
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }).whenComplete((reply, error) -> {
            Runnable deliver = () -> {
                if (error != null) {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause()
                            : error;
                    LOGGER.error("GemmaBuddy planning request failed for '{}'", normalizedQuery, cause);
                    context.goals().clear();
                    GemmaBuddy.sendError(context.player(), "LM Studio had trouble answering: " + friendlyError(cause)
                            + ". Check the log for details.");
                    return;
                }

                GemmaBuddy.sendLine(context.player(), reply);
                context.goals().markComplete(reply);
            };

            if (server != null) {
                server.execute(deliver);
            } else {
                deliver.run();
            }
        });

        return ActionResult.success("Planning request started.");
    }

    private ActionResult tryDeterministicKnowledgeAnswer(ActionContext context, String query) {
        MinecraftServer server = context.player().getServer();
        if (server == null) {
            return null;
        }

        String contextTarget = ContextResolver.resolveTarget(context.player(), context.knowledge(), query);
        String effectiveQuery = contextTarget.isBlank() ? query : contextTarget;
        Optional<KnowledgeDataverse.DeterministicAnswer> answer = context.repository().answerQuestion(server,
                effectiveQuery);
        if (answer.isEmpty()) {
            return null;
        }

        KnowledgeDataverse.DeterministicAnswer resolved = answer.get();
        context.knowledge().rememberResolvedTarget(resolved.resolvedEntryId());
        for (String line : resolved.lines()) {
            GemmaBuddy.sendLine(context.player(), line);
        }
        LOGGER.info("GemmaBuddy deterministic answer kind='{}' query='{}' resolved='{}' lines={}",
                resolved.kind(), query, resolved.resolvedEntryId(), String.join(" | ", resolved.lines()));
        return ActionResult.success("Deterministic knowledge answer shown.");
    }

    private String completePlanningReply(ActionContext context, StateSnapshot snapshot, String knowledgeContext,
            String normalizedQuery) throws Exception {
        String systemPrompt = """
                You are GemmaBuddy, a Minecraft play-buddy.
                You may think briefly if needed, but you must always provide a final answer in message.content.
                The final answer must be one short Minecraft in-game chat sentence.
                Do not include markdown, bullet points, code fences, or raw reasoning in the final answer.
                """;

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Game state:\n").append(snapshot.compactSummary()).append('\n');
        if (!knowledgeContext.isBlank()) {
            userPrompt.append("\nRelevant local mod knowledge:\n").append(knowledgeContext).append('\n');
        }
        userPrompt.append("\nPlayer message:\n").append(normalizedQuery).append('\n');

        LmStudioResponse response = context.llm().complete(systemPrompt, userPrompt.toString(), PLANNING_MAX_TOKENS);
        String reply = cleanReply(response.content());
        if (reply.isBlank() && !normalize(response.reasoningContent()).isBlank()) {
            reply = requestFinalAnswerFromReasoning(context, response.reasoningContent());
        }
        if (reply.isBlank()) {
            return "I thought too long and forgot to answer. Ask me again shorter.";
        }
        return reply;
    }

    private String requestFinalAnswerFromReasoning(ActionContext context, String reasoningContent) throws Exception {
        String summarizationSystemPrompt = """
                You are converting model reasoning into a final Minecraft chat reply.
                Give only the final one-sentence Minecraft chat reply.
                Do not include markdown, bullets, code fences, or reasoning.
                """;

        String summarizationUserPrompt = "Give only the final one-sentence Minecraft chat reply based on this reasoning:\n"
                + normalize(reasoningContent);

        LmStudioResponse response = context.llm().complete(summarizationSystemPrompt, summarizationUserPrompt,
                QUICK_MAX_TOKENS);
        return cleanReply(response.content());
    }

    private String stripTrailingFor(String text) {
        String cleaned = normalize(text);
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" used for")) {
            cleaned = cleaned.substring(0, cleaned.length() - " used for".length()).trim();
            lower = cleaned.toLowerCase(Locale.ROOT);
        }
        if (lower.endsWith(" for")) {
            cleaned = cleaned.substring(0, cleaned.length() - " for".length()).trim();
        }
        return cleaned;
    }

    private String stripTrailingDo(String text) {
        String cleaned = normalize(text);
        if (canonicalize(cleaned).endsWith(" do")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return cleaned;
    }

    private String ensureRecipeIntent(String text) {
        String cleaned = normalize(text);
        if (cleaned.startsWith("how do i craft ") || cleaned.startsWith("how do i make ")
                || cleaned.startsWith("recipe for ")) {
            return cleaned;
        }
        return "recipe for " + cleaned;
    }

    private String ensureUsageIntent(String text) {
        String cleaned = normalize(text);
        if (cleaned.startsWith("what is ") && cleaned.endsWith(" used for")) {
            return cleaned;
        }
        if (cleaned.startsWith("what can i do with ") || cleaned.startsWith("how do i use ")) {
            return cleaned;
        }
        return "what is " + cleaned + " used for";
    }

    private String friendlyError(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String cleaned = message.trim().replaceAll("\\s+", " ");
        return cleaned.length() > 120 ? cleaned.substring(0, 117) + "..." : cleaned;
    }

    private String cleanReply(String reply) {
        String text = normalize(reply);
        if (text.isBlank()) {
            return "";
        }
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline).trim();
        }
        return text;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private static String canonicalize(String text) {
        String normalized = normalize(text).toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\p{Punct}]+$", "");
    }

    public interface ActionHandler {
        ActionResult execute(ActionContext context) throws Exception;
    }

    public enum InputMode {
        NONE,
        MAIN_INPUT,
        TARGET_INPUT
    }

    public record ActionDefinition(
            String id,
            String category,
            String label,
            String description,
            boolean longRunning,
            boolean requiresInput,
            InputMode inputMode,
            String inputPlaceholder,
            List<String> aliases,
            List<String> slashPaths,
            ActionHandler handler) {
        public ActionDefinition {
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
            slashPaths = List.copyOf(slashPaths == null ? List.of() : slashPaths);
        }
    }

    public record ResolvedAction(ActionDefinition definition, String argument, String matchedAlias) {
    }
}
