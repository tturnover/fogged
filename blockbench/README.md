# Blockbench sources

`.bbmodel` project files for the mod's placeholder block art, generated from the
current `src/main/resources/assets/fogged/models/block/*.json`. Open a file
in Blockbench (**File > Open Model**), edit geometry/UVs/textures, save.

Each file corresponds to a block model family, listed by target JSON in the
header comment inside each `.bbmodel` (`_fogged_notes` field). A few files are
**master shapes** that several JSON variants derive from by scaling (grass
age/moisture crosses, the fog-eye stem nub sizes) — edit the master once, and
the size variants get re-derived proportionally when plugged back in.

| .bbmodel | plugs into |
|---|---|
| `fog_eye_flower` | `fog_eye_template.json` |
| `fog_eye_head` | `fog_eye_head_template.json` |
| `fog_eye_head_lean` | `fog_eye_head_lean_template.json` |
| `fog_eye_base` | `fog_eye_base.json` |
| `fog_eye_stem_side` | `fog_eye_stem_side.json` |
| `fog_eye_stem_noside` | `fog_eye_stem_noside.json` (+ noside1/2/3, resized nub) |
| `foggy_grass` | `foggy_grass_age{0-3}_m{0-4}.json` (20 files, scaled) |
| `foggy_grass_side` | `foggy_grass_side_age{0-3}.json` (4 files, scaled) |
| `puff_bush_leaves_rare` | `puff_bush_leaves_rare.json` |
| `nozzle_filter` | `nozzle_filter.json` (`#net` is a flat-gray stand-in — real texture `create:block/net` lives in the Create mod's jar) |
| `fog_detector` | `fog_detector.json` (already finished art, rebuilt here so it's editable) |
| `fog_detector_extension` | `fog_detector_extension.json` (same) |

When you're done editing, hand the `.bbmodel` files back and they'll be
re-exported into the corresponding vanilla Java block-model JSON (keeping the
texture variable names noted above so datagen / blockstates keep working).
