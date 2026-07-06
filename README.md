Fogged
======

A NeoForge mod for MC 1.21.1 that makes world separated horizontally by **fog**: a fogged plane, 
below which the player breathes similar as if underwater. Air drains, drowning begins, the view fogs over to a
short render distance, and a semi-transparent plane with foam marks where the
boundary cuts through blocks.

The fog is not fixed — it rises and falls over time, so the land you can
safely stand on shifts across the days.

What's in releases
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

In progress
--------

Latest tagged release is **v0.4a**. Everything below partially done but not yet cut
into a release:

- **Fog detector blocks** — redstone sensing of the fog level.
- **Under-fog mob allow-list** — restricts which mobs spawn/survive below the
  plane.
- **Under-fog rot** — fog flora spread and lava/fire/torch scouring.
- **Fog-flora variants** — temperature variants.
- **Fog lurker** — underground creature that stalks players who linger deep.