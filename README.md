Fogged
======

A NeoForge mod that adds a horizontal **breathing boundary** to the world: a
fogged "surface" plane below which the player breathes as if underwater. Air
drains, drowning begins, the view fogs over to a short render distance, and a
semi-transparent plane with foam marks where the boundary cuts through blocks,
entities and (optionally) Sable contraptions.

The boundary is not fixed — it rises and falls over time.

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

Other config
------------

- `airLossPerTick` — air lost per tick (of 300) while below the boundary.
- `fogDistance` — render distance (blocks) of the underwater-style fog.
- `renderPlane` / `planeColor` — toggle and RGBA hex of the separation plane.
- `foamColor` / `foamWidth` — colour and reach of the foam band at edges.
- `sableFoam` — also foam around Sable sub-levels (no effect without Sable).
- `foamDebug` — render raw foam data for debugging.

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
