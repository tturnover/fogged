Fogged
======

A NeoForge mod (Minecraft 1.21.1) that drops a horizontal **breathing
boundary** across the world: a fogged "surface" plane, below which the player
breathes as if underwater. Air drains, drowning begins, the view fogs over to a
short render distance, and a semi-transparent plane with foam marks where the
boundary cuts through blocks, entities and (optionally) Sable contraptions.

The boundary is not fixed — it rises and falls over time, so the land you can
safely stand on shifts across the days. Everything the mod adds grows out of
that one idea: what happens to a world that is slowly, tidally drowning.

What's in it
------------

- **The fog plane** — the core drowning boundary: breath loss, underwater fog,
  the rendered separation surface, and its foam edges.
- **Fog moss** — creeps over the natural ground beneath the plane wherever the
  murk kills vegetation or a mob dies, eating worldgen terrain but stopping at
  anything a player built. Has temperature variants.
- **Foggy grass** — grows on top of fog moss, a drowned-world groundcover.
- **The fog lurker** — a large underground worm that prowls beneath players who
  linger deep. It surfaces a fin now and then; break a block near it and it
  lunges up for a heavy hit, then retreats.
- **The fog detector** — a block that senses the plane's height, for wiring
  contraptions to the rising and falling surface.

Progress
--------

Latest tagged release is **v0.4a**. Everything below is built but not yet cut
into a release:

- **Fog detector blocks** — redstone sensing of the fog level (0.5a).
- **Under-fog mob allow-list** — restricts which mobs spawn/survive below the
  plane (0.6a).
- **Under-fog rot** — fog moss spread and lava/fire/torch scouring (0.7a).
- **Fog-moss variants** — temperature variants and per-strength textures; fixed
  a reversed surface-boil (0.8a).
- **Foggy grass** — groundcover growing on fog moss (0.9a).
- **Fog lurker** — underground worm that stalks players who linger deep (0.10a).
- **In progress (uncommitted)** — foggy-grass moisture variants across ages, and
  a jointed lurker body (center + paws parts and animations).

Boundary height over time
-------------------------

The Y of the boundary is driven by a **day schedule** plus a smaller
**overday offset**, both in `config/fogged-*.toml`.

### `planeHeightSchedule` — height across world days

A TOML table mapping a world day (`dayTime / 24000`) to a boundary height.
The height is linearly interpolated between listed days; before the first day
it holds the first height, after the last day it holds the last.

```toml
planeHeightSchedule = { "0" = -30, "10" = 40, "80" = 100 }
```

Above: day 0 starts at Y −30, climbs linearly to Y 40 by day 10, then on to
Y 100 by day 80, and stays there afterwards.

### `overdayOffset` — daily rise and fall

A compact `[minNoon, maxMidnight]` pair added on top of the scheduled height.
The offset eases (cosine) from the noon minimum to the midnight maximum and
back over each day, so the surface breathes up at night and down at noon.

```toml
overdayOffset = [0.0, 4.0]   # +0 at noon, +4 at midnight
```

The rest of the tunables (air-loss rate, fog distance, plane and foam colours,
Sable integration, debug toggles) are documented inline in the generated
`config/fogged-*.toml`.

Building
--------

Open the repository in IntelliJ IDEA or Eclipse. Run `gradlew build` to build,
`gradlew --refresh-dependencies` if libraries are missing, `gradlew clean` to
reset generated state.

Mapping names use the official Mojang mappings; see the license at
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Resources
---------

- NeoForged docs: https://docs.neoforged.net/
- NeoForged Discord: https://discord.neoforged.net/
