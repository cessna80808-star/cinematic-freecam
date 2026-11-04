# Cinecam

Client-side cinematic cameras for **Minecraft Java 26.2** (the "Chaos Cubed" drop).

Cinecam takes over your local camera and flies it around the player along nine
different previs-style shots — perfect for trailers, screenshots, timelapses and
machinima. It snaps cleanly to AOTV-style teleports, collides against walls every
frame, and never touches the server: your movement, hitboxes and gameplay are
exactly the same, only your view changes.

## Shots

| Shot | What it does |
| --- | --- |
| **Dynamic** | Reads what you are doing and adapts: a shoulder view while mining, a target-aware combat view, a stable chase camera at 400%+ speed, and a rear pursuit during teleport chains. Default shot. |
| **Orbit** | A slow, perfectly steady circle around the player. The locked-off establishing shot. |
| **Spiral** | Circles while climbing and sinking, with the radius breathing in and out. |
| **Crane** | A jib-style orbit that starts high, descends and pushes in, then rises again. |
| **Dolly** | Orbits while slowly pushing in and pulling out, with a gentle Dutch roll. |
| **Flyby** | A wide low-to-high loop with a close pass. |
| **Top Down** | A high overhead orbit that looks straight down on the player. |
| **Pendulum** | A slow arc that swings past the player and back the other way. |
| **Vertigo** | A dolly-zoom orbit: the camera pushes in while the lens widens. |

Every shot eases in and out over `introSeconds` instead of snapping, looks at the
player, and follows them smoothly. Ordinary movement keeps a tight, capped lag;
a detected teleport snaps the orbit centre to the player immediately and settles
the camera into a rear chase based on the jump direction, because easing across
an AOTV jump is what lets the player leave the frame. Handheld micro-shake is on
by default; set `shake` to `0` for a locked-off camera.

## Dynamic mode

The **Dynamic** shot (the default) watches the player and blends between three
framings:

- **Mining** — when you swing at a block, the camera slides to shoulder height,
  puts you on the right third of the frame, and looks past your back toward the
  block. The side offset and collision solver keep it on your side of the wall.
- **Combat** — when you are hit or strike an entity, the camera locks onto the
  nearest valid enemy (preferring the crosshair), pulls back into a rear
  three-quarter view, and frames both you and the target. Target changes are
  smoothed, and the collision solver keeps the shot out of nearby walls.
- **Exploring** — when you are slow it drifts in a gentle orbit. As speed builds
  it blends into a stable chase camera behind your look direction, so 400%+
  SkyBlock movement stays readable instead of orbiting into terrain.
- **Teleporting** — a detected jump switches to a chase behind the jump
  direction, not the view direction, and stays there long enough to frame the
  next jump in a chain.

The sub-framings cross-fade with smoothed weights, so a fight starting mid-mine
does not snap. At speed the follow anchor is capped to roughly 0.35 blocks of
lag, the look target leads the player by up to two blocks, and the lens opens
by up to 3°, which keeps the player composed when moving at several times
vanilla sprint speed.

Press `F4` or run `/cinecam dynamic` to turn the adaptive framing off. Classic
mode keeps the gentle Dynamic orbit and ignores mining and combat adaptation;
the high-speed stabilizer below still applies so fast travel stays readable.
Geometry avoidance is separate: set `avoidGeometry` to `false` as well if you
want a completely unrestricted free camera.

## Teleport pursuit

AOTV and similar abilities do not look like normal movement: the player can
cross the whole frame between two position updates, often with no matching
delta movement. Cinecam tracks the raw local-player position rather than the
smoothed camera anchor. A jump of at least 2.5 blocks at 30 blocks/s or faster
counts as a teleport.

On detection the orbit centre snaps to the raw position and the rig is rebuilt
at once, so the player does not leave the frame while the camera catches up.
The Dynamic shot switches to a rear chase based on the measured jump direction,
opens its boom with the jump length and leads the focus along the travel path.
Pursuit stays active for 1.6 seconds and every jump in a chain refreshes it, so
the camera is already behind the next AOTV hop instead of chasing the last one.
Every shot uses the jump direction to enter the same stable rear chase; the
Dynamic shot keeps its additional predictive framing.

## Fast movement

At high horizontal speed every shot eases toward a stable rear chase and damps
its roll and handheld shake. Dynamic uses its own speed chase; Orbit, Flyby and
the other shots keep their authored motion at normal speed and give way to the
predictable chase only while you are moving fast. The authored shot returns
smoothly as speed drops. This applies in classic mode too.

## Geometry-aware camera

Cinecam searches a full circle of angles and several heights around the subject,
testing both line of sight and a camera-sized collision volume. It keeps full
boom distance whenever possible and swings around a wall rather than
immediately pushing in; only if no open angle exists does it shorten. The chosen
rig and the outward boom recovery ease, but moving into geometry is immediate:
every rendered frame is clipped against blocks and then checked with a
camera-sized volume. When a wall clears, the boom eases back out instead of
popping. The rig only re-searches when its current safe target becomes blocked,
and prefers to stay on the same side, so it does not flip between corners and
bounce. If the aim point itself is blocked, the clamp retreats toward the
player's eye. Set `avoidGeometry` to `false` only if you deliberately want a
free camera.

While a shot is engaged, Cinecam also draws the local player through occluding
terrain using a depth-test-free copy of the vanilla entity render type. This is
deliberately a player-only pass rather than section deletion: chunk sections are
16x16x16, so hiding a section at the normal six-block camera distance would
remove the floor and walls around the player as well. Toggle it with
`/freecam` under **See-through**, the `seeThroughPlayer` config field, or the
studio button. The base player model is affected; armour layers and name tags
still use vanilla depth sorting.

## Smooth emerge and return

Toggling the camera on starts at the player's eyes and pulls out into the shot;
toggling it off flies back into the player's eyes before first person is
restored. If you started in third person, the outro returns to that third-person
camera instead. After the blend reaches the vanilla pose, Cinecam holds it for a
short settle and blends the saved perspective in before releasing, so the
handoff has no snap. The HUD returns during that settle instead of popping.

## Full frame

Letterbox is off by default, so the camera uses the whole window. Run
`/cinecam letterbox` to toggle 2.39:1 bars. Existing configs are migrated to off
once; `letterbox` can be set manually, and `letterboxRatio` controls the bar
shape when it is enabled.

## Cuts and director mode

Changing shots dips through black over a third of a second for a clean cut
(`cutDip`). Director mode automatically moves to the next shot every
`directorSeconds`, using the same dip, so you can leave it running for a
hands-free montage. Press `F9` to freeze a shot on a frame and `F10` to toggle
director mode.

## Requirements

- Minecraft Java Edition **26.2**
- **Fabric Loader 0.20.1+**
- **Fabric API 0.161.0+26.2**
- **Java 25**

## Install

1. Install Fabric Loader for 26.2.
2. Remove any older Cinecam jar, then drop
   [Fabric API](https://modrinth.com/mod/fabric-api) and `cinecam-1.10.0.jar`
   into `.minecraft/mods`.
3. Launch the game. Cinecam is client-only, so it also works on servers that
   don't have it installed (as long as they allow client mods).

## Studio

Run `/freecam` (or `/cinecam gui`) to open the **Cinecam Studio**, a three-column
editing desk designed to stay readable even at Minecraft's smaller automatic GUI
scales. The left rail lists all nine shots with small path glyphs; click a row to
make it the active shot, and the amber mark follows the selection. The centre
monitor draws that shot's path at full size with viewfinder gate marks, the
player at the centre, and a moving camera marker. Both previews are 2D path
diagrams, not live 3D renders, so the studio stays lightweight.

The setup deck on the right groups every control into four tabs:

- **Lens** — camera on/off, adaptive/classic framing, playback speed, FOV, orbit
  distance, height and aim height.
- **Motion** — wall clearance, wall avoidance, intro time, handheld shake and
  see-through player.
- **Output** — full-frame/letterbox, HUD visibility and director mode.
- **Manual** — turn manual pan/zoom on or off, center the offset, and see the
  WASD/scroll reminder.

Changes apply immediately and are saved to `config/cinecam.json`; the game keeps
running behind the studio. The status line shows whether the camera is live,
which framing mode is active, and whether manual framing is on.

## Manual pan and zoom

Manual framing is a separate layer over the shots, not a tenth shot. Press `V`
or run `/cinecam manual` to toggle it while the camera is engaged:

- `W`/`S` push the aim into or out of the scene; `A`/`D` pan it across the frame.
- The mouse wheel zooms the lens. While manual framing is active the wheel is
  consumed, so it does not change your hotbar slot.
- The Manual tab in the studio has the same toggle and a **Center offset**
  button. Turning manual off eases the aim and zoom back to the active shot.

Manual mode is additive: it does not lock player movement, so WASD still moves
you normally. Stand still for a clean pan. The mode is session-only and is not
written to `config/cinecam.json`; switching shots keeps the current offset until
you center it or turn manual off.

## Controls

| Key | Action |
| --- | --- |
| `F6` | Toggle the cinematic camera |
| `F7` | Cycle to the next shot |
| `F8` | Cycle speed (0.5x, 0.75x, 1x, 1.5x, 2x) |
| `F9` | Freeze / resume the shot |
| `F10` | Toggle auto-director |
| `F4` | Toggle dynamic / classic camera |
| `V` | Toggle manual pan and wheel zoom |

All seven can be rebound in **Options → Controls → Cinecam**.

The shortcuts are polled on both the client tick and the camera render pass, and
read raw GLFW state, so they keep working when an external client such as Nebula
leaves the mouse ungrabbed or a GUI screen owns input. Render-pass polling also
lets a toggle start its blend while a pausing screen has the mouse released.
Minecraft's normal key handling is still used, so rebinding works as usual.

The current shot name appears briefly in the corner whenever it changes.

## Commands

`/freecam` and `/cinecam` commands are client-side only and work in singleplayer
and on servers:

```
/freecam                    open the Cinecam Studio
/cinecam                    show status and a short hint
/cinecam help               list the primary commands
/cinecam gui                open the studio (compatibility alias)
/cinecam on|off             engage or release the camera
/cinecam shot [name|next]   choose a shot or advance to the next one
/cinecam manual [on|off]    toggle manual pan and wheel zoom
/cinecam speed              cycle playback speed
/cinecam reload             re-read config/cinecam.json
```

The advanced `toggle`, `next`, `freeze`, `director`, `dynamic` and `letterbox`
commands still work as compatibility aliases, but the studio and keybinds are
the shorter path.

## Configuration

`config/cinecam.json` is created on first launch:

| Field | Default | Meaning |
| --- | --- | --- |
| `shot` | `"DYNAMIC"` | Active shot. |
| `dynamic` | `true` | Adaptive mining/combat framing. `false` is classic, predictable framing. |
| `speed` | `1.0` | Base playback speed (the F8 key multiplies on top of this). |
| `radius` | `6.0` | Orbit radius in blocks. |
| `height` | `2.4` | Camera height above the player's feet. |
| `targetHeight` | `1.2` | Height of the point the camera aims at. |
| `follow` | `true` | Follow the player. `false` anchors the shot where it started. |
| `followRate` | `4.0` | Minimum tracking rate. High speed automatically tightens this to cap the lag. |
| `introSeconds` | `1.8` | Emerge / return duration. |
| `fov` | `55` | Field of view while fully engaged. |
| `letterbox` | `false` | Draw 2.39:1 cinematic bars. Off uses the full frame. |
| `letterboxRatio` | `2.39` | Aspect ratio for the bars. |
| `hideHud` | `true` | Hide the hotbar, chat, crosshair, etc. |
| `roll` | `2.5` | Maximum Dutch-angle roll in degrees. |
| `shake` | `0.02` | Handheld micro-shake in blocks (`0` disables). |
| `avoidGeometry` | `true` | Clip the camera against walls, floors and ceilings. |
| `seeThroughPlayer` | `true` | Draw the local player through terrain between the freecam and the subject. |
| `cameraMargin` | `0.35` | Gap kept between the camera and the surface it clipped against. |
| `cutDip` | `true` | Dip through black when the shot changes. |
| `directorSeconds` | `20.0` | Seconds between automatic shot changes (`0` disables). |
| `director` | `false` | Whether auto-director is enabled. |

Edit the file and run `/cinecam reload`, or restart the game.

## Build from source

Cinecam targets 26.2 and needs **JDK 25**:

```bash
./gradlew build
```

The mod jar lands in `build/libs/cinecam-1.10.0.jar`. For development,
`./gradlew runClient` launches a Fabric dev client with the mod loaded.

> Minecraft 26.2 ships unobfuscated, so this project has **no mappings
> dependency** and no remap step for the game itself.

## Updating to 26.3

26.3 ("Wilderness Bound") is already out. The render pipeline and camera APIs
this mod uses are unchanged between 26.2 and 26.3, so the port is a version bump
in `gradle.properties`:

```properties
minecraft_version=26.3
fabric_api_version=0.165.0+26.3
loader_version=0.20.1
loom_version=1.19-SNAPSHOT
java_version=25
```

Then run `./gradlew build` and re-check the mixin targets (`Camera#update`,
`Camera#calculateFov`, `Hud#extractRenderState`, `MouseHandler#onScroll`) if
Minecraft changes the camera, HUD or mouse pipeline again. You can also override the versions from the command line
without editing the file:

```bash
./gradlew build -Pminecraft_version=26.3 -Pfabric_api_version=0.165.0+26.3
```

## How it works

- `CameraMixin` runs at the tail of `Camera#update`, after vanilla has placed the
  camera, and replaces the position, yaw, pitch and roll before the camera render
  state is extracted.
- During the intro it interpolates from the player's eye pose to the shot; during
  the outro it interpolates back to the eyes (or to the vanilla third-person
  camera if that is the perspective being restored).
- `CineCamera#pose` computes each shot, blends the Dynamic base/chase, mining
  and combat framings, then runs the open-angle camera rig and clearance
  smoothing.
- `CameraMixin` also overrides `Camera#calculateFov` so the custom FOV, the
  Vertigo lens offset and manual zoom reach the projection matrix.
- Manual framing is applied after the shot rig: `CineCamera` shifts only the
  aim target and adds a lens FOV offset, so the existing wall clamp still
  governs the camera body. `MouseHandlerMixin` consumes the wheel only while
  manual framing is live, preventing hotbar changes during zoom.
- `HudMixin` cancels HUD extraction while engaged (setting the vanilla
  `guiRenderState.isHudHidden` flag too, which also hides the hand and 3D
  crosshair) and submits the animated bars and dip-to-black through the new 26.2
  `GuiGraphicsExtractor`.
- The camera is forced into third person while engaged so the player model is
  visible; your original perspective is restored when the shot ends.
- `CinecamScreen` is the `/freecam` studio: a still shot rail, a live path
  monitor and a tabbed setup deck, all writing through the same config the camera
  reads.

## Limitations

- The final camera body is hard-clamped out of collision shapes, but a block
  exactly at the edge of the near plane or a wall between the camera and a
  different aim point can still occlude the view normally.
- Interactions still aim from the cinematic camera while engaged; use the freeze
  key for screenshots rather than mining with the cinematic camera running.
- Purely client-side: other players only ever see your normal view.
- See-through applies to the local player's base model. Armour layers and name
  tags can still be hidden by thick geometry.
- Manual pan is an aim offset, not a free-flying camera, and it does not suppress
  player movement: WASD also moves you while manual framing is on. Manual state
  is session-only and is not saved to the config file.

## License

MIT.
