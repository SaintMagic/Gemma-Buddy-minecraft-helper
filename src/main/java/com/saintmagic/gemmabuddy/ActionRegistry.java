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
    public static final String FIND = "Find";
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
                        "what tags is",
                        "what related items",
                        "how do i decay them into compost"),
                List.of("lookup <query>"), this::knowledgeLookupAction));

        register(action("recipe_lookup", KNOWLEDGE, "Recipe for target",
                "Show the exact local recipe for a target when recipe data is available.",
                false, true, InputMode.TARGET_INPUT, "item / block / output",
                List.of("how do i craft", "how do i make", "recipe for", "can i craft"),
                List.of("recipe <query>"), this::recipeLookupAction));

        register(action("usage_lookup", KNOWLEDGE, "Uses for target",
                "Show what recipes or uses the target appears in.",
                false, true, InputMode.TARGET_INPUT, "item / block / input",
                List.of("uses for"),
                List.of("uses <query>"), this::usageLookupAction));

        register(action("mod_origin", KNOWLEDGE, "Which mod adds target",
                "Resolve the owning mod from the local registry and reports.",
                false, true, InputMode.TARGET_INPUT, "item / block / entity",
                List.of("which mod adds", "what mod added"),
                List.of("mod <query>"), this::modOriginAction));

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

        register(action("follow", BUDDY, "Follow me",
                "Ask approval, then make the passive buddy follow the player.",
                false, false, InputMode.NONE, null, List.of("follow me", "follow"), List.of("follow"),
                SafetyManager.SafetyLevel.SAFE_MOVEMENT, true, true, "Follow", this::followAction));

        register(action("stay", BUDDY, "Stay here",
                "Stop navigation and keep the buddy at its current location.",
                false, false, InputMode.NONE, null, List.of("stay here", "stay"), List.of("stay"),
                SafetyManager.SafetyLevel.SAFE_MOVEMENT, false, true, "Stay", this::stayAction));

        register(action("come", BUDDY, "Come here",
                "Ask approval, then move the buddy to the player.",
                false, false, InputMode.NONE, null, List.of("come here", "come"), List.of("come"),
                SafetyManager.SafetyLevel.SAFE_MOVEMENT, true, true, "Come", this::comeAction));

        register(action("mark_home", BUDDY, "Mark home",
                "Remember the player's current location as home.",
                false, false, InputMode.NONE, null, List.of("set home", "mark home"), List.of("home set"),
                this::markHomeAction));

        register(action("return_home", BUDDY, "Return home",
                "Ask approval, then navigate the buddy to the remembered home in this dimension.",
                false, false, InputMode.NONE, null, List.of("return home"), List.of("home return"),
                SafetyManager.SafetyLevel.SAFE_MOVEMENT, true, true, "Home", this::returnHomeAction));

        register(action("plan", PLANNING, "What should we do next?",
                "Ask GemmaBuddy for a plan using the current game state.",
                true, true, InputMode.MAIN_INPUT, "question",
                List.of("what do we do", "what should we do", "what should we do next", "next", "plan"),
                List.of("plan <request>", "goal plan"), this::planAction));

        register(action("goal_set", PLANNING, "Set goal",
                "Set and persist the current player goal.",
                false, true, InputMode.MAIN_INPUT, "goal", List.of("goal set"), List.of("goal set <goal>"),
                this::goalSetAction));

        register(action("goal_status", PLANNING, "Goal status",
                "Show the current persisted goal.",
                false, false, InputMode.NONE, null, List.of("goal status", "what was i doing"),
                List.of("goal status"), this::goalStatusAction));

        register(action("goal_clear", PLANNING, "Clear goal",
                "Clear the current goal.",
                false, false, InputMode.NONE, null, List.of("goal clear"), List.of("goal clear"),
                this::goalClearAction));

        register(action("remember", PLANNING, "Remember note",
                "Store a bounded local player note.",
                false, true, InputMode.MAIN_INPUT, "note", List.of("remember"), List.of("remember <note>"),
                this::rememberAction));

        register(action("notes", PLANNING, "Show notes",
                "Show recent local player notes.",
                false, false, InputMode.NONE, null, List.of("notes"), List.of("notes"), this::notesAction));

        register(action("find", FIND, "Find target",
                "Search inventory, nearby loaded area, and remembered discoveries without loading chunks.",
                false, true, InputMode.TARGET_INPUT, "item / block / entity", List.of("find"),
                List.of("find <target>"), this::findAction));

        register(action("scan", FIND, "Scan looked-at target",
                "Remember the block or entity the player is looking at.",
                false, false, InputMode.NONE, null, List.of("scan this", "scan"), List.of("scan"), this::scanAction));

        register(action("track_stop", FIND, "Stop tracking",
                "Clear the current tracked find target.",
                false, false, InputMode.NONE, null, List.of("stop tracking"), List.of("track stop"),
                this::trackStopAction));

        register(action("stop", BUDDY, "Stop",
                "Immediately cancel movement, tracking, pending approval, and queued work.",
                false, false, InputMode.NONE, null, List.of("stop"), List.of("stop"), this::stopAction));

        register(action("approve", DEBUG, "Approve pending action",
                "Approve the current pending safe action.",
                false, false, InputMode.NONE, null, List.of("approve"), List.of("approve"), this::approveAction));

        register(action("deny", DEBUG, "Deny pending action",
                "Deny the current pending action.",
                false, false, InputMode.NONE, null, List.of("deny"), List.of("deny"), this::denyAction));

        register(action("permissions", DEBUG, "Permissions",
                "Show the current alpha safety policy.",
                false, false, InputMode.NONE, null, List.of("permissions"), List.of("permissions"),
                this::permissionsAction));

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

        register(action("set_endpoint", DEBUG, "Set LM endpoint",
                "Set the OpenAI-compatible LM Studio chat-completions URL.",
                false, true, InputMode.TARGET_INPUT, "http://localhost:1234/v1/chat/completions",
                List.of("set endpoint"), List.of("config endpoint <target>"), this::setEndpointAction));

        register(action("set_model", DEBUG, "Set model name",
                "Set the LM Studio model identifier/profile name.",
                false, true, InputMode.TARGET_INPUT, "model name", List.of("set model"),
                List.of("config model <target>"), this::setModelAction));

        register(action("set_thinking", DEBUG, "Thinking off/auto/on",
                "Set model thinking policy. Off remains the recommended default.",
                false, true, InputMode.TARGET_INPUT, "off / auto / on", List.of("set thinking"),
                List.of("config thinking <target>"), this::setThinkingAction));

        register(action("set_tokens", DEBUG, "Set max tokens",
                "Set normal and planning token limits as: normal planning.",
                false, true, InputMode.TARGET_INPUT, "512 2048", List.of("set max tokens"),
                List.of("config tokens <target>"), this::setTokensAction));

        register(action("set_temperature", DEBUG, "Set temperature",
                "Set normal and planning temperatures as: normal planning.",
                false, true, InputMode.TARGET_INPUT, "0.15 0.25", List.of("set temperature"),
                List.of("config temperature <target>"), this::setTemperatureAction));

        register(action("set_timeout", DEBUG, "Set LM timeout",
                "Set the LM request timeout in seconds.",
                false, true, InputMode.TARGET_INPUT, "45", List.of("set timeout"),
                List.of("config timeout <target>"), this::setTimeoutAction));

        register(action("dump_actions", DEBUG, "Dump action registry",
                "Print registered action IDs grouped by category.",
                false, false, InputMode.NONE, null, List.of("dump action registry"), List.of("actions"),
                this::dumpActionsAction));

        register(action("selfcheck", DEBUG, "Run self-check",
                "Run concise registry, knowledge, planner, config, and UI safety checks.",
                false, false, InputMode.NONE, null, List.of("selfcheck"), List.of("selfcheck"),
                this::selfCheckAction));

        register(action("skill_shelter_plan", PLANNING, "Plan basic shelter",
                "Plan-only starter shelter skill; it never places blocks.",
                false, false, InputMode.NONE, null, List.of("plan basic shelter"), List.of(),
                SafetyManager.SafetyLevel.WORLD_CHANGE, true, true, "Shelter", this::planOnlySkillAction));
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
        if (actionsById.containsKey(definition.id())) {
            throw new IllegalStateException("Duplicate GemmaBuddy action id: " + definition.id());
        }
        actionsById.put(definition.id(), definition);
        actionsByCategory.computeIfAbsent(definition.category(), key -> new ArrayList<>()).add(definition);
        for (String alias : definition.aliases()) {
            ActionDefinition existing = actionsByAlias.putIfAbsent(normalize(alias), definition);
            if (existing != null && existing != definition) {
                LOGGER.warn("Ambiguous GemmaBuddy alias '{}' is owned by '{}' and '{}'", alias, existing.id(),
                        definition.id());
            }
        }
    }

    private ActionDefinition action(String id, String category, String label, String description, boolean longRunning,
            boolean requiresInput, InputMode inputMode, String inputPlaceholder, List<String> aliases,
            List<String> slashPaths,
            ActionHandler handler) {
        return new ActionDefinition(id, category, label, description, longRunning, requiresInput, inputMode,
                inputPlaceholder, aliases, slashPaths, SafetyManager.SafetyLevel.READ_ONLY, false, true,
                actionsById.size(), label, handler);
    }

    private ActionDefinition action(String id, String category, String label, String description, boolean longRunning,
            boolean requiresInput, InputMode inputMode, String inputPlaceholder, List<String> aliases,
            List<String> slashPaths, SafetyManager.SafetyLevel safetyLevel, boolean approvalRequired,
            boolean uiVisible, String shortLabel, ActionHandler handler) {
        return new ActionDefinition(id, category, label, description, longRunning, requiresInput, inputMode,
                inputPlaceholder, aliases, slashPaths, safetyLevel, approvalRequired, uiVisible, actionsById.size(),
                shortLabel, handler);
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
        return runStructuredPlanning(context, query);
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

    private ActionResult modOriginAction(ActionContext context) {
        String query = firstNonBlank(context.argument(), context.normalizedInput());
        ActionResult deterministic = tryDeterministicKnowledgeAnswer(context, "which mod adds " + query);
        if (deterministic != null) {
            return deterministic;
        }
        GemmaBuddy.sendError(context.player(), "I could not resolve the owning mod from local registry data.");
        return ActionResult.failure("Mod ownership is unknown.");
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

    private ActionResult followAction(ActionContext context) {
        return context.safety().requestApproval(context.player(), "follow", "let GemmaBuddy follow you",
                () -> GemmaBuddy.setBuddyMode(context.player(), GemmaBuddyEntity.BuddyMode.FOLLOW));
    }

    private ActionResult stayAction(ActionContext context) {
        return GemmaBuddy.setBuddyMode(context.player(), GemmaBuddyEntity.BuddyMode.STAY);
    }

    private ActionResult comeAction(ActionContext context) {
        return context.safety().requestApproval(context.player(), "come", "move GemmaBuddy to you",
                () -> GemmaBuddy.setBuddyMode(context.player(), GemmaBuddyEntity.BuddyMode.COME_TO_PLAYER));
    }

    private ActionResult markHomeAction(ActionContext context) {
        context.memory().setHome(context.player());
        GemmaBuddy.sendLine(context.player(), "Home marked at " + context.player().blockPosition().toShortString()
                + " in " + context.player().level().dimension().location() + ".");
        return ActionResult.success("Home marked.");
    }

    private ActionResult returnHomeAction(ActionContext context) {
        return context.safety().requestApproval(context.player(), "return_home", "move GemmaBuddy to marked home",
                () -> GemmaBuddy.setBuddyMode(context.player(), GemmaBuddyEntity.BuddyMode.RETURN_HOME));
    }

    private ActionResult goalSetAction(ActionContext context) {
        String goal = firstNonBlank(context.argument(), context.normalizedInput());
        if (goal.isBlank()) {
            GemmaBuddy.sendError(context.player(), "Use: gemma goal set <goal>");
            return ActionResult.failure("Missing goal.");
        }
        context.goals().setGoal(goal, List.of(), false);
        GemmaBuddy.sendLine(context.player(), "Goal set: " + goal);
        return ActionResult.success("Goal set.");
    }

    private ActionResult goalStatusAction(ActionContext context) {
        GemmaBuddy.sendLine(context.player(), "Goal: " + context.goals().statusLine());
        return ActionResult.success("Goal status shown.");
    }

    private ActionResult goalClearAction(ActionContext context) {
        context.goals().clear();
        GemmaBuddy.sendLine(context.player(), "Goal cleared.");
        return ActionResult.success("Goal cleared.");
    }

    private ActionResult rememberAction(ActionContext context) {
        String note = firstNonBlank(context.argument(), context.normalizedInput());
        if (note.isBlank()) {
            GemmaBuddy.sendError(context.player(), "Use: gemma remember <note>");
            return ActionResult.failure("Missing note.");
        }
        context.memory().remember(note);
        GemmaBuddy.sendLine(context.player(), "Remembered locally: " + note);
        return ActionResult.success("Note remembered.");
    }

    private ActionResult notesAction(ActionContext context) {
        List<String> notes = context.memory().notes();
        if (notes.isEmpty()) {
            GemmaBuddy.sendLine(context.player(), "No saved notes.");
        } else {
            GemmaBuddy.sendLine(context.player(), "Recent notes:");
            notes.stream().skip(Math.max(0, notes.size() - 8)).forEach(note -> GemmaBuddy.sendLine(context.player(), note));
        }
        return ActionResult.success("Notes shown.");
    }

    private ActionResult findAction(ActionContext context) {
        String query = firstNonBlank(context.argument(), context.normalizedInput());
        if (query.isBlank()) {
            GemmaBuddy.sendError(context.player(), "Use: gemma find <item/block/entity>");
            return ActionResult.failure("Missing find target.");
        }
        FindService.FindResult result = context.find().find(context.player(), query, GemmaBuddy.config().findRadius());
        if (!result.resolvedId().isBlank()) {
            context.memory().setTrackedTarget(result.resolvedId());
            GemmaBuddy.sendLine(context.player(), result.message() + " at " + result.position().toShortString()
                    + " (" + result.distance() + "m, source=" + result.source() + ").");
            return ActionResult.success("Find target located.");
        }
        GemmaBuddy.sendLine(context.player(), result.message());
        return ActionResult.failure("Target not found in fair search scope.");
    }

    private ActionResult scanAction(ActionContext context) {
        String target = ContextResolver.resolveTarget(context.player(), context.knowledge(), "what does this do");
        if (target.isBlank()) {
            GemmaBuddy.sendError(context.player(), "Look directly at a block/entity or hold an item, then scan again.");
            return ActionResult.failure("No context target.");
        }
        context.knowledge().rememberResolvedTarget(target);
        context.memory().setTrackedTarget(target);
        GemmaBuddy.sendLine(context.player(), "Scanned context target: " + target + ".");
        return ActionResult.success("Context target scanned.");
    }

    private ActionResult trackStopAction(ActionContext context) {
        context.memory().setTrackedTarget("");
        GemmaBuddy.sendLine(context.player(), "Tracking stopped.");
        return ActionResult.success("Tracking stopped.");
    }

    private ActionResult stopAction(ActionContext context) {
        context.safety().stopAll(context.player());
        context.memory().setTrackedTarget("");
        GemmaBuddyEntity buddy = GemmaBuddy.nearestBuddy(context.player());
        if (buddy != null) {
            buddy.setBuddyMode(GemmaBuddyEntity.BuddyMode.STOPPED);
        }
        context.goals().updateProgress("Stopped by player");
        GemmaBuddy.sendLine(context.player(), "Stopped movement, tracking, queued work, and pending approval.");
        return ActionResult.success("All active work stopped.");
    }

    private ActionResult approveAction(ActionContext context) {
        return context.safety().approve(context.player());
    }

    private ActionResult denyAction(ActionContext context) {
        return context.safety().deny(context.player());
    }

    private ActionResult permissionsAction(ActionContext context) {
        GemmaBuddy.sendLine(context.player(), context.safety().statusLine(context.player()));
        return ActionResult.success("Permission status shown.");
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

    private ActionResult setEndpointAction(ActionContext context) {
        String value = normalize(context.argument());
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("Endpoint needs a scheme and host");
            }
            GemmaBuddy.config().setLmStudioEndpoint(value);
            GemmaBuddy.sendLine(context.player(), "LM Studio endpoint saved: " + value);
            return ActionResult.success("Endpoint saved.");
        } catch (IllegalArgumentException ex) {
            GemmaBuddy.sendError(context.player(), "Invalid endpoint URL: " + friendlyError(ex));
            return ActionResult.failure("Invalid endpoint.");
        }
    }

    private ActionResult setModelAction(ActionContext context) {
        String value = normalize(context.argument());
        if (value.isBlank()) {
            return ActionResult.failure("Model name is required.");
        }
        GemmaBuddy.config().setModelName(value);
        GemmaBuddy.sendLine(context.player(), "Model saved: " + value);
        return ActionResult.success("Model saved.");
    }

    private ActionResult setThinkingAction(ActionContext context) {
        String value = normalize(context.argument());
        if (!List.of("off", "auto", "on").contains(value.toLowerCase(Locale.ROOT))) {
            GemmaBuddy.sendError(context.player(), "Thinking mode must be off, auto, or on.");
            return ActionResult.failure("Invalid thinking mode.");
        }
        GemmaBuddyConfig.ThinkingMode mode = GemmaBuddyConfig.ThinkingMode.parse(value);
        GemmaBuddy.config().setThinkingMode(mode);
        GemmaBuddy.sendLine(context.player(), "Thinking mode saved: " + mode.configValue());
        return ActionResult.success("Thinking mode saved.");
    }

    private ActionResult setTokensAction(ActionContext context) {
        String[] parts = normalize(context.argument()).split(" ");
        try {
            int normal = Integer.parseInt(parts[0]);
            int planning = parts.length > 1 ? Integer.parseInt(parts[1]) : normal;
            GemmaBuddy.config().setMaxTokens(normal, planning);
            GemmaBuddy.sendLine(context.player(), "Max tokens saved: normal="
                    + GemmaBuddy.config().maxTokensDefault() + ", planning="
                    + GemmaBuddy.config().maxTokensPlanning() + ".");
            return ActionResult.success("Token limits saved.");
        } catch (RuntimeException ex) {
            GemmaBuddy.sendError(context.player(), "Use two numbers, for example: 512 2048");
            return ActionResult.failure("Invalid token limits.");
        }
    }

    private ActionResult setTemperatureAction(ActionContext context) {
        String[] parts = normalize(context.argument()).split(" ");
        try {
            double normal = Double.parseDouble(parts[0]);
            double planning = parts.length > 1 ? Double.parseDouble(parts[1]) : normal;
            GemmaBuddy.config().setTemperatures(normal, planning);
            GemmaBuddy.sendLine(context.player(), "Temperatures saved: normal="
                    + GemmaBuddy.config().temperatureDefault() + ", planning="
                    + GemmaBuddy.config().temperaturePlanning() + ".");
            return ActionResult.success("Temperatures saved.");
        } catch (RuntimeException ex) {
            GemmaBuddy.sendError(context.player(), "Use two numbers, for example: 0.15 0.25");
            return ActionResult.failure("Invalid temperatures.");
        }
    }

    private ActionResult setTimeoutAction(ActionContext context) {
        try {
            GemmaBuddy.config().setRequestTimeoutSeconds(Integer.parseInt(normalize(context.argument())));
            GemmaBuddy.sendLine(context.player(),
                    "LM timeout saved: " + GemmaBuddy.config().requestTimeoutSeconds() + " seconds.");
            return ActionResult.success("Timeout saved.");
        } catch (RuntimeException ex) {
            GemmaBuddy.sendError(context.player(), "Timeout must be a number of seconds.");
            return ActionResult.failure("Invalid timeout.");
        }
    }

    private ActionResult dumpActionsAction(ActionContext context) {
        GemmaBuddy.sendLine(context.player(), "Registered actions: " + actionsById.size());
        actionSummaryLines().forEach(line -> GemmaBuddy.sendLine(context.player(), line));
        return ActionResult.success("Action registry shown.");
    }

    private ActionResult selfCheckAction(ActionContext context) {
        List<String> failures = new ArrayList<>();
        if (actionsById.values().stream().anyMatch(action -> action.handler() == null)) {
            failures.add("visible action without handler");
        }
        if (actionsById.values().stream().filter(ActionDefinition::uiVisible)
                .anyMatch(action -> action.category().isBlank())) {
            failures.add("visible action without category");
        }
        if (context.player().getServer() != null
                && context.repository().resolveEntry(context.player().getServer(), "spruce leaves").isEmpty()) {
            failures.add("spruce leaves alias");
        }
        PlannerService.PlannerFactPacket packet = context.planner().buildFactPacket("selfcheck", context.snapshot(),
                context.memory().currentGoal(), "");
        long uniqueRefs = packet.availableActions().stream().map(PlannerService.AvailableAction::actionRef).distinct()
                .count();
        if (uniqueRefs != packet.availableActions().size()) {
            failures.add("duplicate planner action_ref");
        }
        PlannerService.ValidatedPlan invalidPlan = context.planner().validate(packet,
                new PlannerService.PlannerProposal("test",
                        List.of(new PlannerService.ProposedStep("missing_ref", "", "")), List.of(), 1.0D, List.of()));
        if (invalidPlan.steps().isEmpty() || !"blocked".equals(invalidPlan.steps().get(0).status())) {
            failures.add("invalid action_ref validation");
        }
        PlannerService.PlannerFactPacket emptyInventoryPacket = new PlannerService.PlannerFactPacket(
                packet.request(), packet.playerState(), List.of(), packet.currentGoal(), packet.knowledgeFacts(),
                packet.availableActions(), packet.safetyRules());
        PlannerService.ValidatedPlan furnacePlan = context.planner().validate(emptyInventoryPacket,
                new PlannerService.PlannerProposal("test",
                        List.of(new PlannerService.ProposedStep("craft_furnace_1", "", "")), List.of(), 1.0D,
                        List.of()));
        if (furnacePlan.steps().isEmpty() || !"blocked".equals(furnacePlan.steps().get(0).status())) {
            failures.add("furnace prerequisite validation");
        }
        PlannerService.ValidatedPlan bedPlan = context.planner().validate(emptyInventoryPacket,
                new PlannerService.PlannerProposal("test",
                        List.of(new PlannerService.ProposedStep("craft_bed_1", "", "")), List.of(), 1.0D, List.of()));
        if (bedPlan.steps().isEmpty() || !"blocked".equals(bedPlan.steps().get(0).status())) {
            failures.add("bed prerequisite validation");
        }
        List<PlannerService.AvailableAction> rottenActions = new ArrayList<>(packet.availableActions());
        rottenActions.add(new PlannerService.AvailableAction("cook_rotten_flesh_1", "cook_item",
                "minecraft:rotten_flesh", "Cook rotten flesh", List.of(), List.of(), "world_change", true, false));
        PlannerService.PlannerFactPacket rottenPacket = new PlannerService.PlannerFactPacket(packet.request(),
                packet.playerState(), packet.inventory(), packet.currentGoal(), packet.knowledgeFacts(), rottenActions,
                packet.safetyRules());
        PlannerService.ValidatedPlan rottenPlan = context.planner().validate(rottenPacket,
                new PlannerService.PlannerProposal("test",
                        List.of(new PlannerService.ProposedStep("cook_rotten_flesh_1", "", "")), List.of(), 1.0D,
                        List.of()));
        if (rottenPlan.steps().isEmpty() || !"blocked".equals(rottenPlan.steps().get(0).status())) {
            failures.add("rotten flesh cooking validation");
        }
        if (GemmaBuddy.config().lmStudioEndpoint().isBlank()) {
            failures.add("LM Studio endpoint missing");
        }
        if (failures.isEmpty()) {
            GemmaBuddy.sendLine(context.player(), "Self-check passed: actions, aliases, planner refs, config, and knowledge.");
            LOGGER.info("GemmaBuddy self-check passed with {} actions", actionsById.size());
            return ActionResult.success("Self-check passed.");
        }
        GemmaBuddy.sendError(context.player(), "Self-check failures: " + String.join(", ", failures) + ".");
        LOGGER.warn("GemmaBuddy self-check failures: {}", failures);
        return ActionResult.failure("Self-check failed.");
    }

    private ActionResult planOnlySkillAction(ActionContext context) {
        GemmaBuddy.sendLine(context.player(),
                "Plan-only shelter skill: choose a safe site, estimate blocks, add light and a door. Execution is locked.");
        return ActionResult.success("Plan-only skill shown; can_execute=false.");
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
                    context.goals().markComplete("Planning failed");
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

    private ActionResult runStructuredPlanning(ActionContext context, String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank() || normalizedQuery.equals("plan")) {
            normalizedQuery = "what should we do next";
        }

        PlannerService.PlannerFactPacket packet = context.planner().buildFactPacket(normalizedQuery,
                context.snapshot(), context.memory().currentGoal(),
                context.knowledge().buildKnowledgeContext(normalizedQuery));
        context.goals().setGoal(firstNonBlank(context.memory().currentGoal(), "Planning"), List.of(), true);
        context.goals().updateProgress("Validating a local-first plan");
        GemmaBuddy.sendLine(context.player(), "Gemma is drafting a plan for Java to validate...");

        MinecraftServer server = context.player().getServer();
        String request = normalizedQuery;
        CompletableFuture.supplyAsync(() -> {
            try {
                String system = """
                        You propose Minecraft plans from the supplied fact packet.
                        Use only available action_ref values exactly as supplied.
                        Do not invent item counts, recipes, prerequisites, or executable state.
                        Java will validate every step. Return strict JSON only.
                        Never include reasoning, analysis, markdown, or code fences.
                        """;
                LmStudioResponse response = context.llm().completeJson(system, context.planner().promptFor(packet));
                String json = cleanJsonReply(response.content());
                if (json.isBlank() && !normalize(response.reasoningContent()).isBlank()) {
                    LmStudioResponse finalOnly = context.llm().completeJson(
                            system + "\nThinking is finished. Emit only the final JSON plan now.",
                            "Create the final JSON plan from this private reasoning and the fact packet. Do not repeat "
                                    + "the reasoning.\nReasoning:\n" + response.reasoningContent()
                                    + "\nFact packet:\n" + context.planner().promptFor(packet));
                    json = cleanJsonReply(finalOnly.content());
                }
                if (json.isBlank()) {
                    throw new IllegalArgumentException("LM Studio returned no final JSON plan");
                }
                PlannerService.PlannerProposal proposal;
                try {
                    proposal = context.planner().parseProposal(json);
                } catch (RuntimeException firstFailure) {
                    LmStudioResponse retry = context.llm().completeJson(
                            system + "\nYour previous output was invalid. Return one valid object matching output_schema.",
                            context.planner().promptFor(packet));
                    proposal = context.planner().parseProposal(cleanJsonReply(retry.content()));
                }
                return context.planner().validate(packet, proposal);
            } catch (Exception ex) {
                LOGGER.warn("Structured planner unavailable for '{}'; using safe local fallback", request, ex);
                PlannerService.PlannerProposal fallback = new PlannerService.PlannerProposal(
                        "LM Studio was unavailable, so this is a safe local fallback plan.",
                        List.of(
                                new PlannerService.ProposedStep("inspect_state_1", "Check current needs first.", ""),
                                new PlannerService.ProposedStep("scan_nearby_1", "Use only the loaded nearby area.",
                                        "inspect_state_1")),
                        List.of("No world-changing step was proposed."), 0.45D, List.of());
                return context.planner().validate(packet, fallback);
            }
        }).whenComplete((plan, error) -> {
            Runnable deliver = () -> {
                if (error != null) {
                    LOGGER.error("GemmaBuddy structured planning failed for '{}'", request, error);
                    context.goals().markComplete("Plan failed");
                    GemmaBuddy.sendError(context.player(), "Plan generation failed safely. No action was executed.");
                    return;
                }
                context.planner().playerLines(plan).forEach(line -> GemmaBuddy.sendLine(context.player(), line));
                context.goals().markComplete("Validated plan ready");
            };
            if (server != null) {
                server.execute(deliver);
            } else {
                deliver.run();
            }
        });
        return ActionResult.success("Structured planning request started.");
    }

    private ActionResult tryDeterministicKnowledgeAnswer(ActionContext context, String query) {
        MinecraftServer server = context.player().getServer();
        if (server == null) {
            return null;
        }

        ActionResult craftability = tryCraftabilityAnswer(context, query);
        if (craftability != null) {
            return craftability;
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

    private ActionResult tryCraftabilityAnswer(ActionContext context, String query) {
        String normalized = canonicalize(query);
        if (!normalized.startsWith("can i craft ")) {
            return null;
        }
        String target = normalized.substring("can i craft ".length()).replaceAll("\\s+now$", "").trim();
        if (target.isBlank() || context.player().getServer() == null) {
            return null;
        }
        Optional<KnowledgeDataverse.RecipeRecord> recipe = context.repository()
                .findRecipeForOutput(context.player().getServer(), target);
        if (recipe.isEmpty()) {
            GemmaBuddy.sendLine(context.player(), "I do not have an exact local recipe for " + target + ".");
            return ActionResult.failure("Exact recipe unavailable.");
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (StateSnapshot.ItemEntry item : context.snapshot().inventoryItems()) {
            counts.put(item.id(), item.count());
        }
        List<String> missing = new ArrayList<>();
        for (KnowledgeDataverse.IngredientCount ingredient : recipe.get().ingredients()) {
            int available = ingredient.itemIds().stream().mapToInt(id -> counts.getOrDefault(id, 0)).sum();
            if (available < ingredient.count()) {
                missing.add((ingredient.count() - available) + " " + ingredient.label());
                continue;
            }
            int remainingNeeded = ingredient.count();
            for (String id : ingredient.itemIds()) {
                int held = counts.getOrDefault(id, 0);
                int used = Math.min(held, remainingNeeded);
                counts.put(id, held - used);
                remainingNeeded -= used;
                if (remainingNeeded == 0) {
                    break;
                }
            }
        }
        if (missing.isEmpty()) {
            GemmaBuddy.sendLine(context.player(), "Yes. You have the exact indexed ingredients for "
                    + recipe.get().outputName() + ".");
            return ActionResult.success("Craftable from current inventory.");
        }
        GemmaBuddy.sendLine(context.player(), "No. To craft " + recipe.get().outputName() + ", you are missing "
                + String.join(", ", missing) + ".");
        return ActionResult.success("Missing ingredients reported.");
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
                || cleaned.startsWith("recipe for ") || cleaned.startsWith("can i craft ")) {
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

    private String cleanJsonReply(String reply) {
        String text = contextFreeSanitize(reply);
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (newline >= 0 && end > newline) {
                text = text.substring(newline + 1, end).trim();
            }
        }
        return text;
    }

    private String contextFreeSanitize(String reply) {
        String text = normalize(reply);
        text = text.replaceAll("(?is)<think>.*?</think>", "");
        text = text.replaceAll("(?is)<\\|channel\\|>\\s*(analysis|thought).*?(?=<\\|channel\\|>|$)", "");
        return text.trim();
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
            SafetyManager.SafetyLevel safetyLevel,
            boolean approvalRequired,
            boolean uiVisible,
            int ordering,
            String shortLabel,
            ActionHandler handler) {
        public ActionDefinition {
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
            slashPaths = List.copyOf(slashPaths == null ? List.of() : slashPaths);
        }
    }

    public record ResolvedAction(ActionDefinition definition, String argument, String matchedAlias) {
    }
}
