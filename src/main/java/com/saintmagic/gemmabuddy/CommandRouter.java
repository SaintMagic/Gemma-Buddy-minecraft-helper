package com.saintmagic.gemmabuddy;

import java.util.Locale;

import org.slf4j.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Routes slash, chat, and UI input to a shared action execution path.
 */
public final class CommandRouter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ActionRegistry actions;
    private final GoalManager goals;
    private final KnowledgeIndex knowledge;
    private final KnowledgeRepository repository;
    private final MemoryManager memory;
    private final SafetyManager safety;
    private final FindService find;
    private final PlannerService planner;
    private final SkillRegistry skills;
    private final ProgressionBrain progression;
    private final WorkOrderService workOrders;
    private final RegressionTestService tests;
    private final LmStudioClient llm;

    public CommandRouter(ActionRegistry actions, GoalManager goals, KnowledgeIndex knowledge,
            KnowledgeRepository repository, MemoryManager memory, SafetyManager safety, FindService find,
            PlannerService planner, SkillRegistry skills, ProgressionBrain progression, WorkOrderService workOrders,
            RegressionTestService tests, LmStudioClient llm) {
        this.actions = actions;
        this.goals = goals;
        this.knowledge = knowledge;
        this.repository = repository;
        this.memory = memory;
        this.safety = safety;
        this.find = find;
        this.planner = planner;
        this.skills = skills;
        this.progression = progression;
        this.workOrders = workOrders;
        this.tests = tests;
        this.llm = llm;
    }

    public void registerSlashCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(GemmaBuddy.MOD_ID);
        for (ActionRegistry.ActionDefinition definition : actions.allActions()) {
            for (String slashPath : definition.slashPaths()) {
                root.then(buildSlashBranch(definition, slashPath));
            }
        }
        dispatcher.register(root);
    }

    public ActionResult routeChat(ServerPlayer player, String rawText) throws Exception {
        String input = normalize(rawText);
        if (input.isBlank()) {
            return sendHelp(player);
        }

        String canonical = canonicalize(input);
        if (canonical.equals("help")) {
            return sendHelp(player);
        }

        if (canonical.startsWith("action ")) {
            ActionRegistry.ResolvedAction resolved = actions.resolveChatAction(input).orElse(null);
            if (resolved == null) {
                GemmaBuddy.sendError(player, "Unknown GemmaBuddy action id.");
                return ActionResult.failure("Unknown action id.");
            }
            return executeResolved(player, input, resolved);
        }

        ActionRegistry.ResolvedAction resolved = actions.resolveChatAction(input).orElse(null);
        if (resolved != null) {
            return executeResolved(player, input, resolved);
        }

        if (canonical.startsWith("/gemmabuddy")) {
            GemmaBuddy.sendError(player, "Use /gemmabuddy commands directly, without gemma.");
            return ActionResult.failure("Slash command was wrapped in chat.");
        }
        GemmaBuddy.sendError(player, "I do not recognize that GemmaBuddy command. Use gemma ask <question>, "
                + "gemma plan <goal>, or a known command such as /gemmabuddy status.");
        return ActionResult.failure("Unsupported GemmaBuddy command.");
    }

    private ActionResult executeResolved(ServerPlayer player, String input, ActionRegistry.ResolvedAction resolved)
            throws Exception {
        ActionRegistry.ActionDefinition definition = resolved.definition();
        String argument = normalize(resolved.argument());
        ActionContext context = new ActionContext(
                player,
                definition.id(),
                input,
                input,
                argument,
                resolved.matchedAlias(),
                StateSnapshot.capture(player),
                knowledge,
                repository,
                goals,
                memory,
                safety,
                find,
                planner,
                skills,
                progression,
                workOrders,
                tests,
                llm);

        LOGGER.info("GemmaBuddy action resolved id='{}' alias='{}' argument='{}' input='{}'",
                definition.id(), resolved.matchedAlias(), argument, input);
        return actions.execute(context, definition.id());
    }

    private int runSlash(ServerPlayer player, String actionId, String argument) {
        try {
            ActionRegistry.ActionDefinition definition = actions.findById(actionId).orElse(null);
            if (definition == null) {
                GemmaBuddy.sendError(player, "Unknown GemmaBuddy action: " + actionId);
                return 0;
            }

            ActionContext context = new ActionContext(
                    player,
                    definition.id(),
                    argument,
                    argument,
                    normalize(argument),
                    "",
                    StateSnapshot.capture(player),
                    knowledge,
                    repository,
                    goals,
                    memory,
                    safety,
                    find,
                    planner,
                    skills,
                    progression,
                    workOrders,
                    tests,
                    llm);
            ActionResult result = actions.execute(context, definition.id());
            return result.success() ? 1 : 0;
        } catch (Exception ex) {
            LOGGER.error("GemmaBuddy slash command failed for {} {}", actionId, argument, ex);
            GemmaBuddy.sendError(player, "That command failed: " + friendlyError(ex) + ". Check the log for details.");
            return 0;
        }
    }

    private ArgumentBuilder<CommandSourceStack, ?> buildSlashBranch(ActionRegistry.ActionDefinition definition,
            String slashPath) {
        String[] tokens = normalize(slashPath).split(" ");
        return buildSlashBranch(definition, tokens, 0);
    }

    private ArgumentBuilder<CommandSourceStack, ?> buildSlashBranch(ActionRegistry.ActionDefinition definition,
            String[] tokens, int index) {
        String token = tokens[index];
        if (isPlaceholder(token)) {
            String argName = placeholderName(token);
            return Commands.argument(argName, placeholderIsGreedy(token) ? StringArgumentType.greedyString()
                    : StringArgumentType.word())
                    .executes(context -> runSlash(context.getSource().getPlayerOrException(), definition.id(),
                            StringArgumentType.getString(context, argName)));
        }

        LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal(token);
        if (index == tokens.length - 1) {
            literal.executes(context -> runSlash(context.getSource().getPlayerOrException(), definition.id(), ""));
        } else {
            literal.then(buildSlashBranch(definition, tokens, index + 1));
        }
        return literal;
    }

    private ActionResult sendHelp(ServerPlayer player) {
        GemmaBuddy.sendLine(player, "Registered GemmaBuddy actions:");
        for (String line : actions.actionSummaryLines()) {
            GemmaBuddy.sendLine(player, line);
        }
        return ActionResult.success("Help shown.");
    }

    private boolean isPlaceholder(String token) {
        return token.startsWith("<") && token.endsWith(">");
    }

    private String placeholderName(String token) {
        return token.substring(1, token.length() - 1).trim();
    }

    private boolean placeholderIsGreedy(String token) {
        String lower = normalize(token).toLowerCase(Locale.ROOT);
        return lower.contains("message") || lower.contains("question") || lower.contains("text")
                || lower.contains("input") || lower.contains("query") || lower.contains("request")
                || lower.contains("goal") || lower.contains("note") || lower.contains("target");
    }

    private String friendlyError(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String cleaned = message.trim().replaceAll("\\s+", " ");
        return cleaned.length() > 120 ? cleaned.substring(0, 117) + "..." : cleaned;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private static String canonicalize(String text) {
        String normalized = normalize(text).toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\p{Punct}]+$", "");
    }
}
