# Changelog

## 1.0.1

### Minecraft 26.x build

I've ported LifeLink to Minecraft 26.1 through 26.3, including 26.1.1 and 26.1.2.
The gameplay and release number stay the same.

- Added `lifelink-1.0.1-mc26.jar` for the 26.x family, using Java 25 and official Minecraft names.
- Shared death rules and saved-world state between both Minecraft builds.
- Kept the 1.21 JAR and its Java 21 toolchain available.
- Added a repeatable audit of the same 26.x JAR across every supported release.

### Minecraft 1.21 build

I've updated LifeLink to cover the full 1.21 range and fixed the shared-death state
after restarting or reopening a world.

- Added support for Minecraft 1.21 through 1.21.11 with one Fabric JAR.
- Saved shared deaths and settings across dedicated server restarts and LAN world reloads.
- Fixed joining and rejoining players returning to Survival after a shared death. They now die, enter Spectator, and receive the original death message and title.
- Restricted all LifeLink commands to operator players or the world owner.
- Enabled natural deaths by default in new worlds; enable PvP deaths with `/lifelink pvpdeaths`.
- Preserved existing saved death-toggle preferences when upgrading.
- Included the LifeLink icon in every build.
- Updated the Java 21 build tooling and added a repeatable compatibility audit.
