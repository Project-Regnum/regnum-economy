# regnum-economy

Economy module for Project Regnum (Minecraft Fabric 1.21.1).

## Coins

- Your balance appears in a dedicated slot in the inventory. Hover over it to see the exact amount.
- Use `/pay <player> <amount>` to transfer coins to an online player.
- Administrators can issue coins with `/economy grant <player> <amount>`.
- When Minecraft credits a player with your death, they receive 10% of your balance by default, rounded down to whole coins. Use `/gamerule regnumEconomyDeathDropPercent <0–100>` to change the rate. Deaths without a credited player leave your balance intact.
- Old pouch balances and legacy coin items are converted at 1 coin per copper, 100 per silver, and 10,000 per gold. Legacy coins carried at login or picked up from the world are credited automatically.

## Player trades

Use `/trade <player>` to send a trade request. The recipient has 60 seconds to accept with `/accept trade`. If they have multiple requests, use `/accept trade <player>` to choose. Both players must be within 8 blocks in the same dimension when the request is sent and accepted.

Once the trade opens, drag items into your offer slots or enter a coin amount. Either player can offer items or coins. Both players must confirm. After both confirm, there is a three-second countdown. Press **Pause** to edit again, or **Cancel** to end the trade and return the offered items. If an inventory is full, make room to finish the trade or to receive pending returns.

## City markets

Install Regnum Nations to use city claims and city owners. Place a `regnum-economy:market` block in a claimed chunk and look at it while running `/market register`. The city owner or an operator can register and manage it. Without Nations, operators can use `/market register <name>` for standalone test markets. With Regnum Auth installed, both participants must log in before direct payments and trades; visitors also log in before using a market.

Right-click a registered market to see its offers and **My trades**. Use `/market sell <quantity> <price-per-item>` while holding the item, or `/market order <item> <quantity> <price-per-item>` to reserve coins for a purchase. Buy items with `/market buy <id> <quantity>` and fulfill buy orders with `/market deliver <id> <quantity>`. The owner of an offer can cancel it with `/market cancel <id>`. Use `/market claim` at the same market to collect goods, proceeds, and refunds. A purchase order matches the specified item and its components.

Use `/market tax <percent>` to set the city's fee for new offers, `/market treasury` to check the city funds, and `/market treasury withdraw <amount>` to withdraw as a city owner or operator. The buyer pays the listed price and the fee is taken from the seller's proceeds. Orders retain the fee shown when posted. Registered market blocks are protected until the owner closes an empty market with `/market close`.

When a city loses the market's claim, open orders stop trading. Their owners can still cancel them and collect goods or refunds. An operator can withdraw city funds from an inactive market. Withdraw remaining funds before closing the last market in a city. If an administrator replaced a registered market block, restore it at the same coordinates to regain access.

Market orders and claims are saved with the world. Inventory and player balances are saved with player data. After a server crash, restore both from the same backup if you need to roll back.

Use JDK 25 for the Gradle build. The compiled mod targets Java 21. Run `.\gradlew.bat build` to create the JAR in `build/libs` or `.\gradlew.bat runClient` to try it locally.
