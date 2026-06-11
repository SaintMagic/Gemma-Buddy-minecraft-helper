package com.saintmagic.gemmabuddy;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;

import com.saintmagic.gemmabuddy.GemmaBuddy;
import com.saintmagic.gemmabuddy.LmStudioClient;
import com.saintmagic.gemmabuddy.LmStudioClient.LmStudioResponse;
import com.saintmagic.gemmabuddy.KnowledgeIndex;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;

import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Routes chat, slash commands, and UI input to GemmaBuddy actions.
 */
public final class CommandRouter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PLANNING_MAX_TOKENS = 2048;
    private static final int QUICK_MAX_TOKENS = 128;
    private static final String LM_STUDIO_URL = "http://localhost:1234/v1/chat/completions";

    private final ActionRegistry actions;
    private final GoalManager goals;
    private final KnowledgeIndex knowledge;
    private final LmStudioClient llm;

    public CommandRouter(ActionRegistry actions, GoalManager goals, KnowledgeIndex knowledge, LmStudioClient llm) {
        this.actions = actions;
        this.goals = goals;
        this.knowledge = knowledge;
        this.llm = llm;
    }

    public void registerSlashCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(GemmaBuddy.MOD_ID)
                .then(Commands.literal("status").executes(context -> runSlash(context.getSource().getPlayerOrException(),
                        "status", "")))
                .then(Commands.literal("inventory").executes(context -> runSlash(context.getSource().getPlayerOrException(),
                        "inventory", "")))
                .then(Commands.literal("see").executes(context -> runSlash(context.getSource().getPlayerOrException(),
                        "see", "")))
                .then(Commands.literal("ask")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(context -> runSlash(context.getSource().getPlayerOrException(), "ask",
                                        StringArgumentType.getString(context, "message")))))
                .then(Commands.literal("study")
                        .then(Commands.literal("mods").executes(context -> runSlash(context.getSource().getPlayerOrException(),
                                "study mods", ""))))
                .then(Commands.literal("modreport")
                        .then(Commands.argument("modid", StringArgumentType.word())
                                .executes(context -> runSlash(context.getSource().getPlayerOrException(), "modreport",
                                        StringArgumentType.getString(context, "modid")))))
                .then(Commands.literal("knowledge")
                        .then(Commands.literal("status").executes(context -> runSlash(
                                context.getSource().getPlayerOrException(), "knowledge status", "")))
                        .then(Commands.literal("rebuild").executes(context -> runSlash(
                                context.getSource().getPlayerOrException(), "knowledge rebuild", ""))))
                .then(Commands.literal("spawn").executes(context -> runSlash(context.getSource().getPlayerOrException(),
                        "spawn", "")))
                .then(Commands.literal("despawn").executes(context -> runSlash(context.getSource().getPlayerOrException(),
                        "despawn", "")))
                .then(Commands.literal("where").executes(context -> runSlash(context.getSource().getPlayerOrException(),
                        "where", "")))
                .then(Commands.literal("lmstudio")
                        .then(Commands.literal("test").executes(context -> runSlash(
                                context.getSource().getPlayerOrException(), "lmstudio test", ""))))
                .then(Commands.literal("config")
                        .then(Commands.literal("path").executes(context -> runSlash(
                                context.getSource().getPlayerOrException(), "show config path", ""))))
                .then(Commands.literal("knowledgepath")
                        .executes(context -> runSlash(context.getSource().getPlayerOrException(),
                                "show knowledge folder path", "")))
                .then(Commands.literal("reload")
                        .then(Commands.literal("config").executes(context -> runSlash(
                                context.getSource().getPlayerOrException(), "reload config", "")))));
    }

    public ActionResult routeChat(ServerPlayer player, String rawText) throws Exception {
        String input = normalize(rawText);
        if (input.isBlank()) {
            return sendHelp(player);
        }

        String lower = canonicalize(input);
        if (lower.equals("help")) {
            return sendHelp(player);
        }

        ActionRegistry.ActionDefinition aliasedAction = actions.findByAlias(lower).orElse(null);
        if (aliasedAction != null && isDirectAliasAction(aliasedAction.id())) {
            return executeSlash(player, aliasedAction.commandTemplate(), "");
        }

        if (lower.equals("status")) {
            return handleStatus(player);
        }
        if (lower.equals("inventory")) {
            return handleInventory(player);
        }
        if (lower.equals("see") || lower.equals("what do you see") || lower.equals("what do you see?")) {
            return handleSee(player);
        }
        if (lower.equals("nearby danger") || lower.equals("danger") || lower.equals("nearby danger?")) {
            return handleDanger(player);
        }
        if (lower.equals("spawn")) {
            return handleSpawn(player);
        }
        if (lower.equals("despawn") || lower.equals("remove buddy")) {
            return handleDespawn(player);
        }
        if (lower.equals("where") || lower.equals("where are you") || lower.equals("where are you?")) {
            return handleWhere(player);
        }

        if (lower.equals("study mods")) {
            return handleStudyMods(player, false);
        }
        if (lower.equals("what mods do we have")) {
            return knowledge.whatModsDoWeHave(player);
        }
        if (lower.equals("knowledge status")) {
            return knowledge.knowledgeStatus(player);
        }
        if (lower.equals("knowledge rebuild")) {
            return handleStudyMods(player, true);
        }
        if (lower.startsWith("modreport ")) {
            return knowledge.modReport(player, input.substring("modreport ".length()).trim());
        }
        if (lower.equals("modreport")) {
            return ActionResult.failure("Use: gemma modreport <modid>");
        }
        if (isKnowledgeQuestion(lower)) {
            return handleKnowledgeQuestion(player, input);
        }

        if (isPlanningAlias(lower)) {
            return handlePlanning(player, input, "planning");
        }

        if (lower.equals("ask") || lower.startsWith("ask ")) {
            String message = lower.equals("ask") ? "" : input.substring(4).trim();
            return handlePlanning(player, message, "ask");
        }

        if (lower.equals("lmstudio test")) {
            return handleLmStudioTest(player);
        }
        if (lower.equals("show config path")) {
            GemmaBuddy.sendLine(player, "Config folder: " + knowledge.configRootPath());
            return ActionResult.success("Config path shown.");
        }
        if (lower.equals("show knowledge folder path")) {
            GemmaBuddy.sendLine(player, "Knowledge folder: " + knowledge.knowledgeRootPath());
            return ActionResult.success("Knowledge path shown.");
        }
        if (lower.equals("reload config")) {
            GemmaBuddy.reloadConfig();
            knowledge.reloadFromDisk();
            GemmaBuddy.sendLine(player, "GemmaBuddy caches reloaded.");
            GemmaBuddy.sendLine(player, "Voice control is " + (GemmaBuddy.config().enableVoiceControl() ? "enabled" : "disabled")
                    + ".");
            GemmaBuddy.sendLine(player, knowledge.statusLine());
            return ActionResult.success("Config reloaded.");
        }

        return handlePlanning(player, input, "fallback");
    }

    public ActionResult executeSlash(ServerPlayer player, String command, String argument) throws Exception {
        String normalizedCommand = canonicalize(normalize(command));
        String normalizedArgument = normalize(argument);

        return switch (normalizedCommand) {
            case "status" -> handleStatus(player);
            case "inventory" -> handleInventory(player);
            case "see" -> handleSee(player);
            case "ask" -> handlePlanning(player, normalizedArgument, "ask");
            case "study mods" -> handleStudyMods(player, false);
            case "knowledge status" -> knowledge.knowledgeStatus(player);
            case "knowledge rebuild" -> handleStudyMods(player, true);
            case "modreport" -> knowledge.modReport(player, normalizedArgument);
            case "spawn" -> handleSpawn(player);
            case "despawn" -> handleDespawn(player);
            case "where" -> handleWhere(player);
            case "what does" -> handleWhatDoes(player, normalizedArgument);
            case "what should we do next", "what should we do", "next", "plan" ->
                    handlePlanning(player, normalizedArgument, "plan");
            case "lmstudio test" -> handleLmStudioTest(player);
            case "show config path" -> {
                GemmaBuddy.sendLine(player, "Config folder: " + knowledge.configRootPath());
                yield ActionResult.success("Config path shown.");
            }
            case "show knowledge folder path" -> {
                GemmaBuddy.sendLine(player, "Knowledge folder: " + knowledge.knowledgeRootPath());
                yield ActionResult.success("Knowledge path shown.");
            }
            case "reload config" -> {
                GemmaBuddy.reloadConfig();
                knowledge.reloadFromDisk();
                GemmaBuddy.sendLine(player, "GemmaBuddy caches reloaded.");
                GemmaBuddy.sendLine(player, "Voice control is " + (GemmaBuddy.config().enableVoiceControl() ? "enabled" : "disabled")
                        + ".");
                GemmaBuddy.sendLine(player, knowledge.statusLine());
                yield ActionResult.success("Config reloaded.");
            }
            default -> handlePlanning(player, normalizedArgument.isBlank() ? normalizedCommand : normalizedArgument,
                    "fallback");
        };
    }

    private int runSlash(ServerPlayer player, String command, String argument) {
        try {
            executeSlash(player, command, argument);
            return 1;
        } catch (Exception ex) {
            LOGGER.error("GemmaBuddy slash command failed for {} {}", command, argument, ex);
            GemmaBuddy.sendError(player, "That command failed: " + friendlyError(ex) + ". Check the log for details.");
            return 0;
        }
    }

    private ActionResult handleStatus(ServerPlayer player) {
        StateSnapshot snapshot = StateSnapshot.capture(player);
        for (String line : snapshot.statusLines()) {
            GemmaBuddy.sendLine(player, line);
        }
        return ActionResult.success("Status shown.");
    }

    private ActionResult handleInventory(ServerPlayer player) {
        StateSnapshot snapshot = StateSnapshot.capture(player);
        GemmaBuddy.sendLine(player, "Inventory: " + snapshot.formatInventorySummary());
        GemmaBuddy.sendLine(player, "Items: " + StateSnapshot.joinLimited(snapshot.inventoryItems(), 10,
                StateSnapshot::formatItem));
        return ActionResult.success("Inventory shown.");
    }

    private ActionResult handleSee(ServerPlayer player) {
        StateSnapshot snapshot = StateSnapshot.capture(player);
        for (String line : snapshot.seeLines()) {
            GemmaBuddy.sendLine(player, line);
        }
        return ActionResult.success("Nearby scan shown.");
    }

    private ActionResult handleDanger(ServerPlayer player) {
        StateSnapshot snapshot = StateSnapshot.capture(player);
        if (snapshot.nearbyDanger().isEmpty()) {
            GemmaBuddy.sendLine(player, "Nearby danger: none.");
        } else {
            GemmaBuddy.sendLine(player, "Nearby danger: " + StateSnapshot.joinLimited(snapshot.nearbyDanger(), 8,
                    StateSnapshot::formatEntity));
        }
        return ActionResult.success("Danger checked.");
    }

    private ActionResult handleSpawn(ServerPlayer player) {
        GemmaBuddy.spawnBuddy(player);
        return ActionResult.success("Buddy spawn requested.");
    }

    private ActionResult handleDespawn(ServerPlayer player) {
        GemmaBuddy.despawnBuddy(player);
        return ActionResult.success("Buddy despawn requested.");
    }

    private ActionResult handleWhere(ServerPlayer player) {
        GemmaBuddy.reportBuddyLocation(player);
        return ActionResult.success("Buddy location shown.");
    }

    private ActionResult handleStudyMods(ServerPlayer player, boolean forceRebuild) {
        return knowledge.studyMods(player, forceRebuild);
    }

    private ActionResult handleWhatDoes(ServerPlayer player, String query) throws Exception {
        String cleaned = normalize(query);
        if (cleaned.isBlank()) {
            GemmaBuddy.sendLine(player, "Use: gemma what does <item/block/mod> do");
            return ActionResult.failure("Missing query.");
        }
        return handleKnowledgeQuestion(player, "what does " + cleaned);
    }

    private ActionResult handleKnowledgeQuestion(ServerPlayer player, String rawQuery) throws Exception {
        String normalizedInput = normalize(rawQuery);
        String lower = canonicalize(normalizedInput);
        KnowledgeIntent intent = determineKnowledgeIntent(lower);
        String targetQuery = extractKnowledgeTarget(normalizedInput, lower, intent);

        if (targetQuery.isBlank()) {
            GemmaBuddy.sendLine(player, "Use: gemma what does <item/block/mod> do");
            return ActionResult.failure("Missing query.");
        }

        String contextTarget = ContextResolver.resolveTarget(player, knowledge, targetQuery);
        if (!contextTarget.isBlank()) {
            targetQuery = contextTarget;
        }

        LOGGER.info("Knowledge query original='{}' normalized='{}' target='{}' intent={}", rawQuery,
                normalizedInput, targetQuery, intent);

        KnowledgeIndex.LookupResult lookup = knowledge.resolveKnowledgeTarget(targetQuery);
        if (lookup == null) {
            String failureMessage = knowledge.isFollowUpQuery(targetQuery) || knowledge.isFollowUpQuery(lower)
                    ? "I do not have a previous target yet. Ask about an item or block first."
                    : "I could not resolve that target. Try a block, item, or mod id.";
            GemmaBuddy.sendError(player, failureMessage);
            LOGGER.info("Knowledge lookup failed for original='{}' normalized='{}' target='{}' intent={}", rawQuery,
                    normalizedInput, targetQuery, intent);
            return ActionResult.failure("No lookup result.");
        }

        knowledge.rememberResolvedTarget(lookup.registryId());
        GemmaBuddy.sendLine(player, knowledge.describeLookup(lookup));

        List<String> localLines = knowledge.buildKnowledgeAnswerLines(lookup, targetQuery,
                intent == KnowledgeIntent.PRACTICAL);
        LOGGER.info("Knowledge lookup resolved original='{}' normalized='{}' target='{}' intent={} resolved='{}' lines={}",
                rawQuery, normalizedInput, targetQuery, intent, lookup.registryId(), String.join(" | ", localLines));

        for (String line : localLines) {
            GemmaBuddy.sendLine(player, line);
        }

        return ActionResult.success("Knowledge lookup shown.");
    }

    private ActionResult handlePlanning(ServerPlayer player, String query, String mode) throws Exception {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank()) {
            GemmaBuddy.sendLine(player, "Use: gemma ask <message>");
            return ActionResult.failure("Missing question.");
        }

        StateSnapshot snapshot = StateSnapshot.capture(player);
        String knowledgeContext = knowledge.buildKnowledgeContext(normalizedQuery);
        goals.setGoal("Planning", List.of("Read the current state", "Answer in one short line"), true);
        goals.updateProgress("Asking LM Studio");
        GemmaBuddy.sendLine(player, "Gemma is thinking...");

        MinecraftServer server = player.getServer();
        CompletableFuture.supplyAsync(() -> {
            try {
                return completePlanningReply(snapshot, knowledgeContext, normalizedQuery);
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
                    goals.clear();
                    GemmaBuddy.sendError(player, "LM Studio had trouble answering: " + friendlyError(cause)
                            + ". Check the log for details.");
                    return;
                }

                GemmaBuddy.sendLine(player, reply);
                goals.markComplete(reply);
            };

            if (server != null) {
                server.execute(deliver);
            } else {
                deliver.run();
            }
        });

        return ActionResult.success("Planning request started.");
    }

    private String completePlanningReply(StateSnapshot snapshot, String knowledgeContext, String normalizedQuery)
            throws Exception {
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

        LmStudioResponse response = llm.complete(systemPrompt, userPrompt.toString(), PLANNING_MAX_TOKENS);
        String reply = cleanReply(response.content());
        if (reply.isBlank() && !normalize(response.reasoningContent()).isBlank()) {
            reply = requestFinalAnswerFromReasoning(response.reasoningContent());
        }
        if (reply.isBlank()) {
            return "I thought too long and forgot to answer. Ask me again shorter.";
        }
        return reply;
    }

    private ActionResult handleLmStudioTest(ServerPlayer player) {
        try {
            LmStudioResponse response = llm.complete(
                    "You are GemmaBuddy. Reply with one short Minecraft chat sentence.",
                    "Say hello in one short Minecraft chat sentence.",
                    QUICK_MAX_TOKENS);
            String reply = cleanReply(response.content());
            if (reply.isBlank() && !normalize(response.reasoningContent()).isBlank()) {
                reply = requestFinalAnswerFromReasoning(response.reasoningContent());
            }
            if (reply.isBlank()) {
                reply = "LM Studio replied with nothing.";
            }
            GemmaBuddy.sendLine(player, reply);
            return ActionResult.success(reply);
        } catch (Exception ex) {
            LOGGER.error("GemmaBuddy LM Studio test failed", ex);
            GemmaBuddy.sendError(player, "LM Studio test failed: " + friendlyError(ex)
                    + ". Check that LM Studio is running.");
            return ActionResult.failure("LM Studio test failed.");
        }
    }

    private boolean isKnowledgeQuestion(String lower) {
        return lower.startsWith("what does ")
                || lower.equals("what does")
                || lower.startsWith("what can i do with ")
                || lower.equals("what can i do with")
                || lower.startsWith("what is ")
                || lower.equals("what is")
                || lower.startsWith("what are ")
                || lower.equals("what are")
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
                || lower.equals("what is it for")
                || lower.equals("what is this for")
                || lower.equals("can i craft with it");
    }

    private KnowledgeIntent determineKnowledgeIntent(String lower) {
        if (lower.startsWith("what does ")
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
                || lower.equals("can i craft with it")) {
            return KnowledgeIntent.PRACTICAL;
        }

        return KnowledgeIntent.IDENTIFY;
    }

    private String extractKnowledgeTarget(String normalizedInput, String lower, KnowledgeIntent intent) {
        String trimmed = normalizedInput.trim();
        if (lower.equals("what does")
                || lower.equals("what can i do with")
                || lower.equals("what is")
                || lower.equals("what are")
                || lower.equals("how do i use")) {
            return "";
        }
        if (knowledge.isFollowUpQuery(lower)) {
            return lower;
        }

        if (lower.startsWith("what does ")) {
            return stripTrailingDo(trimmed.substring("what does ".length()).trim());
        }
        if (lower.startsWith("what can i do with ")) {
            return trimmed.substring("what can i do with ".length()).trim();
        }
        if (lower.startsWith("what can i craft with ")) {
            return trimmed.substring("what can i craft with ".length()).trim();
        }
        if (lower.startsWith("what can i make with ")) {
            return trimmed.substring("what can i make with ".length()).trim();
        }
        if (lower.startsWith("how do i use ")) {
            return trimmed.substring("how do i use ".length()).trim();
        }
        if (lower.startsWith("what is ")) {
            String target = trimmed.substring("what is ".length()).trim();
            target = stripTrailingFor(target);
            return target;
        }
        if (lower.startsWith("what are ")) {
            String target = trimmed.substring("what are ".length()).trim();
            target = stripTrailingFor(target);
            return target;
        }
        if (lower.startsWith("what is it for") || lower.startsWith("what is this for")) {
            return lower;
        }

        if (intent == KnowledgeIntent.PRACTICAL) {
            return trimmed;
        }

        return trimmed;
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

    private enum KnowledgeIntent {
        IDENTIFY,
        PRACTICAL
    }

    private String requestFinalAnswerFromReasoning(String reasoningContent) throws Exception {
        String summarizationSystemPrompt = """
                You are converting model reasoning into a final Minecraft chat reply.
                Give only the final one-sentence Minecraft chat reply.
                Do not include markdown, bullets, code fences, or reasoning.
                """;

        String summarizationUserPrompt = "Give only the final one-sentence Minecraft chat reply based on this reasoning:\n"
                + normalize(reasoningContent);

        LmStudioResponse response = llm.complete(summarizationSystemPrompt, summarizationUserPrompt, QUICK_MAX_TOKENS);
        return cleanReply(response.content());
    }

    private ActionResult sendHelp(ServerPlayer player) {
        GemmaBuddy.sendLine(player, "Use gemma status, inventory, see, ask <message>, or knowledge commands.");
        GemmaBuddy.sendLine(player, "Try: gemma study mods, gemma modreport <modid>, gemma what does <item> do.");
        return ActionResult.success("Help shown.");
    }

    private String friendlyError(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String cleaned = message.trim().replaceAll("\\s+", " ");
        return cleaned.length() > 120 ? cleaned.substring(0, 117) + "..." : cleaned;
    }

    private boolean isDirectAliasAction(String actionId) {
        return "spawn".equals(actionId) || "despawn".equals(actionId) || "where".equals(actionId);
    }

    private boolean isPlanningAlias(String lower) {
        return lower.equals("what do we do")
                || lower.equals("what should we do")
                || lower.equals("what should we do next")
                || lower.equals("next")
                || lower.equals("plan");
    }

    private String stripTrailingDo(String query) {
        String cleaned = canonicalize(query);
        if (cleaned.endsWith(" do")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return cleaned;
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

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private static String canonicalize(String text) {
        String normalized = normalize(text).toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\p{Punct}]+$", "");
    }
}
