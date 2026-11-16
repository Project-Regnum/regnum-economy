# regnum-economy

Economy module for Project Regnum (Minecraft Fabric 1.21.1).

## Coins

- Your persistent wallet appears in a dedicated inventory UI slot. Click it (or use `/wallet`) to deposit a selected coin stack, deposit all coins, or withdraw physical coins. Withdrawals require inventory space. Amounts use copper units: 1 silver = 100 copper; 1 gold = 10,000 copper.
- Coin exchange inventory cells select stacks without moving them. Use the gold selection border, arrows/Tab, and **Deposit stack**. If a request times out, refresh the authoritative state before retrying; a missing acknowledgement does not mean a transfer failed.
- Use the trade GUI for player transfers. Physical coins go in item slots; virtual coins go in the money field.
- Administrators can issue new currency with `/economy grant <player> <amount>` after enabling `/gamerule regnumEconomyIssuanceEnabled true`. Configure `regnumEconomyIssuanceMaxGrant` and `regnumEconomyIssuanceDailyPlayerLimit` for issuance limits. Issuance receipts persist with the recipient's account; withdrawal never issues new value.
- When Minecraft credits a player with your death, they receive 10% of your balance by default, rounded down to whole coins. Use `/gamerule regnumEconomyDeathDropPercent <0–100>` to change the rate. Deaths without a credited player leave your balance intact.
- Old pouch balances retain their value and legacy coin items remain spendable through explicit coin deposits. Coins carried at login or picked up remain physical items.

## Player trades

Use `/trade <player>` to send a trade request. The recipient has 60 seconds to accept with `/accept trade`. If they have multiple requests, use `/accept trade <player>` to choose. Both players must be within 8 blocks in the same dimension when the request is sent and accepted.

Once the trade opens, drag items into your offer slots or enter a coin amount. Either player can offer items or coins. Both players must confirm. After both confirm, there is a three-second countdown. Press **Pause** to edit again, or **Cancel** to end the trade and return the offered items. If an inventory is full, make room to finish the trade or to receive pending returns.

Trade drops are disabled because spawned world entities cannot participate in an inventory rollback. Completion and cancellation each commit both players together; unsuccessful cancellation keeps returns in persistent escrow. Client and server must use matching Economy builds.

## City markets

Requires the verified Regnum Nations 0.12.0 build, exposing the API v1 originally introduced in PR #20. Place a `regnum-economy:market` block in a claimed chunk and look at it while running `/market register`. Current Nations city permissions control market creation, management, tax and treasury actions; kingdom authority does not grant city authority. With Regnum Auth installed, both participants must log in before trading; visitors also log in before using a market.

Right-click a registered market to see its offers and **My trades**. Use `/market sell <quantity> <price-per-item>` while holding the item, or `/market order <item> <quantity> <price-per-item>` to reserve coins for a purchase. Buy items with `/market buy <id> <quantity>` and fulfill buy orders with `/market deliver <id> <quantity>`. The owner of an offer can cancel it with `/market cancel <id>`. Use `/market claim` at the same market to collect goods, proceeds, and refunds. A purchase order matches the specified item and its components.

Use `/market tax <percent>` to set the city's fee for new offers, `/market treasury` to check the city funds, and `/market treasury withdraw <amount>` to withdraw as a city owner or operator. The buyer pays the listed price and the fee is taken from the seller's proceeds. Orders retain the fee shown when posted. Registered market blocks are protected until the owner closes an empty market with `/market close`.

When a city loses the market's claim, open orders stop trading. Their owners can still cancel them and collect goods or refunds. An operator can withdraw city funds from an inactive market. Withdraw remaining funds before closing the last market in a city. If an administrator replaced a registered market block, restore it at the same coordinates to regain access.

Open Economy → Treasury in Nations for separate city and kingdom virtual accounts. Deposits and withdrawals move funds to or from the same player balance used by markets. Existing city treasuries migrate once to scoped city accounts, even where a legacy kingdom shares the city UUID. Economy operations journal player data and market escrow together and recover interrupted commits on startup. Restore the complete world backup for a manual rollback.

Political changes invalidate open treasury contexts. Funds belonging to dissolved entities remain in their original scoped accounts and are not reassigned to another government; this version has no orphan-account recovery UI. Economic changes roll back on rejected/exceptional exits, and committed results are synchronized afterward. A pending recovery journal blocks further economic saves during shutdown. This covers player/market files; vanilla chunk/container/item-entity persistence is outside the journal. Gameplay, multiplayer and crash-recovery behavior still require runtime verification.

Use JDK 25 for Gradle; the mod targets Java 21. Economy consumes the existing sibling Nations project output at `../regnum-nations/build/libs/regnum-nations-0.12.0.jar`. Run `./gradlew test build` (Windows: `.\gradlew.bat test build`). Override the sibling directory with `-Pnations_project=<directory>` or supply a prebuilt artifact with `-Pnations_jar=<jar>`. Neither override changes the compatibility lock. The Nations dependency remains a separate installed mod.

For a fresh checkout, build Nations commit `b28e35866407645c94437129a29e63d0c53283b4` using JDK 25 and `./gradlew remapJar --no-daemon --console=plain` in a separate sibling checkout. CI does this automatically, then runs Economy's `test build`. Economy’s `build` now includes required headless integration GameTests; Nations dependency provisioning alone still runs only `remapJar`. Economy does not automatically rebuild Nations, so an existing artifact or modified sibling checkout is never overwritten during synchronization/build.

Two Nations 0.12.0 artifacts are explicitly approved by normalized JAR-content SHA-256:

- Reviewed E1 local build (uncommitted Nations checkout): `3459e8d87ca7c3c59468a5123564cb60d939c78b1e3a4a4cbf50e7bd7242d07d`.
- Clean build of the pinned commit (CI): `0431e6d09b1a7c06bfd6360cddf03b9ba21607a788003890f7e73aeb40170daf`.

The existing artifact contains local implementation edits that are absent from the pinned source commit. Its API class bytes match the clean build; both full artifacts are locked, rather than trusting arbitrary builds with the same API or version. Both the build and an early, server-safe startup check reject different content with installation guidance; matching a version string is insufficient. Use the same verified artifact on both sides. ZIP timestamps/compression may differ; different compiled content requires an explicitly reviewed compatibility-lock update. Loom development runs verify the original dependency JAR supplied by the build. Fabric metadata also requires the locked Nations version.

The 0.12.0 common API retains the PR #20 contract (scoped identities, permissions, territory queries, provider registration and change events). Client panel registration/context contracts remain present, and `NationsIdentity` adds a drawing helper. Common and client compilation against the verified artifact checks Economy's consumed signatures; gameplay and crash-recovery behavior still require runtime verification.

### Economy UI verification

The coin exchange has separate Deposit and Withdraw modes. Selecting inventory coins never moves them; the deposit preview shows their copper value. Enter in the withdrawal amount field moves focus to the explicit Withdraw button. Treasury transfers show source, destination and amount before confirmation; Cancel is initially focused, and political invalidation clears the review. Trade keeps its server slot positions and shows item offers separately from balance offers.

Run native client checks with an approved artifact:

```sh
./gradlew runUiTest test build -Pnations_jar=/path/to/approved/regnum-nations-0.12.0.jar --no-daemon --console=plain
```

`runUiTest` uses a disposable `build/run/uiTest` directory and synthetic snapshots in EN/UK at 320×240, 640×360 and 1100×760. It checks layout, mode visibility, explicit submission, stale replies/reviews, permissions and market command suggestions, and writes captures under `checks/captures/`. It does not perform transfers or validate escrow/crash recovery in multiplayer. The standard `test` task still has no JUnit test sources. Neither task changes the Nations compatibility lock.

### E1 local artifact approval

The explicitly reviewed local artifact remains Nations 0.12.0 at base commit
`b28e35866407645c94437129a29e63d0c53283b4` **plus uncommitted B/D/E1 changes**.
The commit is not claimed to reproduce the dirty local release. The separate CI
fingerprint still names the clean commit and was not changed. Approval uses the
existing `nations_content_sha256` property, packaged compatibility properties,
and unchanged build/prelaunch verifiers; neither path accepts arbitrary artifacts.
The prior local artifact is replaced in the allowlist. Reapproval requires reviewing
API/consumed signatures, required tests, matching client/server artifacts and
capturing source/artifact provenance; merely rebuilding the same version is insufficient.
See the shared `../docs/UI-IMPLEMENTATION-STATUS.md` E1 record for exact evidence.

`./gradlew runUiTest test build --no-daemon --console=plain` uses the approved
sibling JAR, checks 54 native client layouts and runs required Fabric server
GameTests in `build/run/gameTest/world` only. Server regressions cover Nations
ownership/financial policies and revocation, transactional balance rollback,
transfer/overflow, player-NBT balance persistence, conversion/inventory-full
rejection and escrow persistence/recovery without duplicate returns. They do not
replace live multiplayer or process-crash recovery testing.
