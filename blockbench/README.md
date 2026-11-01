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

## Entities (rigged)

Entities don't use vanilla models or GeckoLib: the mod loads Blockbench's
**Bedrock Entity** export itself (`com.fogged.rig`), plays its animations, and
fits the feet to the ground. Keep the `.bbmodel` sources in `blockbench/entities/`.

### Project

**File > New > Bedrock Entity**. Front of the creature faces **north (-Z)**,
the floor is at height 0, and every animation is made on that flat floor.
Leave **Settings > Export > Export Empty Groups** on (the default): the contact
and control bones have no cubes.

Box UV and per-face UV both work, as do cube rotation, inflate and mirror.
Mesh (polygon) elements and locators are ignored.

### Bone names

Names are the whole contract between Blockbench and the rig; anything else is
an ordinary bone that just animates.

| Bone | Required | Meaning |
|---|---|---|
| `root` | no | Takes the body height. Without it, every top-level bone does. |
| `leg_<id>` | per pillar leg | A one-bone leg, vanilla style; pivot on the hip. Slides up or down to meet the ground. |
| `leg_<id>_upper` | per jointed leg | Upper leg; pivot on the hip. |
| `leg_<id>_lower` | per jointed leg | Lower leg, inside the upper; pivot on the knee. Bends to meet the ground. |
| `contact_<id>` | per leg | Empty bone at the sole, inside the leg (the lower bone of a jointed one). The point that is put on the ground. |
| `reach_<id>_up`, `reach_<id>_down` | no | Empty bones **beside** a leg (in `root` or the body, not inside the leg), placed at the highest / lowest the sole may be moved to meet the ground. Either can be left out. |
| `*_tilt` | no | Any bone whose name ends in `_tilt` leans with the slope under the planted feet. No such bone, no tilt. |
| `ground` | no | Empty control bone; its scale switches the whole ground fit (below). |

- `<id>` is any name (`fl`, `fr`, `l1`, ...); any number of legs works, and
  pillar and jointed legs can be mixed. Any other bone named `leg_...` is read
  as a pillar, so name a foot bone e.g. `foot_fl`, not `leg_fl_foot`.
- **Reach limits:** drag `reach_<id>_up` / `_down` to where that foot may go;
  they ride with the body, so the range is relative to the hip, and they can
  be keyframed like any bone. Without them a pillar's limit is `max_step` and a
  jointed leg's is its own length.
  - **Up sets the step height.** The mob's real step height (how high it
    walks up, and pathfinds up) is the lowest `reach_<id>_up` above its sole in
    the rest pose, across all legs that have one. Read at startup from the
    model, so a change needs a restart. No up bones: the step height in code.
  - **Down sets the search depth.** A leg with `reach_<id>_down` looks for
    ground no deeper than it; `reach_down` in the tuning is only for legs
    without one.
- **Pillar:** without reach bones a pillar never slides further than `max_step`, so let its top
  reach into the body far enough to stay hidden for that much slide up *and*
  down (the trudger: hip 60 px, body 48 to 92 px, pillar top at the hip,
  reach bones at +32 / -12 px, so a 2-block step).
- **Jointed:** model the knee already bent. A straight leg has no reach left to
  stretch down. A dead-straight leg folds its knee toward the front (-Z) when
  it has to bend.

### Control keyframes

Bedrock animations only have rotation, position and scale (outliner
visibility is not exported), so the rig's switches are **scale keyframes on
empty bones**. Only the X component is read; uniform scale is fine.

| Keyframe | 1 (default) | 0 | Between |
|---|---|---|---|
| `ground` scale | full ground fit | off: no body height, tilt or leg correction | partial |
| `contact_<id>` scale | that foot is fitted | that foot follows the animation only | partial |

They blend through crossfades like any keyframe, so easing one from 1 to 0
over a few frames switches the fit off for part of an animation without a pop.

### Animations

| Name (default) | Plays when | Without it |
|---|---|---|
| `animation.<entity>.idle` | standing still | required |
| `animation.<entity>.walk` | moving mostly forward | required |
| `animation.<entity>.walk_back` | moving mostly backward | the walk, played in reverse |
| `animation.<entity>.strafe_left` / `strafe_right` | moving mostly sideways (to its own left / right) | the walk |
| `animation.<entity>.turn_left` / `turn_right` | standing but turning faster than `turn_threshold` | the walk |

"Moving" is faster than `walk_threshold`; which way is judged against the way
the body faces, with a little hysteresis so it doesn't flicker at the
diagonals. Moving animations play at the real speed over `walk_speed`, turns
at the real turning rate over `turn_speed` (0.25x to 4x).

- Switching crossfades over `blend_time`.
- Loop modes: *loop*, *play once* (holds the last frame) and *hold on last frame*.
- Linear, smooth (Catmull-Rom), step and bezier keyframes play as in
  Blockbench (bezier is baked into linear keys by the exporter).
- Molang expressions are not evaluated: such a value reads as rest (0, or 1
  for scale), and the log names the bones that used one.

### How the ground fit works

Every frame, after the animation:

1. A foot counts as planted while its `contact_<id>` is near the animation's
   floor (`plant_low` to `plant_high`, fading between).
2. The ground under every foot is found, from `max_step` above down to
   `max_step + reach_down` below, and smoothed (`foot_smoothing`).
3. The body moves to the weighted average of the planted feet whose ground is
   within `max_step` (`body_smoothing`), but never so high that a planted foot
   can't reach down to its ground, nor so low that one can't lift enough: a
   resting leg folds up to let a floating one down (a leg's reach is its
   `reach_<id>_up` / `_down` bones, else a step for a pillar and its straight
   length for a jointed leg). If not every foot can touch, the low one wins. `*_tilt` bones lean with the plane
   through them (`tilt_smoothing`, needs 3 planted feet).
4. Each planted leg puts its contact on its ground: a jointed leg bends its
   upper and lower bones, as far as the leg reaches; a pillar slides, at most
   `max_step`. A foot over a deeper drop reaches down
   but doesn't pull the body after it; ground taller than a step is left to
   the animation.

Heights are smoothed in world space, so the entity's own step up a block
doesn't show. A jump of more than 4 blocks (a teleport) is taken at once.

### Tuning file

`assets/fogged/rigs/<entity>.json`. Every key is optional and the file itself
can be missing; it reloads with **F3+T**.

| Key | Default | Unit | Meaning |
|---|---|---|---|
| `texture` | `fogged:textures/entity/<entity>.png` | resource id | Texture to draw with. |
| `idle` | `animation.<entity>.idle` | name | Idle animation. |
| `walk` | `animation.<entity>.walk` | name | Walk animation. |
| `walk_speed` | `0` | blocks/s | Ground speed the walk cycle was animated for. The walk then plays faster or slower (0.25x to 4x) with the real speed so feet don't slide. `0` plays it at its own pace. |
| `walk_threshold` | `0.3` | blocks/s | Speed above which it walks rather than idles. |
| `walk_back`, `strafe_left`, `strafe_right`, `turn_left`, `turn_right` | `animation.<entity>.<key>` | name | The other movement animations (all optional). |
| `turn_speed` | `0` | degrees/s | Turning rate the turn animations were made for; they play faster or slower with the real one. `0` plays them at their own pace. |
| `turn_threshold` | `25` | degrees/s | Turning on the spot faster than this plays a turn. |
| `max_turn` | `0` (vanilla) | degrees/s | How fast the mob itself may turn, walking or standing; it slows down while facing off its way, so a slow turner makes wide arcs. Gameplay, read at startup (needs a restart); for mobs extending `RiggedMob`. |
| `blend_time` | `0.25` | seconds | Crossfade between animations. `0` switches at once. |
| `max_step` | entity step height | blocks | How far a foot's ground may be above or below and still move the body. Default matches the entity's step-height attribute, so legs reach what the body can step onto. |
| `reach_down` | `3` | blocks | How much further below `max_step` a foot over a drop looks for ground. |
| `body_smoothing` | `8` | 1/s | How fast the body height follows the feet. Higher is snappier, `0` is instant. |
| `foot_smoothing` | `12` | 1/s | How fast a foot follows the ground under it (block edges, ground appearing or vanishing). |
| `tilt_smoothing` | `6` | 1/s | How fast `*_tilt` bones follow the slope. |
| `plant_low` | `0.5` | pixels | A contact this high or lower above the animation's floor is fully planted. |
| `plant_high` | `3` | pixels | From this height up a contact is in the air and left to the animation. |
| `shadow_radius` | `0.8` | blocks | Shadow under the entity. |

Example (the plodder's):

```json
{
	"walk_speed": 1.25,
	"walk_threshold": 0.3,
	"blend_time": 0.25,
	"body_smoothing": 8,
	"plant_low": 0.5,
	"plant_high": 3,
	"shadow_radius": 0.9
}
```

### Export

| File | From |
|---|---|
| `assets/fogged/geo/entity/<entity>.geo.json` | File > Export > Export Bedrock Geometry |
| `assets/fogged/animations/entity/<entity>.animation.json` | Animate tab: save all animations to one file |
| `assets/fogged/textures/entity/<entity>.png` | the texture |
| `assets/fogged/rigs/<entity>.json` | tuning, written by hand |

All four reload with **F3+T**. A new entity then needs its class (extend
`RiggedMob`, naming its model, so step height and `max_turn` apply), one line
in `ModEntities` and one `RiggedRenderer` line in `FoggedClient`.

The game log has one line per model saying what the rig found
(`[rig] fogged:plodder: 15 bones, legs [fl, fr, bl, br], ...`) and a warning
for anything half-made: a leg missing a bone, a contact outside its leg, a
`ground` bone with something in it, an animation naming a bone the model
doesn't have, or a missing idle/walk.

Two test creatures are working examples; open their `.geo.json` in Blockbench
to see the setup:

- `plodder` (`/summon fogged:plodder`): small, four long legs, trotting gait.
- `trudger` (`/summon fogged:trudger`): sauropod scale, about four blocks to the
  hip, one-bone pillar legs, long neck and tail, and a slow vanilla-style walk
  (legs swing from the hip) that always keeps three feet down.

### Circling (entity tags)

Any pathfinding mob, from this mod or any other, circles the nearest player
when it carries a circle tag in its `Tags` list:

```
/summon fogged:plodder ~ ~ ~ {Tags:["fogged.circle_r_f"]}
/tag @e[type=fogged:plodder,limit=1,sort=nearest] add fogged.circle_l
```

The tag is `fogged.circle`, then optionally `_l` or `_r`, then optionally `_f`:

| Part | Meaning |
|---|---|
| `fogged.circle` | Circle the player, walking the circle facing where it goes. |
| `_l` / `_r` | Go to its left / right, as the mob sees it facing the player (right is counter-clockwise seen from above). Without one, each mob picks a side and keeps it, and turns back if it can't get round something. |
| `_f` | Face the player and step sideways instead. |

So: `fogged.circle`, `fogged.circle_l`, `fogged.circle_r`, `fogged.circle_f`,
`fogged.circle_l_f`, `fogged.circle_r_f`. The tag is saved with the mob.

**Stick:** while the circled player holds a stick in either hand, the mob
stops circling and holds the place it had relative to them (player steps
left, it steps left). A `_f` mob keeps looking at them. Put the stick away and
it circles on from where it is.

Fixed values (in `CirclePlayerGoal`):

| | Value |
|---|---|
| Circle radius | 4 blocks + 2 x the mob's width (plodder 6.4) |
| Starts circling within | 16 blocks of a player |
| Gives up beyond | 24 blocks (48 while following a stick) |
| Walking speed | 1.0 x the mob's speed; turns at most 6 degrees a tick |
| Sideways speed (`_f`) | 0.6 x the mob's speed |
| Detour round an obstacle | pathfinds 6 blocks round the circle, up to 3 seconds |
| Following a stick | catches up at 1.5 x speed when over 4 blocks off; pathfinds when over 6; stands still within 0.3 |
| Priority | above wandering and looking about, below most attack goals (a mob with a target still attacks) |
