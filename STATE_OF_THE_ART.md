# GemmaBuddy: State-of-the-Art Upgrade Proposals

These are proposed post-alpha systems, not claims about the current jar.

## 1. Evidence Knowledge Graph

Replace flat snippets with a typed local graph:

- nodes for entries, recipes, tags, machines, quests, advancements, dimensions, and discoveries;
- edges such as `crafted_by`, `used_in`, `unlocks`, `found_at`, `requires`, and `documented_by`;
- source, timestamp, confidence, mod version, and file provenance on every fact;
- Patchouli, FTB Quests, EMI/JEI, KubeJS, loot-table, tooltip, and config adapters.

This would let GemmaBuddy answer not only “what is this?” but “why is this relevant to my current progression, and what evidence supports that?”

## 2. Counterfactual Progression Simulator

Build a deterministic Java world-state simulator for planning:

- inventory deltas;
- recipe and machine transformations;
- tool/permission requirements;
- expected drops expressed as ranges and probabilities;
- time, fuel, durability, and travel-cost estimates;
- branch comparison without executing anything.

The LLM proposes candidate plans. The simulator scores feasibility, risk, cost, reversibility, and expected progression value.

## 3. Spatial Episodic Memory

Create a fair local map of what the player and buddy have genuinely observed:

- discovered resources and structures;
- container summaries with freshness timestamps;
- route landmarks and hazards;
- dimension-aware home/base networks;
- confidence decay when information becomes stale;
- no chunk loading and no unseen-world knowledge.

This would make “where did we see leather?” or “lead me back to the copper cave” genuinely useful without becoming x-ray.

## 4. Local Learning From Corrections

Add explicit player feedback:

- “that answer was wrong”;
- “this machine is important”;
- “prefer this recipe”;
- “never suggest this route”;
- “remember how this pack handles ore processing.”

Store corrections as local, reversible rules with provenance. Never silently fine-tune or upload data.

## 5. Trust And Explainability UI

Every answer or plan should be inspectable:

- fact/evidence drawer;
- deterministic versus model-generated labels;
- confidence and missing-data indicators;
- exact approval boundary;
- predicted inventory before/after;
- rollback/reversibility notes;
- one-click “why?” for every proposed step.

This would make GemmaBuddy safer and more credible than opaque autonomous agents.

## 6. Mod Adapter SDK

Define a small Java SPI so mod authors and pack makers can contribute:

- knowledge extractors;
- machine semantics;
- progression rules;
- safe action adapters;
- context providers;
- custom UI cards.

Adapters remain optional and versioned. The core mod still works without them.

## 7. Cooperative Companion Intelligence

Give the visible buddy socially useful, non-destructive behavior:

- pointing and looking toward tracked targets;
- contextual gestures and speech bubbles;
- carrying no hidden inventory;
- waiting at landmarks;
- multiplayer ownership and permission boundaries;
- shared plans that clearly distinguish each player’s responsibilities.

## 8. Modpack Evaluation Harness

Build repeatable local benchmarks:

- recipe identity and count accuracy;
- mod ownership resolution;
- craftability calculations;
- planner prerequisite correctness;
- hallucination and unsupported-claim rate;
- UI latency and indexing performance on 50, 200, and 600-mod packs;
- regression worlds with known expected answers.

The strongest differentiator would be measurable grounded accuracy, not merely a larger model.

## Recommended Order

1. Evidence graph and adapter SPI.
2. Counterfactual simulator.
3. Spatial episodic memory.
4. Explainability UI.
5. Correction learning.
6. Cooperative embodiment.
7. Public benchmark suite.
