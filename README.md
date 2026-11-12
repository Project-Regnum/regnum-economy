# regnum-economy

Economy module for Project Regnum (Minecraft Fabric 1.21.1).

## Coins

- Your persistent wallet appears in a dedicated inventory UI slot. Click it (or use `/wallet`) to deposit a selected coin stack, deposit all coins, or withdraw physical coins. Withdrawals require inventory space. Amounts use copper units: 1 silver = 100 copper; 1 gold = 10,000 copper.
- Pouch inventory cells select stacks without moving them. Use the gold selection border, arrows/Tab, and **Deposit stack**. If a request times out, refresh the authoritative state before retrying; a missing acknowledgement does not mean a transfer failed.
- Use the trade GUI for player transfers. Physical coins go in item slots; virtual coins go in the money field.
- Administrators can issue new currency with `/economy grant <player> <amount>` after enabling `/gamerule regnumEconomyIssuanceEnabled true`. Configure `regnumEconomyIssuanceMaxGrant` and `regnumEconomyIssuanceDailyPlayerLimit` for issuance limits. Issuance receipts persist with the recipient's account; withdrawal never issues new value.
- When Minecraft credits a player with your death, they receive 10% of your balance by default, rounded down to whole coins. Use `/gamerule regnumEconomyDeathDropPercent <0–100>` to change the rate. Deaths without a credited player leave your balance intact.
- Old pouch balances retain their value and legacy coin items remain spendable through explicit pouch deposits. Coins carried at login or picked up remain physical items.

## Player trades

Use `/trade <player>` to send a trade request. The recipient has 60 seconds to accept with `/accept trade`. If they have multiple requests, use `/accept trade <player>` to choose. Both players must be within 8 blocks in the same dimension when the request is sent and accepted.

Once the trade opens, drag items into your offer slots or enter a coin amount. Either player can offer items or coins. Both players must confirm. After both confirm, there is a three-second countdown. Press **Pause** to edit again, or **Cancel** to end the trade and return the offered items. If an inventory is full, make room to finish the trade or to receive pending returns.

Trade drops are disabled because spawned world entities cannot participate in an inventory rollback. Completion and cancellation each commit both players together; unsuccessful cancellation keeps returns in persistent escrow. Client and server must use matching Economy builds.

## City markets

Requires Regnum Nations API v1 from PR #20, commit `483330f8523f38c4bc13ae68e43335921dc8dc90`. Place a `regnum-economy:market` block in a claimed chunk and look at it while running `/market register`. Current Nations city permissions control market creation, management, tax and treasury actions; kingdom authority does not grant city authority. With Regnum Auth installed, both participants must log in before trading; visitors also log in before using a market.

Right-click a registered market to see its offers and **My trades**. Use `/market sell <quantity> <price-per-item>` while holding the item, or `/market order <item> <quantity> <price-per-item>` to reserve coins for a purchase. Buy items with `/market buy <id> <quantity>` and fulfill buy orders with `/market deliver <id> <quantity>`. The owner of an offer can cancel it with `/market cancel <id>`. Use `/market claim` at the same market to collect goods, proceeds, and refunds. A purchase order matches the specified item and its components.

Use `/market tax <percent>` to set the city's fee for new offers, `/market treasury` to check the city funds, and `/market treasury withdraw <amount>` to withdraw as a city owner or operator. The buyer pays the listed price and the fee is taken from the seller's proceeds. Orders retain the fee shown when posted. Registered market blocks are protected until the owner closes an empty market with `/market close`.

When a city loses the market's claim, open orders stop trading. Their owners can still cancel them and collect goods or refunds. An operator can withdraw city funds from an inactive market. Withdraw remaining funds before closing the last market in a city. If an administrator replaced a registered market block, restore it at the same coordinates to regain access.

Open Economy → Treasury in Nations for separate city and kingdom virtual accounts. Deposits and withdrawals move funds to or from the same player pouch used by markets. Existing city treasuries migrate once to scoped city accounts, even where a legacy kingdom shares the city UUID. Economy operations journal player data and market escrow together and recover interrupted commits on startup. Restore the complete world backup for a manual rollback.

Political changes invalidate open treasury contexts. Funds belonging to dissolved entities remain in their original scoped accounts and are not reassigned to another government; this version has no orphan-account recovery UI. Economic changes roll back on rejected/exceptional exits, and committed results are synchronized afterward. A pending recovery journal blocks further economic saves during shutdown. This covers player/market files; vanilla chunk/container/item-entity persistence is outside the journal. Gameplay, multiplayer and crash-recovery behavior still require runtime verification.

Use JDK 25 for Gradle; the mod targets Java 21. Build the pinned Nations source with its `remapJar` task, then run `.\gradlew.bat build -Pnations_jar=<path-to-Nations-jar>` (or place that JAR at `libs/regnum-nations-483330f8523f38c4bc13ae68e43335921dc8dc90.jar`). Output is in `build/libs`. The Nations dependency remains a separate installed mod.

The verified Nations 0.9.2 artifact is locked by normalized JAR-content SHA-256 `cbceed688bdc7c947defeca05b190e17323307864018c82d080e017686a77a9f`. Both the build and an early, server-safe startup check reject different content with installation guidance; matching a version string is insufficient. Use the same verified artifact on both sides. ZIP timestamps/compression may differ; different compiled content requires an explicitly reviewed compatibility-lock update. Loom development runs verify the original dependency JAR supplied by the build.
