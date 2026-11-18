# NPC and coin integration evidence — 2026-11-18

Integration started from `origin/main` at `5bb28eb`, with a clean working tree.
No `AGENTS.md` was present in the repository or its ancestor directories.
`feat/npc-integration` (`32c8c3a`) already descends from the approved
`feat/coin-textures` commit (`a7beab6`), so the second requested merge is
already up to date. No binary conflict required an artwork choice.

## Confirmed fixes

- NPC operations could checkpoint a player's inventory while items were on
  the cursor or in the inventory crafting grid. Those transient items are
  absent from vanilla player NBT. NPC commits now require the inventory menu,
  an empty cursor and an empty crafting grid, including UUID-only settlement.
- NPC authorization did not reject the disconnected player flag before the
  player was removed from `PlayerList`. It now does; stale actions cannot use
  that interval to change escrow or NPC configuration.
- The NPC commit changed the GameTest launcher from Java 21 to Java 25 without
  an accompanying compatibility change. The approved Nations JAR contains
  Java 21 class files. The server GameTests and native UI checks now launch
  explicitly on Java 21; CI provisions both 21 and 25, with 25 used for Gradle.

## Validation

| Check | Executed result |
| --- | --- |
| `test build` with JDK 25 for Gradle / Java 21 Minecraft | Passed; all 14 required GameTests passed |
| Standard unit-test task | `NO-SOURCE`; the repository has no JUnit tests |
| `runUiTest` on Java 21 | Passed; 102 native EN/UK layouts at 320×240, 640×360 and 1100×760 |
| Actual journal-write failure before commit | Passed rollback and fresh-retry assertions |
| Actual install failure / abrupt JVM halt / two restarts | Expected exit 86, then two successful recovery checks |
| Approved Nations dependency and startup checks | Passed in native client and server runs |
| Modified Nations dependency | Rejected by the unchanged fingerprint verifier |
| Missing Nations dependency | Blocked during Loom configuration, before verifier task execution |
| Asset identity, dimensions, transparency, paths and baked models | Passed; native capture inspected |

An initial added packet assertion compared numeric translation argument Java
types rather than their wire meaning. It was corrected to compare the complete
canonical component serialization; the final server run passed. All 102 native
layouts passed in the preceding combined build/UI invocation. The subsequent
successful `test build` includes the last packet and revoked-token assertions;
production code and UI source were unchanged between those two invocations.

The focused server checks cover exact-component trades, duplicate and stale
requests, partial/full inventory rejection, overflow, 2,304-item stock transfers,
16-item and one-item stack limits, cursor/crafting rejection, disconnects,
missing NPC settlement, malformed NPC data, malformed merchant escrow,
legacy market migration, all five role lifecycles, packet codec bounds,
city ownership/permission changes and original-city escrow settlement.

A real pre-commit journal write error is induced by temporarily blocking the
disposable world's data directory. The test requires complete rollback of
wallet, inventory, escrow and the durable market file, followed by a successful
fresh retry.

The separate recovery probe blocks the actual market-file install after the
journal and player file commit. It verifies the recovery lock and retained
journal, restores the old market destination, then abruptly terminates the
JVM with code 86. That Gradle invocation intentionally fails with exit 86.
Two independent Java 21 JVM restarts succeeded, verifying wallet 90 copper,
two purchased stone items, merchant escrow 10 copper/two stock, journal
removal, idempotent startup and all five NPC roles loaded from saved entity
regions with their original identities and configuration.

The modified, correct-version Nations test artifact was rejected by
`verifyNationsDependency`. A missing artifact stopped during Loom
configuration before that task could execute. Normal client and server
startup accepted the unchanged approved CI fingerprint
`0431e6d09b1a7c06bfd6360cddf03b9ba21607a788003890f7e73aeb40170daf`.
Neither compatibility fingerprint nor the Nations repository was modified.

## Coin artwork

All three PNGs match `feat/coin-textures` byte-for-byte. Each is 16×16 with
92 transparent and 164 opaque pixels. Copper, silver and gold remain visually
distinct, use the existing `assets/regnum-economy/textures/item/*_coin.png`
paths and generated item models, and retain their IDs and values. Native UI
validation checks baked model resolution and captures the denominations.

## Limits

Native client validation uses synthetic pages and menus; it is not an
end-to-end multiplayer interaction test. Live client/server trading,
network disconnect timing, integrated-host restart/recovery, resource packs,
additional UI scales and hardware combinations remain untested. Persistence
was checked for explicitly saved NPC entities. The tested interruption is
after player install and before market install; it does not establish safety
against power loss or every filesystem/crash stage. Vanilla chunks, external
containers and item entities remain outside the existing wallet journal.
Dissolved political accounts retain the existing lack of an orphan-account
recovery UI. No general crash-safety guarantee is made.

Local logs and captures are retained in ignored `build/`: `integration-final-build.log`, `integration-final.log`,
`recovery-interrupt.log`, `recovery-verify-first.log`,
`recovery-verify-second.log`, `nations-reject-*.log` and
`run/uiTest/checks/captures/`. See [NPC verification commands](npcs.md#development-and-verification)
for reproduction.
