# GemmaBuddy Roadmap

## North Star

GemmaBuddy is a standalone local-first AI companion for modded Minecraft.

It studies your installed mods, understands your world and inventory, answers modpack questions, helps plan goals, and eventually acts through safe, controlled systems.

Core rule:

- One standalone mod.
- Minimal required dependencies.
- No cloud required.
- No external companion stack required.

## Phase 0: Stabilize the Current Prototype

Goal: Make the existing mod reliable before adding dragon wings.

### Features

- `/gemmabuddy status`
- `/gemmabuddy inventory`
- `/gemmabuddy see`
- `/gemmabuddy ask <message>`
- `gemma status`
- `gemma inventory`
- `gemma what do you see`
- `gemma what should we do`
- `G` key UI
- LM Studio connection

### Fixes

- Fix G UI layout overlap
- Fix text rendering behind panels
- Fix empty LM Studio replies from thinking models
- Keep E2B working for fast testing
- Keep 12B compatibility for later

### Done when

- All basic commands work
- G UI is readable
- E2B replies correctly
- No crash on world load
- No command throws red syntax errors

Priority: highest

## Phase 1: Command Architecture Cleanup

Goal: Stop commands, UI, and chat from becoming separate tangled code paths.

### Add

- CommandRouter
- ActionRegistry
- ActionResult
- StateSnapshot
- LLMClient
- GoalManager placeholder

### Design

Every input path should call the same action system:

Slash command
-> CommandRouter
-> ActionRegistry
-> ActionResult

Chat alias
-> CommandRouter
-> ActionRegistry
-> ActionResult

G UI button
-> CommandRouter
-> ActionRegistry
-> ActionResult

### Done when

- Adding a new command only requires adding one action
- UI buttons do not duplicate command logic
- Chat aliases do not duplicate command logic

Priority: very high

## Phase 2: Better G UI

Goal: Make GemmaBuddy pleasant to use without memorizing slash commands.

### Add expandable categories

- Basic
- Knowledge / Mods
- Buddy / Entity
- AI / Planning
- Debug / Maintenance

### Buttons

#### Basic

- Status
- Inventory
- What do you see?
- Ask Gemma

#### Knowledge / Mods

- Study installed mods
- Knowledge status
- Rebuild knowledge index
- Open mod report
- Ask what this item/block does

#### Buddy / Entity

- Spawn buddy
- Despawn buddy
- Where are you?

#### AI / Planning

- What should we do next?
- Make a plan
- Ask custom question

#### Debug / Maintenance

- LM Studio test
- Show config path
- Show knowledge folder path
- Reload config

### Done when

- G UI works at different GUI scales
- Categories expand/collapse
- Buttons dispatch through CommandRouter
- Long tasks show progress
- No visual overlap

Priority: very high

## Phase 3: Visible Buddy Body

Goal: Stop being a disembodied haunted tooltip.

### First body version

- `/gemmabuddy spawn`
- `/gemmabuddy despawn`
- `/gemmabuddy where`
- `gemma spawn`
- `gemma where are you`

### Visual target

- Do not use villager as final look
- Use humanoid female companion placeholder
- Simple custom texture
- Name tag: GemmaBuddy
- No combat
- No inventory
- No world changes

### Later visual upgrade

- Custom humanoid model
- Idle animation
- Walk animation
- Look-at-player behavior
- Optional sitting/waving later

### Done when

- GemmaBuddy visibly spawns near player
- Can be removed cleanly
- Does not crash on reload
- Has non-villager humanoid look

Priority: high, but after UI stability

## Phase 4: Mod Study System / Knowledge Index

Goal: The true differentiator.

GemmaBuddy studies the installed modpack and builds local reports.

### Commands

- `/gemmabuddy study mods`
- `/gemmabuddy modreport <modid>`
- `/gemmabuddy knowledge status`
- `/gemmabuddy knowledge rebuild`

### Output folder

- `config/gemmabuddy/knowledge/`
- `config/gemmabuddy/knowledge/mods/<modid>.json`
- `config/gemmabuddy/knowledge/mods/<modid>.md`

### Each report should include

- mod id
- display name
- version
- items
- blocks
- entities
- recipes
- tags
- advancements
- creative tab hints
- possible progression hints

### Critical rule

Do not send the entire report to the LLM.

### Correct flow

player asks question
-> search Knowledge Index
-> retrieve relevant snippets
-> send compact context to LM Studio
-> GemmaBuddy answers

### Done when

- GemmaBuddy can index the installed modpack
- Reports are written to disk
- Knowledge status shows indexed counts
- Player can ask what a modded item/block does
- Answer uses local game data

Priority: core feature

## Phase 4.5: Documentation Builder

Goal: Turn raw modpack data into local, searchable documentation pages.

The Knowledge Index gives GemmaBuddy facts. The Documentation Builder turns those facts into evidence-based local docs that answer practical questions better than raw registry output alone.

### Build from local evidence

- recipes
- tags
- advancements
- loot tables
- lang and display names
- tooltips if accessible
- creative tabs
- guidebook text if available
- quest book data if available
- KubeJS scripts if present
- config hints if readable

### Output

- `config/gemmabuddy/knowledge/docs/<namespace>/<id>.md`
- optional supporting JSON beside each doc if practical

### Example knowledge card contents

- ID
- Name
- Mod
- Type
- How to craft or obtain it
- How to use it
- What it is used for
- Progression relevance
- Related items
- Known limitations
- Evidence sources used

### Critical rule

- Do not invent docs from vibes.
- Build docs from structured local evidence first.
- Let the LLM summarize the evidence, not fabricate it.

### Done when

- GemmaBuddy can answer "what is this for?" with uses, recipes, progression relevance, and limitations
- Docs are searchable by item, block, mod, or keyword
- Answers are grounded in local evidence rather than generic identity only

## Phase 5: Mod-Aware Q&A

Goal: Turn the Knowledge Index into useful answers.

### Questions GemmaBuddy should handle

- What mod added this item?
- What is this ore for?
- What can I craft with this?
- What should I do with veridium?
- Which backpack upgrade should I craft first?
- What food can I make with my inventory?
- What progression path does this mod seem to have?

### Inputs to use

- Knowledge Index
- player inventory
- nearby blocks/entities
- recipes
- tags
- advancements
- current dimension/biome

### Done when

- Answers are grounded in indexed data
- GemmaBuddy admits when data is missing
- GemmaBuddy does not hallucinate random mod wiki nonsense

Priority: very high

## Phase 6: Goals, Memory, and Planning

Goal: GemmaBuddy remembers what you are trying to do.

### Add

- Current goal
- Subgoals
- Player notes
- Known home/base location
- Important discovered items
- Modpack progression notes

### Commands

- `/gemmabuddy goal set <goal>`
- `/gemmabuddy goal status`
- `/gemmabuddy goal clear`
- `/gemmabuddy remember <note>`
- `/gemmabuddy notes`

### Example

- Goal: Build starter base
- Subgoal 1: Gather wood
- Subgoal 2: Make storage
- Subgoal 3: Study backpack upgrades
- Subgoal 4: Build furnace area

### Done when

- GemmaBuddy can maintain a goal across sessions
- Planning uses inventory + knowledge + notes
- The UI shows current goal

Priority: high

## Phase 7: Safe Action System

Goal: Prepare for doing things without turning the world into a smoking crater.

### First safe actions

- follow player
- come here
- stay
- look at player
- mark home
- go home later
- collect nearby dropped items later

### Action safety rules

- No world modification without explicit permission
- No block breaking in first action version
- No inventory manipulation until stable
- Every action must be cancellable
- `/gemmabuddy stop` must always work

### Architecture

- ActionRegistry
- PermissionLevel
- RunningTask
- TaskStatus
- CancelToken

### Done when

- GemmaBuddy can start/stop safe non-destructive actions
- UI shows current running action
- Commands remain responsive

Priority: medium-high

## Phase 8: Movement and Utility Actions

Goal: Give the companion legs.

### Possible actions

- follow me
- come here
- stay here
- go to marked home
- move to coordinates
- avoid danger
- return if too far

### Design inspiration

Baritone-style ideas only:

- goals
- processes
- path state
- task cancellation
- progress feedback

No required Baritone dependency.

### Done when

- GemmaBuddy can navigate basic terrain
- Does not get permanently lost immediately
- Can be stopped
- Can report what it is trying to do

Priority: medium

## Phase 9: Building and Skills

Goal: "Build me a house" becomes a structured skill, not magical nonsense.

### Skill system

- Skill name
- Aliases
- Parameters
- Required materials
- Steps
- Safety checks
- Preview/plan first
- Execute only after confirmation

### Example skill

- `build_basic_house`
- aliases: starter house, small house, simple base
- params: width, depth, material, roof type
- steps:
  1. choose flat area
  2. count materials
  3. place floor
  4. place walls
  5. add door
  6. add roof
  7. add light

### First mode

- Plan only

### Later mode

- Execute with confirmation

### Done when

- GemmaBuddy can turn vague requests into reusable skill plans
- Skills are saved locally
- Execution remains optional and safe

Priority: later

## Phase 10: Polish and Public Alpha

Goal: Make it usable by someone who is not living inside the codebase.

### Needed

- Config screen or config file
- Clean Modrinth page
- Screenshots
- Commands list
- Privacy note
- LM Studio setup guide
- Known limitations
- Crash-safe logging
- Versioned changelog

### Public alpha promise

- Read-only mod-aware companion
- Local LM Studio support
- Knowledge Index
- G UI
- Visible buddy
- Planning and Q&A
- No cloud required
- No required external mods

### Done when

- Fresh instance install works
- README explains setup
- No hardcoded local paths
- Bad LM Studio config gives friendly error

Priority: after core loop works

## The Build Order I'd Actually Follow

1. Fix current UI + LM Studio parsing
2. Refactor CommandRouter / ActionRegistry
3. Add expandable G UI categories
4. Replace villager with humanoid buddy
5. Add Knowledge Index
6. Add mod-aware Q&A
7. Add goals/memory/planning
8. Add safe movement/actions
9. Add skill system
10. Polish for public alpha

## The Golden Rule

Every phase must leave the mod in a usable state.

No giant rewrite where everything is broken for five days. Small upgrades, testable jar, then next brick. GemmaBuddy should grow like a suspiciously clever machine, not explode like a modpack with 400 mixins.
