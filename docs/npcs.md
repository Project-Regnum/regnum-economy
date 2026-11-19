# Regnum NPCs

NPCs ship with Regnum Economy for Fabric 1.21.1 and use the locked Regnum Nations 0.12.0 artifact. Install matching builds on the server and clients. When Regnum Auth is installed, log in before interacting or administering an NPC.

## Roles

- **Merchant** opens a catalogue with real stock, copper prices, wallet balance and a separate trade confirmation.
- **Buyer** opens its catalogue on right-click. It pays bids from escrow and takes matching items into stock.
- **Banker** shows your current balance and denominations and opens Regnum's existing coin exchange for deposits and withdrawals.
- **Civil official** can report the current city, owner and kingdom in a Nations claim.
- **Sentinel** and **resident** support the same branching dialogue and city context as the other roles. All six roles are stationary.

Right-click within six blocks with a clear line of sight. The greeting branches into a second dialogue page, role actions and city information when available. Use **Back** to return to the greeting or **Close** to leave. Default dialogue and controls are available in English and Ukrainian.

## Administration

Use `/economy npc create <role> <name>` at the position where the NPC should stand. The built-in role IDs are `regnum-economy:merchant`, `regnum-economy:buyer`, `regnum-economy:banker`, `regnum-economy:official`, `regnum-economy:guard` and `regnum-economy:generic`. Creation prints a clickable UUID. Other nearby NPC commands offer UUID completions.

| Command | Effect |
| --- | --- |
| `/economy npc info <npc>` | Show a nearby NPC's identity. Authorized merchant operators also see escrow funds and numbered offers. |
| `/economy npc edit <npc>` | Open the same configuration page as **Configure NPC** in a conversation. |
| `/economy npc remove <npc>` | Remove a nearby NPC after emptying any escrow. |
| `/economy npc offer <npc> <quantity> <ask> <bid>` | Configure the held item's exact components, lot size and prices. |
| `/economy npc unlist <npc> <slot>` | Remove an offer with empty stock. |
| `/economy npc stock deposit <npc> <slot> <amount>` | Move matching items from your inventory into stock. |
| `/economy npc stock collect <npc> <slot> <amount>` | Move stock to your inventory if there is space. |
| `/economy npc funds deposit <npc> <amount>` | Move copper from the source account into merchant escrow. |
| `/economy npc funds collect <npc> <amount>` | Return copper from escrow to the original account. |
| `/economy npc settle <npc>` | Return all stock and funds in a single operation and clear the catalogue. Works by UUID even if the entity is missing or unloaded. |

The configuration page edits the display name, role label, appearance, greeting, second dialogue page and active state. Empty dialogue or role label fields restore localized defaults. Custom prose is stored as literal text. Merchant operators can also edit the held item's offer graphically. Funds and stock transfers use the commands above. An authorized administrator can right-click an inactive NPC to reactivate it.

Wilderness placement requires permission level 2. The creator owns the NPC. Level-3 operators may edit or remove an empty wilderness NPC. Its proprietor alone manages stock, offers and funds. In a claim, placement uses Nations' `ECONOMY_CREATE_MARKET`. Editing and removal use `ECONOMY_MANAGE_MARKET` for the bound city. Merchant financial and stock operations additionally require `ECONOMY_WITHDRAW_TREASURY`, and returns to that treasury require `ECONOMY_DEPOSIT_TREASURY`. A kingdom position confers authority only according to the bound city's Nations policy.

A live claim must still match the NPC's bound city for visitors, edits and deposits. A wilderness NPC suspends public interaction when a city claims its position. The original authorized account can still collect escrow after claim loss; settlement resolves its original financial identity. City and kingdom names, ownership and affiliation come from Nations at interaction time.

## Merchant settlement

Set the lot size to 1–64 and both prices in copper per lot. The ask is what the visitor pays and the bid is what the visitor receives. Zero disables that direction. A merchant has up to 16 listings and each can hold up to 2,304 units. A sale to the merchant replenishes that item's stock. The main inventory must contain the exact components for deposits and sales, and purchases require inventory space.

Wilderness merchants use the proprietor's virtual wallet as the funding and settlement account. City merchants use their own city treasury. Purchases credit the NPC's escrow and sales draw from it. City funding and return are explicit transfers. NPC trades have a zero tax rate. This rate applies to the NPC catalogue only.

The server issues one-use page tokens tied to the player and loaded entity. It checks current distance, claim, authorization and exact offer terms again before a transfer. If a confirmation response times out, reopen the conversation to check the authoritative inventory, wallet and stock before retrying.

Entity UUID and position use vanilla serialization. The version-1 `RegnumNpc` entity tag holds affiliation, role, appearance, active state and text. Unsupported definitions remain inactive and retain their original data. Merchant offers and escrow share `data/regnum_economy_markets.dat` and the existing wallet transaction journal. Version 3 reads versions 1 and 2 with an empty NPC catalogue on migration. Keep the full world together when restoring a backup. If an NPC was deleted through vanilla administration or lost before an entity save, use its UUID to settle any durable escrow.

## Buyer setup in a disposable world

Create a fresh world with commands enabled. As an operator in unclaimed wilderness, stand where the buyer should go and run these commands. Replace `<operator>` with your player name and `<npc>` with the UUID printed when the NPC is created. Hold plain wheat in your main hand before running `offer`.

```text
/gamerule regnumEconomyIssuanceEnabled true
/economy grant <operator> 100
/give @s minecraft:wheat 4
/economy npc create regnum-economy:buyer Miller
/economy npc offer <npc> 2 0 6
/economy npc funds deposit <npc> 30
/economy npc info <npc>
```

The ask column is `0` for buyers. To configure the same offer graphically, hold the wheat, open `/economy npc edit <npc>`, select **Edit held-item offer**, enter `2 0 6`, and select **Save**. The **Talk** button in the buyer's catalogue also leads to **Configure NPC** for authorized users. The funding command draws from the proprietor's wallet in wilderness and the bound city treasury in a claim.

Right-click Miller. Select wheat if necessary, then choose **Sell lot**. The review shows 2 wheat and 6 copper. Choose **Confirm sale**. With the same operator account as the visitor, the inventory should go from 4 to 2 wheat, the wallet from 70 to 76 copper, the listing stock from 0 to 2 wheat, and escrow from 30 to 24 copper. `/wallet` shows the wallet balance and `/economy npc info <npc>` shows stock and escrow.

To exercise server rejection with two players, have the visitor open a sale review. From the operator account, hold the same wheat and run `/economy npc offer <npc> 2 0 7` before the visitor confirms. The visitor should see that the offer changed. Compare the visitor's matching inventory and wallet and the buyer's stock and escrow before and after confirmation. The updated bid appears in the catalogue for a fresh review.

## Development and verification

Set Gradle to JDK 25 and provide a Java 21 toolchain. Use the approved Nations artifact described in the [Economy README](../README.md). On Windows, run `.\gradlew.bat test build runUiTest --console=plain`. The server GameTests and client layout checks use separate directories under `build/run`.

NPC transactions require the normal inventory menu with an empty cursor and crafting grid. Close other containers and return any cursor-held or crafting-grid items before transferring stock, funds or settling escrow. Disconnected player instances cannot perform NPC actions.

For the opt-in process interruption test, run `runGameTest -Precovery_probe=interrupt`, followed by `runGameTest -Precovery_probe=verify` twice in separate Gradle invocations. The interrupt phase intentionally exits its test JVM with code 86 after a real market-file installation failure; Gradle reports that expected interruption as a failed task. Both verification invocations must succeed. This uses only `build/run/recoveryProbe/world`; starting another interrupt phase resets that disposable world. The normal `build` uses the separate, freshly reset `build/run/gameTest/world`.

The probe checks journal recovery after the player file has been installed but the market file has not, exact wallet/items/escrow after restart, repeated startup and persistence of all five roles. It does not establish safety against power loss or every possible filesystem failure. Live multiplayer and integrated-host behavior still require separate testing. See [stabilization evidence](npc-stabilization.md).

To exercise a live trade in the development client, run `.\gradlew.bat runClient` and create a new creative test world with commands enabled. Find open wilderness and stand where the merchant should go.

```text
/economy npc create regnum-economy:merchant Mason
/economy npc offer <npc> 2 10 6
/economy npc stock deposit <npc> 1 8
```

Hold the item you want to trade before running the offer command. Use the UUID printed at creation and keep at least eight matching items in your main inventory to stock it. Create legitimate test funds via the existing authorized issuance workflow in this disposable world. For example, enable `regnumEconomyIssuanceEnabled` and grant your test player 100 copper. Fund the merchant with `/economy npc funds deposit <npc> 30`. Right-click the merchant, browse, review a purchase and confirm. Check that the lot appears in inventory and the price leaves the wallet. Review and confirm a sale of that lot to verify the reverse transfer. For a banker, create one with `/economy npc create regnum-economy:banker Clerk`, then open the coin exchange and exercise the existing deposit and withdrawal controls.

## Implementation and extension

`NpcRole` registers stable role IDs with a translated label, default skin and built-in interaction feature. `RegnumNpc` holds the placed instance. `NpcInteractions` defines the server-side dialogue pages and their permitted actions. Extend the built-in features there when adding an action. `NpcMerchants` owns offer and escrow operations through the existing market transaction. Client widgets and rendering live in the client source set. Resource packs can override the 64×64 regular-arm skins under `textures/entity/npc/`.

The current dialogue editor exposes two editable prose pages and built-in conditional branches. Logically independent data-pack template graphs, player-profile skins and patrols are future extension points. See [third-party notices](../THIRD_PARTY_NOTICES.md) for the Easy NPC reference.
