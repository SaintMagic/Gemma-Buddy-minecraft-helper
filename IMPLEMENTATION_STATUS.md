# GemmaBuddy Implementation Status

Updated for the local companion alpha pass.

## Implemented

- NeoForge 1.21.1 standalone mod and custom companion entity.
- Shared `CommandRouter -> ActionRegistry -> ActionDefinition -> ActionResult` path.
- Slash commands, `gemma ...` chat aliases, and G UI action dispatch.
- Local status, inventory, nearby block/entity scanning, and context resolution.
- Local mod reports and vanilla/modded registry lookup.
- In-memory knowledge dataverse with exact recipe, usage, tag, and ownership evidence.
- On-demand local documentation cards.
- Optional LM Studio client with empty-content/reasoning fallback handling.
- Experimental voice control disabled by default.
- Persistent local goals, notes, home, discoveries, and tracking state.
- Structured planner packets with unique action refs and Java validation.
- Approval/deny/stop safety plumbing.
- Passive follow/stay/come/return-home movement modes.
- Fair inventory/loaded-area/memory find without chunk loading.
- Persistent permission levels, safe movement autoapproval, and tracked action-task status.
- Coordinate-aware tracking, open-container scans, and buddy guidance to known same-dimension targets.
- Craftable GemmaBuddy Console with compact recent-history/quick-action UI.
- Plan-only SkillRegistry for shelter, starter tools, enchanting setup, and next-step organization.
- Markdown and JSON documentation cards with machine/progression evidence where locally discoverable.
- Deterministic Progression Brain with recipes, missing counts, dependency paths, remembered sources, evidence, and confidence.
- Persistent bounded Work Orders with one-scope approval, pause/resume/cancel, budgets, interruption rules, and milestone-only reporting.
- Categorized in-game regression runner with JSON and Markdown reports.
- Dedicated Work Order settings screen and registry-generated Work Orders category in the G UI.

## Partial Alpha

- Grounded Q&A: deterministic recipes/usages work; inventory-aware craftability and broader evidence coverage are being expanded.
- Planning: strict proposal parsing and validation work; the available-action catalog is intentionally small and plan-only.
- Grounded Q&A: exact local recipes/usages/craftability work, but guidebook, quest, loot, and machine evidence coverage is incomplete.
- Safety: persistent permission profiles and action-task state work; world-changing execution remains intentionally locked.
- Buddy movement: explicit modes work, but advanced path recovery and cross-dimension travel are locked.
- Fair Find: inventory, drops, entities, loaded blocks, remembered open-container contents, and memory work.
- Work Orders: mining, gathering, building, and crafting are validated assisted workflows; autonomous mutation is not implemented.

## Locked / Plan-Only

- Autonomous mining, breaking, placing, attacking, hunting, looting, inventory manipulation, crafting, and building.
- Whole-world or unloaded-chunk search.
- Building skills execute as plans only.
- Voice cannot bypass routing, safety, or approvals.

## Safety Invariants

- Java is authoritative for recipes, ingredient counts, inventory counts, prerequisites, and safety.
- The LLM may propose or phrase; it cannot authorize execution.
- Model reasoning is never displayed.
- Every planner step uses a unique `action_ref`; repeated `action_id` values are allowed only as action types.
- `gemma stop` cancels active movement, tracking, queued work, and pending approval.
- Work Order approval applies once to an exact per-task scope; scope expansion requires a new approval.
- Work Orders never use safe-movement autoapproval to bypass their bounded approval.
- Missing or unavailable systems return a friendly locked/partial result rather than fake success.
