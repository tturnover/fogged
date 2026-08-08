# Blockbench sources

`.bbmodel` project files for the mod's editable block art, generated from the
current `src/main/resources/assets/fogged/models/block/*.json`. Open a file
in Blockbench (**File > Open Model**), edit geometry/UVs/textures, save.

Each file corresponds to a block model family, listed by target JSON in the
header comment inside each `.bbmodel` (`_fogged_notes` field).

| .bbmodel | plugs into |
|---|---|
| `fog_detector` | `fog_detector.json` (finished art) |
| `fog_detector_extension` | `fog_detector_extension.json` (finished art) |

The **nozzle filter** has no source here: `fogged:block/nozzle_filter` now
references Create's own nozzle model (`create:block/nozzle/block`) and textures
directly, swapping only the mesh texture (`#3`) to wool, coloured per block. It
needs no local art. **Done.**

When you're done editing, hand the `.bbmodel` files back and they'll be
re-exported into the corresponding vanilla Java block-model JSON (keeping the
texture variable names noted above so datagen / blockstates keep working).
