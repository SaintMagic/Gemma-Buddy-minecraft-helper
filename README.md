GemmaBuddy
===========

GemmaBuddy is a NeoForge 1.21.1 read-only play-buddy mod for local singleplayer and LAN worlds.

Features in this milestone:

- `/gemmabuddy status`
- `/gemmabuddy inventory`
- `/gemmabuddy see`
- `/gemmabuddy ask <message>`
- `gemma status`
- `gemma inventory`
- `gemma what do you see`
- `gemma what do we do`
- `gemma ask <message>`
- GemmaBuddy chat screen on `G`
- Custom GemmaBuddy companion entity with `/gemmabuddy spawn`, `/gemmabuddy despawn`, and `/gemmabuddy where`
- LM Studio chat completion support at `http://localhost:1234/v1/chat/completions`

Requirements:

- Java 21
- NeoForge 1.21.1 world or instance
- LM Studio running locally if you want the `ask` feature

Run locally from this folder:

```powershell
.\gradlew runClient
```

Build the jar:

```powershell
.\gradlew build
```

The built jar is written to:

```text
build\libs\gemmabuddy-0.1.0.jar
```

Copy that jar into your PrismLauncher instance `mods` folder, for example:

```text
%APPDATA%\.prismlauncher\instances\<your-instance>\minecraft\mods
```

If your PrismLauncher instance uses a different root, place the jar in that instance's `mods` directory.

## Notes

- The `G` key opens the GemmaBuddy chat screen.
- The mod is read-only for this milestone: no movement, mining, inventory changes, or world edits.
- If you want LM Studio to use a specific local model, set `GEMMABUDDY_LM_MODEL` or `LLM_MODEL` before launching Minecraft.
- GeckoLib is included for future animated companion work; the current visible companion uses the lighter vanilla slim-humanoid renderer for stability.

## Quick Test List

In Minecraft chat, try:

```text
/gemmabuddy status
/gemmabuddy inventory
/gemmabuddy see
/gemmabuddy ask what should we do first?
gemma status
gemma inventory
gemma what do you see
gemma what do we do
```
