# End-to-end check against a real Forge server

Runs the 1.19.2 build inside a real Forge 1.19.2 dedicated server together with
[Micle's Login Protection](https://modrinth.com/mod/micles-login-protection), and prints what actually happens
on the event bus. This is what settled the "no damage while Login Protection is installed" report: the dispatch
side is fine there, so the report is not caused by this mod rewriting call sites.

## What it checks

| line | meaning |
|---|---|
| `FastDispatch visible to game classes` | rewritten call sites can link at runtime |
| `Proxy.server after ServerStartedEvent: set` | Login Protection's `addListener(Consumer)` registration (a rewritten call site) is delivered, so `addPlayer` cannot silently fail |
| `state after addPlayer: JOINING` | Login Protection's own state machine is intact |
| `LivingDamageEvent while JOINING: cancelled=true` | its protection cancels damage while a player is still joining |
| `LivingDamageEvent while ACTIVE: cancelled=false` | and stops once the player is active |
| `ForgeHooksClient.onKeyInput ... handler hits` | the client input event path (the one that moves a player out of JOINING) still reaches its listeners |

## Running it

1. Install a Forge 1.19.2 server: `java -jar forge-1.19.2-43.5.2-installer.jar --installServer <dir>`
2. Put the pieces into `<dir>/mods`: the build from this branch (`fastevents-1.19-*.jar`), the Login Protection jar,
   and the test mod produced by the script below.
3. `pwsh -File tools/e2e/run.ps1 -ServerDir <dir> -LoginProtectionJar <lp.jar>`
4. Look for the `[E2E]` lines in the output.

The script compiles the test mod against the server's own `libraries/`, copies the jars into `mods/`, writes
`eula.txt`, starts the server once, and shuts it down as soon as the checks are done.

Note that Minecraft's own members are SRG-named at runtime, so the test mod only uses descriptor lookups
(`noArgReturning`) instead of official names.
