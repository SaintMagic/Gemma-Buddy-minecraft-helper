package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Repeatable in-game regression harness with machine-readable local reports.
 */
public final class RegressionTestService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path root = FMLPaths.CONFIGDIR.get().resolve(GemmaBuddy.MOD_ID).resolve("test-reports");
    private volatile TestReport latest;

    public ActionResult run(ActionContext context, String requestedCategory) {
        String category = normalize(requestedCategory);
        if (category.isBlank()) {
            category = "all";
        }
        List<TestCase> cases = new ArrayList<>();
        if (matches(category, "parser")) {
            parserTests(cases);
        }
        if (matches(category, "knowledge")) {
            knowledgeTests(context, cases);
        }
        if (matches(category, "planner")) {
            plannerTests(context, cases);
        }
        if (matches(category, "find")) {
            findTests(context, cases);
        }
        if (matches(category, "workorders")) {
            workOrderTests(context, cases);
        }
        if (matches(category, "safety")) {
            safetyTests(context, cases);
        }
        if (cases.isEmpty()) {
            GemmaBuddy.sendError(context.player(),
                    "Unknown test category. Use parser, knowledge, planner, find, workorders, or safety.");
            return ActionResult.failure("Unknown test category.");
        }
        long passed = cases.stream().filter(TestCase::passed).count();
        latest = new TestReport(Instant.now().toString(), category, cases.size(), (int) passed,
                cases.size() - (int) passed, List.copyOf(cases));
        writeReports(latest);
        GemmaBuddy.sendLine(context.player(), "Regression tests: " + passed + "/" + cases.size() + " passed.");
        if (passed != cases.size()) {
            cases.stream().filter(test -> !test.passed()).limit(6)
                    .forEach(test -> GemmaBuddy.sendError(context.player(),
                            "FAIL " + test.name() + ": " + test.detail()));
        }
        GemmaBuddy.sendLine(context.player(), "Report: " + root.resolve("latest.md").toAbsolutePath());
        return passed == cases.size() ? ActionResult.success("Regression tests passed.")
                : ActionResult.failure("Regression test failures.");
    }

    public ActionResult report(ActionContext context) {
        if (latest == null) {
            Path json = root.resolve("latest.json");
            GemmaBuddy.sendLine(context.player(), Files.exists(json)
                    ? "Latest report: " + json.toAbsolutePath()
                    : "No regression report exists yet.");
            return Files.exists(json) ? ActionResult.success("Report path shown.")
                    : ActionResult.failure("No report.");
        }
        GemmaBuddy.sendLine(context.player(), "Latest tests: " + latest.passed() + "/" + latest.total()
                + " passed at " + latest.generatedAt() + ".");
        GemmaBuddy.sendLine(context.player(), "Report: " + root.resolve("latest.md").toAbsolutePath());
        return ActionResult.success("Latest report shown.");
    }

    private void parserTests(List<TestCase> cases) {
        expectRoute(cases, "craftability", "can i craft enchanting table now", "recipe_lookup");
        expectRoute(cases, "obsidian usage", "what is obsidian used for", "usage_lookup");
        expectRoute(cases, "copper usage", "what is copper used for", "usage_lookup");
        expectRoute(cases, "home set", "home set", "mark_home");
        expectRoute(cases, "set home", "set home", "mark_home");
        expectRoute(cases, "mark home", "mark home", "mark_home");
        expectRoute(cases, "skill shelter", "skill shelter", "skill_shelter_plan");
        expectRoute(cases, "build shelter work", "build basic shelter", "work_create");
        expectRoute(cases, "clear chat", "clear chat", "clear_chat");
        expectRoute(cases, "clear history", "clear history", "clear_chat");
        expectRoute(cases, "mine work", "mine 8 stone", "work_create");
        expectRoute(cases, "mine natural count", "mine some stone", "work_create");
        expectRoute(cases, "gather work", "gather 6 spruce logs", "work_create");
        expectRoute(cases, "goal work", "work on enchanting table", "work_create");
        expectRoute(cases, "craft work", "craft furnace", "work_create");
        expectRoute(cases, "shelter article", "build a basic shelter", "work_create");
        boolean unknown = GemmaBuddy.actionRegistry().resolveChatAction("banana mode").isEmpty();
        cases.add(test("unknown does not route to planner", unknown, unknown ? "unresolved" : "unexpected route"));
    }

    private void knowledgeTests(ActionContext context, List<TestCase> cases) {
        if (context.player().getServer() == null) {
            cases.add(test("server knowledge available", false, "server unavailable"));
            return;
        }
        Optional<KnowledgeDataverse.RecipeRecord> recipe = context.repository()
                .findRecipeForOutput(context.player().getServer(), "enchanting table");
        cases.add(test("enchanting table recipe exists", recipe.isPresent(), recipe.toString()));
        if (recipe.isPresent()) {
            Map<String, Integer> ingredients = new LinkedHashMap<>();
            recipe.get().ingredients().forEach(value -> ingredients.put(value.label().toLowerCase(Locale.ROOT),
                    value.count()));
            cases.add(test("enchanting table exact counts",
                    hasIngredient(ingredients, "book", 1) && hasIngredient(ingredients, "diamond", 2)
                            && hasIngredient(ingredients, "obsidian", 4),
                    ingredients.toString()));
            ProgressionBrain.ProgressionReport progression = context.progression().analyze(context.player(),
                    "enchanting table");
            cases.add(test("progression preserves recipe counts",
                    progression.recipe().stream().anyMatch(value -> value.startsWith("1 "))
                            && progression.recipe().stream().anyMatch(value -> value.startsWith("2 "))
                            && progression.recipe().stream().anyMatch(value -> value.startsWith("4 ")),
                    progression.recipe().toString()));
        }
        cases.add(test("enchantment table alias",
                context.repository().resolveEntry(context.player().getServer(), "enchantment table")
                        .map(value -> value.registryId().equals("minecraft:enchanting_table")).orElse(false),
                "alias resolution"));
        cases.add(test("enchant table alias",
                context.repository().resolveEntry(context.player().getServer(), "enchant table")
                        .map(value -> value.registryId().equals("minecraft:enchanting_table")).orElse(false),
                "alias resolution"));
        boolean usage = context.repository().findUsagesForInput(context.player().getServer(), "minecraft:obsidian")
                .stream().anyMatch(value -> value.outputId().equals("minecraft:enchanting_table"));
        cases.add(test("obsidian usage includes enchanting table", usage, "recipesByInput"));
    }

    private void plannerTests(ActionContext context, List<TestCase> cases) {
        PlannerService.PlannerFactPacket packet = context.planner().buildFactPacket("test", context.snapshot(), "",
                "");
        PlannerService.ValidatedPlan plan = context.planner().validate(packet,
                new PlannerService.PlannerProposal("test",
                        List.of(new PlannerService.ProposedStep("missing_ref", "", "")), List.of(), 1.0D, List.of()));
        cases.add(test("unknown planner action blocked",
                !plan.steps().isEmpty() && "blocked".equals(plan.steps().get(0).status()), plan.toString()));
        LmStudioClient.LmStudioResponse visible = context.llm().parseResponse(
                "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
        cases.add(test("visible LM content parsed", "OK".equals(visible.content()), visible.toString()));
        LmStudioClient.LmStudioResponse reasoning = context.llm().parseResponse(
                "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"hidden\"}}]}");
        cases.add(test("reasoning parsed separately",
                reasoning.content().isBlank() && "hidden".equals(reasoning.reasoningContent()), reasoning.toString()));
        cases.add(test("LM diagnostics states", LmStudioClient.ResponseStatus.values().length >= 8,
                Integer.toString(LmStudioClient.ResponseStatus.values().length)));
        cases.add(test("thinking off fields supported", context.llm().thinkingOffCompatibilityFieldsEnabled(),
                "compatibility flags"));
    }

    private void findTests(ActionContext context, List<TestCase> cases) {
        FindService.FindResult inventory = new FindService.FindResult("stone", "minecraft:stone", "inventory",
                BlockPos.ZERO, 0, 1.0D, false, "inventory");
        cases.add(test("inventory result not trackable", !inventory.trackable(), inventory.toString()));
        cases.add(test("loaded block result trackable",
                new FindService.FindResult("stone", "minecraft:stone", "nearby_loaded_block",
                        context.player().blockPosition(), 1, 0.9D, true, "loaded").trackable(),
                "loaded result"));
        cases.add(test("container result trackable",
                new FindService.FindResult("stone", "minecraft:stone", "remembered_container",
                        context.player().blockPosition(), 1, 0.8D, true, "container").trackable(),
                "container result"));
        cases.add(test("discovery result trackable",
                new FindService.FindResult("stone", "minecraft:stone", "world_memory",
                        context.player().blockPosition(), 1, 0.6D, true, "memory").trackable(),
                "memory result"));
        expectRoute(cases, "container contents alias", "what was in this chest", "container_contents");
        expectRoute(cases, "container label alias", "remember this chest as starter chest", "container_label");
    }

    private void workOrderTests(ActionContext context, List<TestCase> cases) {
        expectRoute(cases, "work command", "work mine 8 stone", "work_create");
        expectRoute(cases, "mine alias", "mine 8 stone", "work_create");
        expectRoute(cases, "gather alias", "gather 6 spruce logs", "work_create");
        expectRoute(cases, "build alias", "build basic shelter", "work_create");
        expectRoute(cases, "craft alias", "craft furnace", "work_create");
        cases.add(test("work orders enabled config", GemmaBuddy.config().workOrdersEnabled(),
                Boolean.toString(GemmaBuddy.config().workOrdersEnabled())));
        cases.add(test("ask every step disabled", !GemmaBuddy.config().askEveryStep(),
                Boolean.toString(GemmaBuddy.config().askEveryStep())));
        cases.add(test("approval scope per task", "per_task".equals(GemmaBuddy.config().approvalScopeDefault()),
                GemmaBuddy.config().approvalScopeDefault()));
    }

    private void safetyTests(ActionContext context, List<TestCase> cases) {
        WorkOrderSafetyRules rules = new WorkOrderSafetyRules();
        cases.add(test("forbidden block rejected", !rules.isSafeMineTarget("minecraft:chest"), "minecraft:chest"));
        cases.add(test("safe stone accepted", rules.isSafeMineTarget("minecraft:stone"), "minecraft:stone"));
        cases.add(test("autonomous mining disabled by default", !GemmaBuddy.config().autonomousMiningEnabled(),
                Boolean.toString(GemmaBuddy.config().autonomousMiningEnabled())));
        cases.add(test("autonomous building disabled by default", !GemmaBuddy.config().autonomousBuildingEnabled(),
                Boolean.toString(GemmaBuddy.config().autonomousBuildingEnabled())));
        PermissionManager.PermissionState state = context.safety().permissions().state(java.util.UUID.randomUUID());
        cases.add(test("default permission read-only",
                state.level() == PermissionManager.PermissionLevel.READ_ONLY, state.level().configValue()));
        cases.add(test("stop action registered", GemmaBuddy.actionRegistry().findById("stop").isPresent(),
                "action registry"));
    }

    private void expectRoute(List<TestCase> cases, String name, String input, String expectedAction) {
        String actual = GemmaBuddy.actionRegistry().resolveChatAction(input)
                .map(value -> value.definition().id()).orElse("");
        cases.add(test(name, expectedAction.equals(actual), "expected=" + expectedAction + ", actual=" + actual));
    }

    private boolean hasIngredient(Map<String, Integer> ingredients, String token, int count) {
        return ingredients.entrySet().stream()
                .anyMatch(entry -> entry.getKey().contains(token) && entry.getValue() == count);
    }

    private TestCase test(String name, boolean passed, String detail) {
        return new TestCase(name, passed, detail == null ? "" : detail);
    }

    private boolean matches(String requested, String category) {
        return "all".equals(requested) || category.equals(requested);
    }

    private void writeReports(TestReport report) {
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve("latest.json"), GSON.toJson(toJson(report)), StandardCharsets.UTF_8);
            List<String> markdown = new ArrayList<>();
            markdown.add("# GemmaBuddy Regression Report");
            markdown.add("");
            markdown.add("- Generated: " + report.generatedAt());
            markdown.add("- Category: " + report.category());
            markdown.add("- Passed: " + report.passed() + "/" + report.total());
            markdown.add("");
            for (TestCase test : report.cases()) {
                markdown.add("- [" + (test.passed() ? "x" : " ") + "] " + test.name() + " - " + test.detail());
            }
            Files.write(root.resolve("latest.md"), markdown, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.error("GemmaBuddy failed to write regression reports", ex);
        }
    }

    private JsonObject toJson(TestReport report) {
        JsonObject rootObject = new JsonObject();
        rootObject.addProperty("generatedAt", report.generatedAt());
        rootObject.addProperty("category", report.category());
        rootObject.addProperty("total", report.total());
        rootObject.addProperty("passed", report.passed());
        rootObject.addProperty("failed", report.failed());
        JsonArray cases = new JsonArray();
        report.cases().forEach(test -> {
            JsonObject value = new JsonObject();
            value.addProperty("name", test.name());
            value.addProperty("passed", test.passed());
            value.addProperty("detail", test.detail());
            cases.add(value);
        });
        rootObject.add("cases", cases);
        return rootObject;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public record TestCase(String name, boolean passed, String detail) {
    }

    public record TestReport(String generatedAt, String category, int total, int passed, int failed,
            List<TestCase> cases) {
    }
}
