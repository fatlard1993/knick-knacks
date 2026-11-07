# Knick Knacks - Development Guide

For what the mod is and how it plays, see [README.md](README.md).

## Installation

Install server-side. Vanilla clients need nothing. Version targets live in `gradle.properties`
(Minecraft, loader, Fabric API) and `fabric.mod.json` (Java). block-tip is optional: it is
compiled against when `../block-tip` is checked out and built, and never bundled.

## Key Files

| File | Responsibility |
|------|---------------|
| `Main.java` | Entry point; the callbacks for setting down, hitting and right-clicking |
| `SetDown.java` | The gesture: vanilla's own off-hand click first, then setting down, then refusing the off-hand clicks a vanilla client sends behind it |
| `Knack.java` | What can be set down, and a thing set down: placing it on a table, a wall or a ceiling, piling it, finding a free spot, turning it, picking it up, letting it fall or float |
| `Burst.java` | A bottle broken by a projectile, a rocket launched by a pulse |
| `Aura.java` | Light from glowing knacks, and motes |
| `Glue.java` | Kragle's glue on a knack, found by the bottle's id rather than compiled against Kragle |
| `Bites.java` | Eating and drinking what is set down, a bite at a time where the item has pictures of its bites |
| `Reading.java` | A book set down, opened on the lectern's screen |
| `mixin/InteractionMixin.java` | A knack's tick: a rocket's fuse, motes, and once a second whether it still has something behind it; and letting a bottle be hit by projectiles |
| `mixin/ProjectileMixin.java` | Breaking a bottle a projectile hits, and letting the projectile fly on |
| `integration/KnackTips.java` | Naming a knack on block-tip's card, loaded only with block-tip present |
| `generate_icon.py` | The mod menu icon, drawn from vanilla textures |

## How A Knack Is Kept

One vanilla interaction, half a block across, that a player hits and right-clicks, and one item
display per item in the pile, all at exactly the same point on the surface. Everything carries the
`knick_knacks` tag. Each display holds its one item and carries `knick_knacks_layer_<n>`, its
`knick_knacks_turn_<n>` sixteenth of a turn, and `knick_knacks_bites_<n>` once it has been bitten.
A layer's height and its small twist (taken from its own UUID) are in its transformation, so every
display stays on the shared point, and the pile and its interaction find each other by position.
Nothing is saved anywhere else.

A knack on a wall or a ceiling carries `knick_knacks_face_<direction>` on the interaction and every
display. On a wall its point is a hair out from the face, so the display is lit by the air in front,
and half its height below its middle, because an interaction grows up from its position; under a
ceiling it is its whole height below.

The interaction also carries what is true of the top of its pile, rewritten whenever the pile
changes, because these are asked about every tick or by every passing projectile:
`knick_knacks_brittle`, `knick_knacks_fuse` (and `knick_knacks_powered` for the rising edge),
`knick_knacks_glow`, `knick_knacks_motes_<kind>`. `knick_knacks_floating` marks one left by a creative
break, and `knick_knacks_kragled_<uuid>` with `knick_knacks_kragled_by_<name>` marks the glue.

## Bites

An item has bite stages when its mod ships models at `assets/<namespace>/models/item/eating/<path>_<n>.json`,
counted from 0 on the classpath. A bitten display is drawn with a `custom_model_data` string of
`bites_<n>`, and the item's definition is expected to select on it; Let's Cook's `generate_models.py`
writes exactly that beside its in-hand eating stages. An item already carrying custom model data of
its own is not given stages, so its look is never overwritten.

## Tests

`src/gametest`: `SetDownAndPickUp` drives everything through the server's own click and packet
paths: four to a block and the fifth refused, the nudge to a free corner, a candle placed as itself,
a poppy planted in grass and standing on a table, vanilla going first (bone meal on grass, a carrot in farmland, an axe on a log, a painting) and setting
down where it does nothing, a bucket never taken, nothing set down under water, the off hand's use
swallowed, four to a wall and turning there, four hung from a ceiling and falling with it, piles to eight and the spill-over beside them, turning,
both hits, eating in one bite and in stages, the bowl and the bottle left behind, a full player refused,
reading and taking a book, resting on a slab, falling when the table or wall goes, floating after a
creative break and falling again once rebuilt, light from glowstone dust, a lingering potion broken by
a snowball into its cloud, a glued bottle neither broken nor falling, and rockets launched one a pulse.
Glue is tested by its tags, since Kragle is not in the run. The cookie is given two
bite stages for the run by models in the test's own resources. It ends by setting a table and a wall, and a
larder of flowers and hanging meat, and taking pictures of them into the run folder, to look at by eye. It opens a window:

```
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest
```

## Performance

`src/gametest/.../Performance.java` runs only with `-Pbench`. It times the overworld's tick with
2048 knacks in force-loaded chunks out of the client's sight, against the same number of vanilla
interactions and displays standing in the same places:

```
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest -Pbench
```

What keeps it cheap:

- **Staggered.** The once-a-second support check, the motes and the fuse are timed off
  `tickCount + id`, so knacks placed or loaded together never all do their work on one tick.
  Before this, 2048 knacks loaded at once made one 70 ms tick every second.
- **The support check looks at blocks, not entities.** It asks only the block behind, with a
  shortcut for full blocks; the pile is looked up only when that block is gone, and once every ten
  seconds to catch an interaction whose displays were killed out from under it.
- **Rockets' fuses are read every other tick**, which still catches an observer's two-tick pulse.
  They are the one knack with a measurable cost of its own: about 3.5 ms for 2048 piles.
- **Motes are not sent when no player is within particle range.**
- **Everything else is a tag.** What the top of a pile is (brittle, fused, glowing, giving off
  motes) is written on the interaction when the pile changes, so nothing per tick looks the pile up.

On a typical run bread knacks come within noise of the vanilla entities they are made of (both
around 6 to 8 ms over an empty world on the development machine); the displays are most of the
cost, and they are vanilla's. A pile of eight is eight displays.
