# Compatibility and release checks

I support Minecraft 1.21 through 1.21.11 in LifeLink 1.0.1 with one JAR.
I build against 1.21 with Java 21, Fabric Loader 0.18.4, Loom 1.14.10, and Gradle 9.2.1.
The separate 26.x build covers 26.1 through 26.3 with Java 25, Fabric Loader
0.19.5, Loom 1.18.2 and Gradle 9.8.0. Both artifacts retain the mod version 1.0.1.

## Minecraft 26.x

Minecraft 26.1 removed obfuscation and Fabric renamed affected APIs, so the 1.21
JAR needs recompilation to run on 26.x. I keep two build targets with small Fabric
adapters and shared round/save logic in `common/`. See
[Fabric's 26.1 porting notes](https://fabricmc.net/2026/03/14/261.html).

The 26.x target compiles against 26.1. Its audit excludes LifeLink source and loads
the same JAR directly, with no remapping or dependency overrides. Each target uses
its matching Fabric API. Hash checks ensure the artifact stays unchanged.

| Minecraft | Pinned Fabric API |
| --- | --- |
| 26.1 | 0.145.1+26.1 |
| 26.1.1 | 0.145.4+26.1.1 |
| 26.1.2 | 0.155.3+26.1.2 |
| 26.2 | 0.161.0+26.2 |
| 26.3 | 0.161.0+26.3 |

The runner and version pins are under `mc26/compatibility/`. Test logs are under
`mc26/build/compatibility/`. These checks cover callback registration and mocked
server/player behavior. Live multiplayer testing of the new 26.x build is still pending.

Verified on 2026-10-01: 17 checks passed on each of the five releases, for 85 total.
The exact artifact hash and results are in `mc26/compatibility/verified-results.json`.

Install one LifeLink JAR per server, and choose Fabric API for the exact Minecraft
version. On Modrinth, the 26.x file belongs in a separate version entry with the
26.x game versions selected; the mod's own version stays 1.0.1.

## Minecraft 1.21 automated verification

Verified on 2026-10-01: all 204 checks passed with no skipped tests. The matrix
checked the same final JAR on all twelve releases without dependency overrides.
I repeated this audit after moving the round and save logic into `common/`.
`compatibility/verified-results.json` records the rebuilt artifact's hash and
retains the original published 1.0.1 hash separately.

| Minecraft | Pinned Fabric API | Release JAR audit |
| --- | --- | --- |
| 1.21 | 0.102.0+1.21 | 17 passed |
| 1.21.1 | 0.116.17+1.21.1 | 17 passed |
| 1.21.2 | 0.106.1+1.21.2 | 17 passed |
| 1.21.3 | 0.114.1+1.21.3 | 17 passed |
| 1.21.4 | 0.119.4+1.21.4 | 17 passed |
| 1.21.5 | 0.119.5+1.21.5 | 17 passed |
| 1.21.6 | 0.128.2+1.21.6 | 17 passed |
| 1.21.7 | 0.129.0+1.21.7 | 17 passed |
| 1.21.8 | 0.136.1+1.21.8 | 17 passed |
| 1.21.9 | 0.134.1+1.21.9 | 17 passed |
| 1.21.10 | 0.138.4+1.21.10 | 17 passed |
| 1.21.11 | 0.141.6+1.21.11 | 17 passed |

The audit runs existing behavior tests against the same release JAR on each target,
with LifeLink's main source excluded. Loom remaps the JAR into each target's
Minecraft development mappings. Fabric loads it using its real dependency bounds;
final checks use no dependency overrides. The original JAR hash must stay unchanged.
Reports and logs are under `build/compatibility/<minecraft>/`; recorded results and
the artifact SHA-256 are in `compatibility/verified-results.json`.

These checks cover Fabric callback registration and mocked server/player behavior.
I tested live gameplay on Minecraft 1.21 and 1.21.11, and both worked.
The 1.21 test used my earlier backport build. The 1.21.11 test used the expanded
build before I tidied up the branding. I haven't completed the full multiplayer
checklist on every version.
Fabric API must match the server's Minecraft version; the shared artifact is LifeLink.

## Keeping one JAR across API changes

`PlayerDeathCompat` bridges Minecraft's `kill()` and `kill(ServerWorld)` signatures.
`CommandPermissionCompat` keeps the operator level 2 and world-owner checks across
three API changes: the permission interface moved; newer Minecraft replaces
numeric permission checks with a permission predicate; and `MinecraftServer.isHost`
now accepts a player configuration entry instead of a GameProfile. All checks call
Minecraft's own implementation. Handles are resolved once and cached, using Fabric's
MappingResolver so they work in both development and production namespaces.

Death handling obtains the server through the player's ServerWorld because newer
versions removed the direct player accessor. The world accessor keeps the same
intermediary signature despite its Yarn name changing across versions.

The newer permission check requests `Permission.Level(GAMEMASTERS)`, equivalent to
operator level 2. The world-owner check constructs `PlayerConfigEntry` from the
player's GameProfile where required. Tests check the exact required permission,
ordinary-player denial, world-owner access and non-player-source denial on each target.

The API signatures are documented in Yarn:
[legacy permissions](https://maven.fabricmc.net/docs/yarn-1.21+build.9/net/minecraft/server/command/ServerCommandSource.html),
[new permissions](https://maven.fabricmc.net/docs/yarn-1.21.11+build.6/net/minecraft/server/command/ServerCommandSource.html),
[player configuration entries](https://maven.fabricmc.net/docs/yarn-1.21.11+build.6/net/minecraft/server/PlayerConfigEntry.html),
and [server world access](https://maven.fabricmc.net/docs/yarn-1.21.11+build.6/net/minecraft/server/network/ServerPlayerEntity.html).
I use the toolchain recommended in [Fabric's 1.21.11 guidance](https://fabricmc.net/2025/12/05/12111.html).
Compiling against 1.21 also catches accidental direct use of newer APIs.

## Maintaining the range

1. Build the release with `.\gradlew.bat clean build` on Windows or
   `bash ./gradlew clean build` on Linux or macOS.
2. Run `./compatibility/Test-Compatibility.ps1` in PowerShell 7 on Windows. This builds once,
   then checks the same artifact across the pinned matrix. Use `-Jar` to audit an
   existing release without rebuilding it.
3. When investigating another version, add its matching dependencies to
   `compatibility/versions.json`. `-AllowUnsupportedVersions` permits a private
   diagnostic audit outside the release's declared bounds.
4. Run the multiplayer checklist on each additional target, including LAN and
   dedicated server restarts. Record dependency versions and artifact hashes.
5. Widen `minecraft_supported_versions` only to a tested range, rebuild, and repeat
   the final JAR audit without diagnostic overrides. Keep a bounded range.
6. Split artifacts only when an observed incompatibility cannot be handled simply
   in the shared code. Recompiling one artifact per target is not same-JAR evidence.

## Multiplayer checklist

Use at least two connected players and a third account that joins after death.

- Inactive: deaths have no linked effect.
- Start, natural deaths off and PvP deaths on: melee and projectile PvP deaths trigger once; ordinary
  fall/lava/mob deaths do not. Check environmental deaths with recent PvP kill credit.
- Natural deaths on (default): fall, lava, mobs and `/kill` trigger once; totem saves do not. PvP deaths are ignored unless enabled separately.
- The original victim is not killed twice; the first-death name stays correct.
- Players across dimensions die and enter Spectator. Title and subtitle both display.
- New joiners and reconnecting players enter the dead state while it is active.
- Clicking Respawn keeps a linked dead player in Spectator.
- Revive works both before and after clicking Respawn, restores Survival, and permits
  a fresh linked death. Include a disconnected player who rejoins after revive.
- Stop prevents further propagation without reviving; start clears the old death.
- Restart preserves the active state, first death and natural-death and PvP toggles. Check integrated-server world
  switching if supporting local play as well as dedicated servers.

Current automated tests cover command dispatch, inactive/natural/PvP filtering,
recursive death guards, late joins, respawn mode, revival using replacement player
objects, iteration over a changing player list, and lifecycle resets. They mock
Minecraft entities; they do not simulate real combat, packets or client respawns.

## Later CI

After multiplayer verification, add a build job that uploads one remapped JAR,
then a matrix job that downloads that artifact and runs it on each claimed version.
Use server integration/GameTests for game behavior and retain logs and JAR hashes.
Release publishing should depend on all declared versions passing.
