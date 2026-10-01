# Release JAR compatibility audit

I use this runner to check one LifeLink JAR across all supported Minecraft versions.

Run from the repository root on Windows with PowerShell 7 and Java 21:

```powershell
.\compatibility\Test-Compatibility.ps1
```

The command builds the default release once and tests its JAR on every pinned
Minecraft version in `versions.json` (currently 1.21 through 1.21.11).
To test an existing artifact:

```powershell
.\compatibility\Test-Compatibility.ps1 -Jar build/libs/lifelink-1.0.1.jar
```

To select targets, pass `-Versions '1.21.4','1.21.3'`. Version pins live in
`versions.json`; release configuration stays in `gradle.properties`.

For each target, the runner supplies matching Minecraft, Yarn and Fabric API to
Loom. LifeLink's main source and resources are excluded. Loom remaps the supplied
release JAR into the target's development mappings, as it does for external mod
JARs. This is a development-environment binary and behavior audit; it is not a
production dedicated-server launch or a live multiplayer test.

The existing behavior tests run against those JAR classes and mocked players.
An additional test checks that LifeLink loaded from a JAR, that Fabric recognizes
it as a mod, and that callback registration succeeds. Reports and logs go under
`build/compatibility/<minecraft>/`. The original JAR's SHA-256 is checked after
every target; aggregate results go in `build/compatibility/results.json`.

Normal audits use the release metadata without dependency overrides. For a private
candidate outside the declared range, add `-AllowUnsupportedVersions`. This creates
a dependency override only in the isolated audit folder and leaves the original
JAR unchanged. Final release audits must pass without that switch.

Before publishing expanded support, run the multiplayer checklist in
`docs/COMPATIBILITY.md`, including dedicated-server and LAN restarts. The audit
cannot verify real damage processing, client titles, or networking timing by itself.
