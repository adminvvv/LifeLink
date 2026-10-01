# Changelog

## 1.0.1

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
