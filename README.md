# GemmaBuddy

GemmaBuddy is a standalone local-first AI companion mod for NeoForge 1.21.1. It combines deterministic local modpack knowledge with an optional LM Studio model, a visible passive companion, persistent goals/notes, validated planning, and fair loaded-area search.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21
- LM Studio only for AI phrasing/planning; local status, knowledge, memory, movement controls, and find remain usable without it

## Build And Run

```powershell
.\gradlew.bat runClient
.\gradlew.bat build
```

The jar is written to:

```text
build\libs\gemmabuddy-0.1.0.jar
```

Copy it to the `mods` folder of the matching NeoForge 1.21.1 PrismLauncher instance.

## Controls

- `G`: full GemmaBuddy companion console
- `V`: experimental push-to-talk when `enableVoiceControl=true`
- Right-click `GemmaBuddy Console`: open the compact field console
- Shift-right-click `GemmaBuddy Console`: scan the looked-at block/entity or held item
- Voice is disabled by default and never bypasses routing, permissions, or approvals.

## Core Commands

```text
/gemmabuddy status
/gemmabuddy inventory
/gemmabuddy see
/gemmabuddy ask <message>
/gemmabuddy plan <request>
/gemmabuddy knowledge status
/gemmabuddy knowledge rebuild
/gemmabuddy recipe <target>
/gemmabuddy uses <target>
/gemmabuddy spawn
/gemmabuddy despawn
/gemmabuddy where
/gemmabuddy follow
/gemmabuddy stay
/gemmabuddy come
/gemmabuddy stop
/gemmabuddy goal set <goal>
/gemmabuddy goal status
/gemmabuddy remember <note>
/gemmabuddy notes
/gemmabuddy find <target>
/gemmabuddy scan
/gemmabuddy approve
/gemmabuddy deny
/gemmabuddy permissions
/gemmabuddy permissions set <level>
/gemmabuddy autoapprove movement on
/gemmabuddy track status
/gemmabuddy track guide
/gemmabuddy progress <target>
/gemmabuddy path <target>
/gemmabuddy missing <target>
/gemmabuddy craftable
/gemmabuddy next
/gemmabuddy work <request>
/gemmabuddy work status
/gemmabuddy work pause
/gemmabuddy work resume
/gemmabuddy work next
/gemmabuddy work cancel
/gemmabuddy autonomy status
/gemmabuddy test run
/gemmabuddy test report
/gemmabuddy selfcheck
```

Normal chat aliases start with `gemma`, for example:

```text
gemma status
gemma recipe for enchanting table
gemma can i craft enchanting table now?
gemma which mod adds spruce leaves
gemma goal set build a starter base
gemma remember check the village later
gemma plan survive the night and work toward enchanting
gemma follow me
gemma find spruce log
gemma track target
gemma guide me
gemma mine 8 stone
gemma gather 6 spruce logs
gemma build basic shelter
gemma craft furnace
gemma path to enchanting table
gemma autonomy assisted
gemma less chatty
gemma pause work
gemma resume work
gemma stop
```

## Supervised Work Orders

Work Orders turn small requests into deterministic, bounded scopes. Java resolves targets, checks recipes/materials, enforces distance/action/time budgets, and asks once for the complete task. Approval never expands beyond that saved scope.

- Mining, gathering, building, and crafting are assisted in this release.
- GemmaBuddy can preview, guide, track milestones, pause, resume, and stop.
- It does not autonomously break/place blocks or manipulate inventory yet.
- Repeated safe steps inside one approved scope do not trigger repeated approvals.
- Interruptions are reserved for danger, missing materials, blocked paths, ambiguity, full inventory, budget limits, completion, or failure.
- `gemma stop` immediately cancels the Work Order and pending approval.

Autonomy modes are `manual`, `assisted`, `approved_batch`, `safe_auto`, and `read_only`. The current executor remains assisted even if a higher mode is selected; autonomous mining/building flags default to off.

Work Order state is stored under `config/gemmabuddy/work-orders/`. Regression reports from `/gemmabuddy test run` are written to:

```text
config/gemmabuddy/test-reports/latest.json
config/gemmabuddy/test-reports/latest.md
```

## LM Studio

Start an OpenAI-compatible local server in LM Studio. GemmaBuddy creates:

```text
config/gemmabuddy/config.json
```

Important fields:

- `lmStudioEndpoint`
- `modelName`
- `modelProfile`
- `thinkingMode`: `off`, `auto`, or `on`
- `maxTokensDefault` and `maxTokensPlanning`
- `temperatureDefault` and `temperaturePlanning`
- `requestTimeoutSeconds`
- `retryWithoutThinkingOnTimeout`
- `hideReasoningAlways`

Recommended:

- Gemma 4 26B A4B QAT with thinking off, if the machine runs it comfortably
- Gemma 4 12B QAT with thinking off as the practical fallback
- Gemma 4 E4B as the lightweight fallback

DiffusionGemma 26B A4B is experimental and was not practical in current 12 GB VRAM testing.

GemmaBuddy suppresses `reasoning_content`, thought channels, and leaked analysis. Java remains authoritative for recipes, counts, prerequisites, safety, and executable state.

## Local Data And Privacy

No cloud is required. GemmaBuddy does not upload the modpack index. Local files live under:

```text
config/gemmabuddy/
config/gemmabuddy/knowledge/
config/gemmabuddy/knowledge/docs/
config/gemmabuddy/memory/
```

Knowledge cards are built from local registries, recipes, tags, and reports. Memory is bounded local JSON.

## Safety

- Default permission posture is read-only.
- Permission levels are `read-only`, `ask-before-action`, `safe-movement`, `inventory-actions`, `block-breaking`, and `building`.
- Permission choices persist locally per player UUID.
- Movement requests require approval at `ask-before-action`; `safe-movement` can run them directly.
- Only safe movement can be autoapproved in this alpha.
- `gemma stop` clears movement, tracking, queued work, and pending approval.
- Work Orders require one explicit approval for their exact task/action/distance/time scope.
- Mining, breaking, placing, attacking, looting, inventory manipulation, and autonomous building remain locked; their Work Orders are assisted.
- Search only inspects inventory, remembered discoveries, and a configured loaded-area radius. It does not load chunks.
- Building skills are plan-only.

## GemmaBuddy Console

The portable console recipe uses a clock, four iron ingots, three redstone dust, and one glass pane. It opens a compact phone-like UI with recent replies, goal/tracking status, and shared action buttons for Follow, Stay, Come, Stop, Scan, Find, Track, Guide, and the full G UI.

The console never duplicates action logic; every button routes through the same `CommandRouter` and `ActionRegistry`.

## Troubleshooting

### LM Studio unavailable

Check `lmStudioEndpoint`, confirm the local server is running, then use `/gemmabuddy lmstudio test`. Planning falls back to a small safe local plan when the model is unavailable.

### Empty model response

Keep thinking off first. GemmaBuddy retries/falls back without displaying reasoning. Increase the timeout only for planner mode.

### Reasoning leakage

Leave `hideReasoningAlways=true`. Report the model/server response in the log if visible thought text still appears.

### Missing knowledge

Run `/gemmabuddy knowledge rebuild`, then `/gemmabuddy knowledge status`. Generated reports and cards are written under `config/gemmabuddy/knowledge/`.

## Known Limits

- No destructive autonomous actions; mining/building/crafting Work Orders are assisted.
- No unloaded-chunk or whole-world search.
- Some mod recipes expose alternatives without precise tag names; answers state what local data knows.
- Experimental voice depends on a compatible local transcription endpoint and remains optional.
- Guidebook/quest/KubeJS extraction is not yet universal across every mod format.

See [ROADMAP.md](ROADMAP.md), [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md), and
[STATE_OF_THE_ART.md](STATE_OF_THE_ART.md).
