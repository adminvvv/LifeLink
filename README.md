# LifeLink

LifeLink is my Fabric mod for shared deaths in Minecraft multiplayer. When one
player dies, everyone linked to them dies too.
One JAR supports Minecraft **1.21 through 1.21.11**.

## Install

Requires **Java 21**, **Fabric Loader 0.18.4+**, and **Fabric API 0.102.0+** built
for your Minecraft version. See [tested dependencies](docs/COMPATIBILITY.md).

Put `lifelink-1.0.1.jar` and Fabric API in the server's `mods` folder. Players do
not need LifeLink installed to join a dedicated server. For singleplayer or LAN,
install both mods locally so LifeLink can run on the world's integrated server.

Source: [adminvvv/LifeLink](https://github.com/adminvvv/LifeLink).
Release notes: [CHANGELOG.md](CHANGELOG.md).

## Commands

All commands require an operator player with permission level 2 or the world owner.

| Command | Behavior |
| --- | --- |
| `/lifelink start` | Enable linking and clear the previous shared death. |
| `/lifelink stop` | Disable linking and clear the shared death. Players remain in their current game mode. |
| `/lifelink revive` | Clear the shared death, respawn dead online players, and restore online players to Survival. Linking keeps its current setting. |
| `/lifelink naturaldeaths` | Toggle deaths without player kill credit. Defaults to on in new worlds. |
| `/lifelink pvpdeaths` | Toggle deaths with player kill credit. Defaults to off in new worlds. |

While linking is active, the first qualifying player death kills linked players,
puts them in Spectator, and displays the first victim's name. Joining and rejoining
players receive the same death message and title while the shared death is active.
Respawning players remain in Spectator until the shared death is cleared.

PvP follows vanilla player kill credit, including arrows and deaths attributed to
recent player combat. Natural deaths and PvP deaths have independent toggles.
Totem saves do not trigger a shared death.

`start` does not revive existing spectators; use `revive` for that. Joining before
a shared death while linking is active restores Survival.

## Saved worlds

LifeLink saves its active setting, death toggles, and first victim in
`data/lifelink.json` inside the world folder. These settings survive dedicated
server restarts and leaving and reopening a LAN world.

`start`, `stop`, and `revive` clear the shared death while keeping both death
toggles. Older version 1 save files retain their natural-death setting and PvP
linking when upgraded. Use the toggle commands to change those settings.

## Build and test

Use a JDK 21 installation and the checked-in Gradle Wrapper:

```powershell
.\gradlew.bat clean build
```

On Linux or macOS, run `bash ./gradlew clean build`. The wrapper pins Gradle 9.2.1 with
a distribution checksum. Fabric Loom is pinned to 1.14.10.

The distributable is `build/libs/lifelink-1.0.1.jar`. The `-sources.jar` is for
developers. Test reports are under `build/reports/tests/test/`.

To check the same release JAR across every supported version, use PowerShell 7:

```powershell
.\compatibility\Test-Compatibility.ps1 -Jar build/libs/lifelink-1.0.1.jar
```

The [compatibility report](docs/COMPATIBILITY.md) describes the automated checks
and live multiplayer checklist. Version pins live in `compatibility/versions.json`.

For a development server, run `.\gradlew.bat runServer --args=nogui` on Windows
or `bash ./gradlew runServer --args=nogui` on Linux or macOS. Review Minecraft's
EULA in the generated `run/eula.txt` before accepting it. Development server files
and worlds stay in the ignored `run/` directory.

## Maintenance

- `gradle.properties`: dependencies, release version, and supported Minecraft range.
- `build.gradle`: Java toolchain, tests, resource expansion, and packaging.
- `src/main/resources/fabric.mod.json`: mod identity and runtime requirements.
- `src/main/java/io/github/adminvvv/lifelink/`: shared logic and compatibility helpers.
- `src/test/java/io/github/adminvvv/lifelink/`: behavior tests.
- `compatibility/`: pinned version matrix and release JAR audit runner.

I use Fabric events for deaths, joins, respawns, and server lifecycle changes.
The compatibility helpers handle changes to Minecraft's kill methods and command
permissions. I build against Minecraft 1.21 and check the same JAR on every
supported version.

Licensed under MIT. See [LICENSE](LICENSE). The license and mod icon are included
in every release JAR.
