# Cinecam

Client-side cinematic cameras for **Minecraft Java 26.2** (the "Chaos Cubed" drop).

Cinecam takes over your local camera and flies it around the player along ten
different previs-style shots — perfect for trailers, screenshots, timelapses and
machinima. It never touches the server: your movement, hitboxes and gameplay are
exactly the same, only your view changes.

## Shots

| Shot | What it does |
| --- | --- |
| **Dynamic** | Reads what you are doing and adapts: behind the shoulder while mining, wider and higher in combat, a trailing orbit while exploring. Default shot. |
| **Orbit** | A slow, perfectly steady circle around the player. The locked-off establishing shot. |
| **Spiral** | Circles while climbing and sinking, with the radius breathing in and out. |
| **Crane** | A jib-style orbit that starts high, descends and pushes in, then rises again. |
| **Dolly** | Orbits while slowly pushing in and pulling out, with a gentle Dutch roll. |
| **Flyby** | A wide low-to-high pass that sweeps past the player and loops (the loop point is a deliberate cut). |
| **Figure 8** | A lemniscate that loops in front of and behind the player. |
| **Top Down** | A high overhead orbit that looks straight down on the player. |
| **Pendulum** | A slow arc that swings past the player and back the other way. |
| **Vertigo** | A dolly-zoom orbit: the camera pushes in while the lens widens. |

Every shot eases in and out over `introSeconds` instead of snapping, looks at the
player, and follows them with a small exponential lag so walking doesn't jitter
the frame. Handheld micro-shake is on by default; set `shake` to `0` for a
locked-off camera.

## Dynamic something that reacts

The **Dynamic** shot (the default) watches the player and blends between three
framings:

- **Mining** — when you swing at a block, the camera slides behind your shoulder
  and looks at your back, keeping the face you are mining on the far side of you.
  It stays on your side of the wall instead of clipping into it.
- **Combat** — when you are hit or strike an entity, the camera pulls back into a
  wide rear three-quarter view, with a slow sway, and the collision solver keeps
  it clear of nearby walls.
- **Exploring** — otherwise it drifts in a gentle orbit whose radius expands with
  your movement speed, so sprinting opens the frame up.

The sub-framings cross-fade with smoothed weights, so a fight starting mid-mine
does not snap.

## Geometry-aware camera

Every shot is clipped against block collision from the look-at point out to the
camera. If a wall, ceiling or floor is in the way, the camera snaps to the near
side of it with a configurable margin, then eases back out when the view clears.
The camera is also nudged out of any block it still overlaps. This is what keeps
low orbits out of terrain and combat framing out of walls; disable it with
`avoidGeometry` if you want a true free camera.

## Smooth emerge and return

Toggling the camera on starts at the player's eyes and pulls out into the shot;
toggling it off flies back into the player's eyes before first person is
restored. If you started in third person, the outro returns to that third-person
camera instead, so changing perspective never snaps. The letterbox bars grow and
shrink with the same ease, and the FOV blends with it.

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
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and `cinecam-1.2.0.jar`
   into `.minecraft/mods`.
3. Launch the game. Cinecam is client-only, so it also works on servers that
   don't have it installed (as long as they allow client mods).

## Controls

| Key | Action |
| --- | --- |
| `F6` | Toggle the cinematic camera |
| `F7` | Cycle to the next shot |
| `F8` | Cycle speed (0.5x, 0.75x, 1x, 1.5x, 2x) |
| `F9` | Freeze / resume the shot |
| `F10` | Toggle auto-director |

All five can be rebound in **Options → Controls → Cinecam**.

The current shot name appears briefly in the corner whenever it changes.

## Commands

`/cinecam` commands are client-side only and work in singleplayer and on servers:

```
/cinecam            show help
/cinecam on         engage the camera
/cinecam off        release the camera
/cinecam toggle     toggle it
/cinecam shot orbit play a specific shot (dynamic, orbit, spiral, crane, dolly,
                    flyby, figure8, topdown, pendulum, vertigo)
/cinecam next       jump to the next shot
/cinecam freeze     freeze or resume the shot
/cinecam director   toggle auto-director
/cinecam speed      cycle speed
/cinecam reload     re-read config/cinecam.json
```

## Configuration

`config/cinecam.json` is created on first launch:

| Field | Default | Meaning |
| --- | --- | --- |
| `shot` | `"DYNAMIC"` | Active shot. |
| `speed` | `1.0` | Base playback speed (the F8 key multiplies on top of this). |
| `radius` | `6.0` | Orbit radius in blocks. |
| `height` | `2.4` | Camera height above the player's feet. |
| `targetHeight` | `1.2` | Height of the point the camera aims at. |
| `follow` | `true` | Follow the player. `false` anchors the shot where it started. |
| `followRate` | `4.0` | How tightly the camera tracks a moving player. |
| `introSeconds` | `1.8` | Emerge / return duration. |
| `fov` | `55` | Field of view while fully engaged. |
| `letterbox` | `true` | Draw 2.39:1 cinematic bars. |
| `letterboxRatio` | `2.39` | Aspect ratio for the bars. |
| `hideHud` | `true` | Hide the hotbar, chat, crosshair, etc. |
| `roll` | `2.5` | Maximum Dutch-angle roll in degrees. |
| `shake` | `0.02` | Handheld micro-shake in blocks (`0` disables). |
| `avoidGeometry` | `true` | Clip the camera against walls, floors and ceilings. |
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

The mod jar lands in `build/libs/cinecam-1.2.0.jar`. For development,
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
`Camera#calculateFov`, `Hud#extractRenderState`) if Minecraft changes the camera
or HUD pipeline again. You can also override the versions from the command line
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
- `CineCamera#pose` computes each shot, blends the Dynamic mining/combat
  framings, then runs the collision clip and distance smoothing.
- `CameraMixin` also overrides `Camera#calculateFov` so the custom FOV and the
  Vertigo lens offset reach the projection matrix.
- `HudMixin` cancels HUD extraction while engaged (setting the vanilla
  `guiRenderState.isHudHidden` flag too, which also hides the hand and 3D
  crosshair) and submits the animated bars and dip-to-black through the new 26.2
  `GuiGraphicsExtractor`.
- The camera is forced into third person while engaged so the player model is
  visible; your original perspective is restored when the shot ends.

## Limitations

- Collision uses a single ray from the player to the camera, so very thin gaps
  can still show a sliver of geometry at the frame edge.
- Interactions still aim from the cinematic camera while engaged; use the freeze
  key for screenshots rather than mining with the cinematic camera running.
- Flyby's loop is a hard cut (a new shot every 18 seconds) rather than a
  continuous orbit.
- Purely client-side: other players only ever see your normal view.

## License

MIT.
