# GemmaBuddy Roadmap

## North Star

GemmaBuddy is a standalone, local-first AI companion for modded Minecraft. It studies the installed modpack, answers questions from local evidence, remembers useful context, proposes validated plans, and only acts through explicit safety controls.

Core rules:

- NeoForge mod only; no external bot client or companion process is required.
- Local LM Studio support is optional. Read-only features work without it.
- No cloud is required and local data stays under `config/gemmabuddy/`.
- No chunk loading for search by default.
- No destructive world actions by default.
- Every non-trivial action is planned and validated before execution.
- `gemma stop` must always be available.

## Current Correct Build Order

1. Finish ActionRegistry/CommandRouter centralization.
2. Finish G UI polish and action coverage.
3. Stabilize buddy entity and movement state.
4. Finish raw Knowledge Index.
5. Build Local Knowledge Dataverse and exact lookup services.
6. Build grounded recipe/usage/mod Q&A.
7. Build structured planner packets and plan validator.
8. Add goals/memory/world discovery.
9. Add safe action system and approvals.
10. Add movement commands.
11. Add Fair Find Mode.
12. Add tablet/mini console.
13. Add plan-only skills.
14. Polish and package public alpha.

## Planning Architecture Rule

The LLM proposes plans. Java validates plans. The LLM must never be the final authority on prerequisites, item counts, recipes, safety, or executable state.

Every available planner action has a unique `action_ref`. An `action_id` describes an action type and may repeat, so it must never be used as a plan-step identity.

## Model Modes

- Fast factual mode: thinking off, short timeout, deterministic facts first.
- Normal Q&A mode: thinking off, compact grounded context.
- Planner mode: thinking optional/auto with a timeout and retry without thinking.
- Heavy mode: configurable larger model, never forced as the default.
- Fallback mode: configurable smaller local model.
- All model calls receive structured fact packets.
- Exact facts come from the dataverse, not model memory.
- Reasoning or thought text is never shown in chat or UI.

Recommended local models:

- Best when the machine can run it: Gemma 4 26B A4B QAT, thinking off.
- Practical fallback: Gemma 4 12B QAT, thinking off.
- Lightweight fallback: Gemma 4 E4B.
- DiffusionGemma 26B A4B remains experimental and is not practical on 12 GB VRAM in current testing.

## Phase 0: Prototype Stabilization

Status: Mostly done.

- Basic slash/chat commands work.
- G UI and LM Studio response fallback exist.
- Errors are logged without crashing the game.

## Phase 1: Command Architecture Cleanup

Status: Done, with ongoing metadata/self-check polish.

- Slash commands, chat aliases, and G UI buttons route through `CommandRouter`.
- `ActionRegistry` owns executable handlers.
- `ActionResult` is the common outcome.
- New actions should normally be added in one registry definition.

## Phase 2: Better G UI

Status: Mostly done.

- Compact collapsible sidebar and structured chat history exist.
- Action buttons are generated from `ActionRegistry`.
- Remaining polish: richer settings controls, compact status details, and tablet reuse.

## Phase 3: Visible Buddy Body

Status: Partial.

- Custom humanoid companion, renderer, texture, spawn/despawn/where exist.
- Explicit movement modes and persistent controller state are required.

## Phase 4: Knowledge Index

Status: Done for the alpha baseline.

- Loaded mods, registry entries, recipes, tags, and reports are indexed locally.
- Vanilla `minecraft` is treated as a knowledge namespace.

## Phase 4.5: Local Knowledge Dataverse + Documentation Builder

Status: Mostly done; complete the service surface and evidence cards in this pass.

- Pure Java in-memory repository behind `KnowledgeRepository`.
- Exact entries, aliases, recipes, usages, tags, mod ownership, and generated cards.
- Compact docs under `config/gemmabuddy/knowledge/docs/<namespace>/<path>.md`.
- SQLite remains a future optional backend, not a dependency.

## Phase 5: Grounded Mod-Aware Q&A

Status: In progress.

- Deterministic identity, recipe, usage, tags, and mod ownership answers.
- Inventory-aware craftability must report exact missing counts.
- LLM may phrase facts but cannot invent counts or layouts.

## Phase 5.5: Structured Planner Input/Output and Plan Validator

Status: In progress.

- Structured fact packets and unique `action_ref` values.
- Strict JSON proposal parsing.
- Java validation of references, dependencies, counts, safety, and approvals.
- Invalid or unsafe steps are blocked, never silently executed.

## Phase 6: Goals, Memory, and Planning

Status: In progress.

- Persistent current goal, notes, home, discoveries, recent plans, and last targets.
- Bounded local JSON history under `config/gemmabuddy/memory/`.
- UI status strip shows the current goal.

## Phase 6.5: Deterministic Progression Brain

Status: Functional alpha.

- Resolves targets from the local dataverse.
- Reports exact indexed recipe counts, craftability, missing materials, remembered sources, dependency paths, evidence, and confidence.
- Feeds Work Orders without giving the LLM authority over counts or safety.
- Unknown resources become find/scout suggestions rather than invented coordinates.

## Phase 7: Safe Action System

Status: Functional alpha.

- Permissions, approvals, action queue, and universal stop.
- Persistent per-player levels and safe movement autoapproval.
- Read-only actions execute directly.
- Movement and world-affecting actions require policy checks.
- Mining, breaking, placing, attacking, looting, and inventory manipulation remain blocked.

## Phase 7.5: Supervised Work Orders + Autonomy Trust Contract

Status: Assisted alpha.

- One approval covers one exact bounded Work Order, never every micro-step.
- Supports small mining/gathering scopes, a 5x5 shelter preview, assisted crafting, starter preparation, enchanting preparation, and current-goal work.
- Budgets cover actions/blocks, distance, duration, allowed targets, and forbidden actions.
- Pause/stop conditions include combat, player distance, full inventory, missing materials, unsafe target changes, and budget exhaustion.
- Compact milestone reporting replaces step-by-step narration.
- Modes: manual, assisted, approved batch, safe auto, and read-only.
- Current limitation: world mutation remains assisted; autonomous mining/building/crafting is not enabled.
- Regression commands cover parser, knowledge, planner, find, Work Orders, and safety.

## Phase 8: Buddy Movement and Utility Commands

Status: Alpha movement only.

- Modes: idle, follow, stay, come, return home, guiding, stopped.
- Server-side passive navigation with no combat or world changes.
- Safe reload default is idle/stay.

## Phase 8.5: Fair Find Mode

Status: Functional alpha loaded-area search.

- Search inventory, nearby drops, nearby loaded blocks/entities, and memory.
- Remember explicitly scanned open-container contents and known target coordinates.
- Track targets and guide the buddy to known same-dimension positions.
- No chunk loading and no whole-world x-ray.
- Unknown targets return scout hints rather than fake coordinates.

## Phase 8.6: GemmaBuddy Console / Mini Tablet

Status: Functional alpha.

- Compact field console with recent replies, goal, buddy, tracking, and quick actions.
- All buttons must route through `ActionRegistry`.
- Craftable console item; right-click opens mini UI and shift-right-click scans context.

## Phase 9: Skill System / Advanced Building

Status: Functional plan-only alpha.

- Initial skills produce material estimates, missing materials, approvals, and validated steps.
- `can_execute` stays false until preview, safety, and world-action systems are proven.

## Phase 10: Public Alpha Polish

Status: In progress.

- Setup, privacy, troubleshooting, command docs, changelog, CI build, and release artifacts.
- Public promise remains local-first, grounded, fair-search, and non-destructive by default.

## Partial Alpha Rule

An incomplete feature may ship only when it:

- is registered through `ActionRegistry`;
- is visible in the UI when appropriate;
- returns a clear `ActionResult`;
- says `locked`, `plan-only`, or `not enabled` instead of pretending it ran;
- cannot crash or bypass permissions;
- is listed honestly in `IMPLEMENTATION_STATUS.md`.

## Golden Rule

Every phase leaves a usable build. GemmaBuddy should become clever one tested brick at a time, not become a 400-mixin crater.

## Beyond Alpha

The proposed evidence graph, progression simulator, spatial episodic memory, explainability UI, correction learning, adapter SDK, and benchmark harness are described in `STATE_OF_THE_ART.md`.
