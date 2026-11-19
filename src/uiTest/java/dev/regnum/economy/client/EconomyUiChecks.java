package dev.regnum.economy.client;

import dev.regnum.economy.money.*;
import dev.regnum.economy.trade.*;
import dev.regnum.economy.market.TreasuryPackets;
import dev.regnum.economy.npc.NpcPackets;
import dev.regnum.economy.npc.NpcPackets.ShopAction;
import dev.regnum.economy.npc.NpcPackets.ShopView;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.sakhno.regnum.nations.api.v1.*;
import dev.sakhno.regnum.nations.api.client.v1.EconomyContext;
import dev.sakhno.regnum.nations.client.ui.EconomyScreen;
import dev.sakhno.regnum.nations.client.ui.KingdomManagementScreen;
import dev.sakhno.regnum.nations.networking.EconomyNavigationPayloads;
import dev.sakhno.regnum.nations.networking.KingdomManagementPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.glfw.GLFW;

/** Synthetic native-client checks. No world, financial packet or server mutation is needed. */
public final class EconomyUiChecks implements ClientModInitializer {
    private static final UUID ID=new UUID(0,1);
    private static final List<String> CAPTURES = List.of("trade-uk-compact", "shop-uk-compact", "buyer-uk-compact",
            "sale-review-uk", "shop-uk-normal", "trade-uk-normal", "trade-en-compact", "shop-en-compact",
            "purchase-review-en", "shop-en-normal", "trade-en-normal", "trade-uk-max", "trade-en-timeout",
            "trade-en-pending", "trade-uk-inventory-warning", "trade-en-tooltip",
            "trade-en-mid", "trade-uk-mid", "trade-en-wide", "trade-uk-wide", "nations-kingdom-en", "nations-economy-en");
    private int stage, layouts, captureTicks;
    private CompletableFuture<Void> reload;
    private final long started=System.nanoTime();
    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                GLFW.glfwHideWindow(mc.getWindow().getWindow());
                if (System.nanoTime()-started>180_000_000_000L) throw new AssertionError("UI checks timed out");
                if (mc.getOverlay()!=null) return;
                if (mc.screen instanceof AccessibilityOnboardingScreen) mc.setScreen(new TitleScreen());
                if (stage>=2) {
                    if (captureTicks == 0 && !prepareCapture(mc)) return;
                    if (++captureTicks<10) return;
                    Files.createDirectories(Path.of("checks/captures"));
                    String capture = CAPTURES.get(stage - 2);
                    try (var pixels=net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                        int[] viewport = captureViewport(capture);
                        require(pixels.getWidth() == viewport[0] && pixels.getHeight() == viewport[1],
                                "Capture framebuffer: " + capture + " " + Arrays.toString(viewport));
                        pixels.writeToFile(Path.of("checks/captures", capture + ".png"));
                    }
                    if (stage - 1 == CAPTURES.size()) mc.stop();
                    else { stage++; captureTicks=0; }
                    return;
                }
                if (!(mc.screen instanceof TitleScreen)) return;
                if (stage==0) {
                    if (!mc.getLanguageManager().getSelected().equals("en_us")) {
                        mc.getLanguageManager().setSelected("en_us"); mc.reloadResourcePacks(); return;
                    }
                    checkLocale(mc); mc.getLanguageManager().setSelected("uk_ua"); reload=mc.reloadResourcePacks(); stage=1;
                } else if (stage==1&&reload.isDone()) {
                    reload.join(); checkLocale(mc); checkMarketLinks();
                    Files.createDirectories(Path.of("checks"));
                    Files.writeString(Path.of("checks/passed.txt"),"PASS "+layouts+" native layouts in EN/UK; buyer and shop views, safe focus, trade slots and states, stale reviews, market suggestions\n");
                    System.out.println("ECONOMY UI PASS: "+layouts+" layouts"); stage=2;
                }
            } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
        });
    }
    private void checkLocale(Minecraft mc) throws Exception {
        for (var denomination : Denomination.values()) {
            var model = mc.getItemRenderer().getModel(new ItemStack(denomination.item()), null, null, 0);
            require(model != mc.getModelManager().getMissingModel(), "Every coin resolves to a baked Minecraft item model");
        }
        for (int[] size:List.of(new int[]{320,240},new int[]{640,360},new int[]{1100,760})) {
            checkNpc(mc,size);
            Inventory inventory=new Inventory(null);
            var constructor=WalletMenu.class.getDeclaredConstructor(int.class,Inventory.class); constructor.setAccessible(true);
            var walletMenu=constructor.newInstance(1,inventory);
            walletMenu.setState(new WalletPackets.State(1,ID,4,0,Long.MAX_VALUE,-1));
            var wallet=new WalletScreen(walletMenu,inventory,Component.translatable("container.regnum-economy.wallet"));
            check(mc,wallet,size);
            require(!((EditBox)get(wallet,"amount")).visible,"Deposit mode hides withdrawal input");
            ((Button)get(wallet,"withdrawMode")).onPress();
            check(mc,wallet,size);
            require(!((Button)get(wallet,"deposit")).visible,"Withdraw mode hides deposit controls");
            var amount=(EditBox)get(wallet,"amount"); amount.setValue("15"); wallet.setFocused(amount);
            wallet.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);
            require((long)get(wallet,"pending")==0,"Enter in the amount field sends no withdrawal");
            require(wallet.getFocused()==get(wallet,"withdraw"),"Enter moves to explicit withdrawal control");
            var before=walletMenu.state(); set(wallet,"latestRequest",9L);
            wallet.accept(new WalletPackets.State(1,ID,99,8,0,0)); require(walletMenu.state()==before,"Outdated wallet reply ignored");
            checkTrade(mc, size);
            var entity=new EntitySnapshot(EntityRef.city(ID),"Long City ".repeat(5),ID,ID,Optional.empty(),Optional.empty(),0xffaaaaaa,Optional.empty(),ResourceLocation.withDefaultNamespace("overworld"),BlockPos.ZERO);
            for (boolean permitted:List.of(false,true)) {
                var treasury=new TreasuryScreen(new EconomyContext(entity,"owner",Set.of()),new TitleScreen());
                UUID screen=(UUID)get(treasury,"screen");
                treasury.accept(new TreasuryPackets.State(screen,0,ID,4,Long.MAX_VALUE,Long.MAX_VALUE,permitted,permitted,"ready"));
                check(mc,treasury,size); ((EditBox)get(treasury,"amount")).setValue("100");
                var review=TreasuryScreen.class.getDeclaredMethod("review",int.class); review.setAccessible(true); review.invoke(treasury,TreasuryPackets.DEPOSIT);
                require((int)get(treasury,"review")== (permitted?TreasuryPackets.DEPOSIT:-1),"Review requires current permission");
                if (permitted) {
                    check(mc,treasury,size);
                    require(((AbstractWidget)treasury.getFocused()).getMessage().getString().equals(Component.translatable("screen.regnum-economy.treasury.cancel").getString()),"Review focuses Cancel");
                    treasury.accept(new TreasuryPackets.State(screen,0,ID,5,-1,-1,false,false,"stale_context"));
                    require((int)get(treasury,"review")==-1,"Political invalidation cancels review");
                    require((long)get(treasury,"pending")==0,"Review never sends a transfer");
                }
            }
        }
    }
    private void checkNpc(Minecraft mc, int[] size) throws Exception {
        for (String role : List.of("merchant", "buyer", "banker", "official", "guard", "generic")) {
            var page = new NpcPackets.Page(ID, Component.literal("Persistent " + role), npcText("role." + role),
                    npcText(role.equals("banker") ? "bank.info" : role.equals("official") ? "city.info" : "greeting",
                            "100", "Owner", "Kingdom"), ItemStack.EMPTY,
                    List.of(npcText("more"), npcText("back")), 0, Component.empty(), "");
            check(mc, new NpcScreen(page), size);
            require(!page.role().getString().startsWith("npc.regnum-economy."), "Every role is localized");
        }
        for (var view : ShopView.values()) {
            for (boolean available : List.of(true, false)) {
                var shop = new NpcScreen(shopPage(view, available, false, true));
                check(mc, shop, size);
                if (view == ShopView.PURCHASE || view == ShopView.SALE) {
                    require(((AbstractWidget)shop.getFocused()).getMessage().equals(npcText("cancel")),
                            "Purchase and sale reviews initially focus Cancel");
                    require(shop.getNarrationMessage().getString().contains("420"), "Narration includes the review total");
                    shop.resize(mc, size[0], size[1]);
                    require(((AbstractWidget)shop.getFocused()).getMessage().equals(npcText("cancel")),
                            "Resize retains safe review focus");
                } else {
                    var buttons = shop.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
                    require(buttons.stream().anyMatch(b -> b.getMessage().equals(npcText("shop.buy_lot"))) == (view == ShopView.CATALOGUE),
                            "The buyer has no purchase control");
                    require(buttons.stream().anyMatch(b -> b.getMessage().equals(npcText("shop.sell_lot")) && b.active) == available,
                            "A sale is enabled only for a current sale action");
                }
            }
        }
        check(mc, new NpcScreen(shopPage(ShopView.BUYER, false, true, false)), size);
        var editor = new NpcScreen(npcPage("edit_greeting", ItemStack.EMPTY, List.of("cancel", "save"), 240));
        check(mc, editor, size);
        var input = (EditBox)get(editor,"input");
        input.setValue("An unsaved greeting");
        editor.setFocused(input);
        editor.resize(mc, size[0], size[1]);
        require(editor.getFocused() == get(editor,"input") && ((EditBox)get(editor,"input")).getValue().equals("An unsaved greeting"),
                "Resizing preserves the NPC edit field and focus");
        require(!npcText("bank.open").getString().startsWith("npc.regnum-economy."), "NPC strings are localized");
    }

    private static Component npcText(String key, Object... args) {
        return Component.translatable("npc.regnum-economy." + key, args);
    }

    private static NpcPackets.Page npcPage(String body, ItemStack item, List<String> actions, int inputLimit) {
        return new NpcPackets.Page(ID, Component.literal("Mason"), npcText("role.merchant"), npcText(body), item,
                actions.stream().map(EconomyUiChecks::npcText).toList(), inputLimit, npcText("edit_greeting"), "");
    }

    private static NpcPackets.Page shopPage(ShopView view, boolean available, boolean empty, boolean longName) {
        var item = empty ? ItemStack.EMPTY : new ItemStack(Items.BREAD);
        if (!empty && longName) item.set(DataComponents.CUSTOM_NAME, Component.literal("Long component-specific name ".repeat(3)));
        var actions = new LinkedHashMap<ShopAction, Component>();
        boolean review = view == ShopView.PURCHASE || view == ShopView.SALE;
        boolean buyer = view == ShopView.BUYER;
        var listings = review || empty ? List.<ItemStack>of() : List.of(item, new ItemStack(Items.APPLE), new ItemStack(Items.WHEAT));
        if (review) {
            actions.put(ShopAction.CANCEL, npcText("cancel"));
            actions.put(ShopAction.CONFIRM, npcText(view == ShopView.PURCHASE ? "shop.confirm_buy" : "shop.confirm_sell"));
        } else if (!empty) {
            actions.put(ShopAction.FIRST, item.getHoverName());
            actions.put(ShopAction.SECOND, listings.get(1).getHoverName());
            actions.put(ShopAction.THIRD, listings.get(2).getHoverName());
            if (available && !buyer) actions.put(ShopAction.BUY, npcText("shop.buy_lot"));
            if (available) actions.put(ShopAction.SELL, npcText("shop.sell_lot"));
            actions.put(ShopAction.NEXT, npcText("shop.next"));
        }
        if (!review) actions.put(ShopAction.BACK, npcText("shop.dialogue"));
        var shop = new NpcPackets.Shop(view, 12_345, listings, 0, 1, empty || review ? 0 : 2, 12, 24, 36,
                buyer ? 0 : 420, review ? 420 : 96, available ? Component.empty() : npcText("shop.no_space"),
                available ? Component.empty() : npcText("shop.stock_full"), List.copyOf(actions.keySet()));
        return new NpcPackets.Page(ID, Component.literal("Miller"), npcText(buyer ? "role.buyer" : "role.merchant"),
                empty ? npcText("shop.empty") : Component.empty(), item, List.copyOf(actions.values()), 0, Component.empty(), "", shop);
    }

    private void checkTrade(Minecraft mc, int[] size) throws Exception {
        var inventory = new Inventory(null);
        Component title = Component.translatable("container.regnum-economy.trade", "Long partner name ".repeat(4));
        for (boolean own : List.of(false, true)) for (boolean other : List.of(false, true)) {
            var menu = tradeMenu(inventory);
            var trade = new TradeScreen(menu, inventory, title);
            boolean countdown = own && other;
            menu.setState(tradeState(7, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, own, other, countdown ? 3 : 0, 0));
            check(mc, trade, size);
            checkTradeSlots(trade, menu, countdown);
            var confirm = (Button)get(trade, "confirm");
            var cancel = (Button)get(trade, "cancel");
            var amount = (EditBox)get(trade, "amount");
            require(confirm.active && cancel.active && confirm.getMessage().equals(tradeText(own ? "pause" : "confirm")),
                    "Trading exposes Confirm or Pause, with Cancel available");
            require((boolean)get(trade, "amountEditable") == !own, "Confirmation locks the wallet input");
            String maximum = Long.toString(Long.MAX_VALUE);
            require(amount.getValue().equals(maximum) && mc.font.width(maximum) <= amount.getInnerWidth(),
                    "The maximum offer fits in the wallet field");
            require(((Component)get(trade, "walletCoins")).getString().equals(maximum)
                            && ((Component)get(trade, "partnerCoins")).getString().equals(maximum),
                    "Both readouts retain all digits");
            int detailWidth = (int)get(trade, "DETAILS_WIDTH");
            require(mc.font.width(tradeText("editing")) <= detailWidth && mc.font.width(tradeText("agreed")) + 9 <= detailWidth,
                    "Both localized offer states fit beside the 3x3 grids in " + mc.getLanguageManager().getSelected());
            for (String label : List.of("slots_editable", "slots_read_only", "slots_locked")) {
                require(mc.font.width(tradeText(label)) <= detailWidth, "Localized slot access fits: " + label);
            }
            int fieldWidth = (int)get(trade, "FIELD_WIDTH");
            for (String label : List.of("own_panel", "other_panel", "wallet_available", "virtual_coins")) {
                require(mc.font.width(tradeText(label)) <= fieldWidth, "Localized ledger label fits: " + label);
            }
            require(mc.font.width(maximum) <= fieldWidth - 8, "A full wallet value fits the ledger field");
            require(trade.getNarrationMessage().getString().contains(maximum), "Narration includes authoritative coin amounts");
            require(tradeStatus(trade, countdown ? "COUNTDOWN" : own ? "WAITING" : other ? "PARTNER_CONFIRMED" : "REVIEW"),
                    "Each confirmation combination has an explicit status");
            checkTradeHover(trade, "Long partner name");
            if (countdown) {
                menu.setState(tradeState(7, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, true, true, 1, 0));
                invoke(trade, "updateControls");
                require((int)get(trade, "countdownSeconds") == 1, "Countdown tracks the server even at the same revision");
                menu.setState(tradeState(7, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, true, true, 0, 0));
                invoke(trade, "updateControls");
                require(tradeStatus(trade, "BOTH_CONFIRMED"), "Both confirmations alone never announce completion");
            }
            set(trade, "submittedRevision", 7L);
            set(trade, "submittedAction", TradePackets.Action.CONFIRM);
            set(trade, "submittedAt", System.currentTimeMillis());
            invoke(trade, "updateControls");
            require(!confirm.active && cancel.active && tradeStatus(trade, own ? "PENDING_PAUSE" : "PENDING_CONFIRM"),
                    "A pending confirmation or pause waits for its acknowledgement");
            set(trade, "submittedAt", System.currentTimeMillis() - 20_000);
            invoke(trade, "updateControls");
            require(!confirm.active && tradeStatus(trade, "TIMEOUT") && (long)get(trade, "submittedRevision") == 7L,
                    "Timeout preserves the in-flight revision and keeps confirmation disabled");
        }

        var menu = tradeMenu(inventory);
        var trade = new TradeScreen(menu, inventory, title);
        check(mc, trade, size);
        require(tradeStatus(trade, "OPENING") && !((Button)get(trade, "confirm")).active
                && !((Button)get(trade, "cancel")).active, "Initial state has no enabled financial controls");
        menu.setState(tradeState(7, 0, 0, 100, false, false, 0, 0));
        invoke(trade, "updateControls");
        require(tradeStatus(trade, "EMPTY") && !((Button)get(trade, "confirm")).active, "Empty offers cannot confirm");
        var amount = (EditBox)get(trade, "amount");
        amount.setValue("150");
        invoke(trade, "updateControls");
        require(tradeStatus(trade, "INSUFFICIENT") && !((Button)get(trade, "confirm")).active, "Insufficient balance is explicit");
        set(trade, "submittedRevision", 7L);
        set(trade, "submittedAction", TradePackets.Action.OFFER_COINS);
        set(trade, "submittedAt", System.currentTimeMillis());
        invoke(trade, "updateControls");
        require(tradeStatus(trade, "PENDING_COINS") && (boolean)get(trade, "amountEditable"), "Pending amount edits can be coalesced");
        trade.setFocused(amount);
        amount.setFocused(true);
        amount.moveCursorTo(2, false);
        amount.setHighlightPos(1);
        trade.resize(mc, size[0], size[1]);
        require(trade.getFocused() == amount && amount.isFocused() && amount.getValue().equals("150")
                && amount.getCursorPosition() == 2 && amount.getHighlighted().equals("5"),
                "Resize keeps the active amount, caret, selection, and focus");
        trade.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        require(!amount.isFocused() && (int)get(trade, "submittedAction") == TradePackets.Action.OFFER_COINS
                && (long)get(trade, "submittedRevision") == 7L, "Enter leaves the amount field without confirming");
        amount.setValue("10");
        for (int issue : List.of(TradePackets.State.INVENTORY_SPACE, TradePackets.State.COIN_BALANCE, TradePackets.State.SAVE_FAILED)) {
            menu.setState(tradeState(7 + issue, 10, 0, 100, false, false, 0, issue));
            invoke(trade, "updateControls");
            String expected = switch (issue) {
                case TradePackets.State.INVENTORY_SPACE -> "INVENTORY_SPACE";
                case TradePackets.State.COIN_BALANCE -> "BALANCE";
                default -> "SAVE_FAILED";
            };
            require(tradeStatus(trade, expected), "Server warning has a readable localized status: " + expected);
        }
        trade.setFocused(amount);
        amount.setFocused(true);
        trade.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        require(trade.getFocused() == get(trade, "confirm"), "Tab moves from the wallet field to Confirm");
        trade.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        require(trade.getFocused() == get(trade, "cancel"), "Tab moves from Confirm to Cancel");
        set(trade, "submittedRevision", menu.state().revision());
        set(trade, "submittedAction", TradePackets.Action.CANCEL);
        invoke(trade, "updateControls");
        require(tradeStatus(trade, "PENDING_CANCEL") && !((Button)get(trade, "cancel")).active,
                "Cancel stays visible without repeating the same pending request");
    }

    private boolean tradeStatus(TradeScreen screen, String name) throws Exception {
        var status = (Enum<?>)get(screen, "status");
        var lines = (List<?>)get(screen, "statusLines");
        String feedback = ((Component)get(screen, "feedback")).getString();
        return status.name().equals(name) && !feedback.contains("regnum-economy.trade.") && !lines.isEmpty() && lines.size() <= 2;
    }

    private void checkTradeHover(TradeScreen screen, String name) throws Exception {
        var hover = TradeScreen.class.getDeclaredMethod("hoverHelp", int.class, int.class);
        hover.setAccessible(true);
        require(((Component)hover.invoke(screen, 16, 12)).getString().contains(name), "A truncated title exposes the full partner name");
    }

    private void checkTradeSlots(TradeScreen screen, TradeMenu menu, boolean locked) throws Exception {
        var base = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class;
        var hit = base.getDeclaredMethod("findSlot", double.class, double.class);
        hit.setAccessible(true);
        int left = containerOrigin(base, screen, "leftPos"), top = containerOrigin(base, screen, "topPos");
        require(left >= 0 && top >= 0 && left + containerOrigin(base, screen, "imageWidth") <= screen.width
                && top + containerOrigin(base, screen, "imageHeight") <= screen.height, "Folio binding fits the logical GUI");
        require(menu.slots.size() == 54, "Trade keeps nine slots per offer and all 36 inventory slots");
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            require(slot.index == i, "Trade menu protocol slot indices stay stable");
            if (i < 18) {
                int withinOffer = i % 9;
                int start = i < 9 ? TradeMenu.OWN_OFFER_X : TradeMenu.OTHER_OFFER_X;
                require(slot.x == start + withinOffer % 3 * 18 && slot.y == TradeMenu.OFFER_Y + withinOffer / 3 * 18
                        && slot.getContainerSlot() == withinOffer, "Both offer grids are 3x3 in their original order");
                require(slot.mayPlace(new ItemStack(Items.BREAD)) == (i < 9 && !locked) && slot.mayPickup(null) == (i < 9 && !locked),
                        "Partner slots stay read-only and the countdown locks the local offer");
            } else {
                int inventoryIndex = i - 18;
                int y = inventoryIndex < 27 ? inventoryIndex / 9 * 18 : 58;
                require(slot.x == TradeMenu.INVENTORY_X + inventoryIndex % 9 * 18 && slot.y == TradeMenu.INVENTORY_Y + y
                        && slot.getContainerSlot() == (inventoryIndex + 9) % 36, "Inventory and hotbar retain vanilla rows and indices");
            }
            int x = left + slot.x, y = top + slot.y;
            require(x >= 0 && y >= 0 && x + 16 <= screen.width && y + 16 <= screen.height, "Slot stays inside the viewport");
            require(hit.invoke(screen, x + 8.0, y + 8.0) == slot, "Rendered slot center matches its menu hitbox");
            for (var child : screen.children()) {
                if (child instanceof AbstractWidget widget)
                    require(x >= widget.getRight() || x + 16 <= widget.getX() || y >= widget.getBottom()
                                    || y + 16 <= widget.getY(), "Trade controls do not overlap slots");
            }
        }
    }

    private static int containerOrigin(Class<?> base, TradeScreen screen, String name) throws ReflectiveOperationException {
        var field = base.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(screen);
    }

    private static TradeMenu tradeMenu(Inventory inventory) throws Exception {
        var constructor = TradeMenu.class.getDeclaredConstructor(int.class, Inventory.class);
        constructor.setAccessible(true);
        return constructor.newInstance(2, inventory);
    }

    private static TradePackets.State tradeState(long revision, long ownCoins, long otherCoins, long balance,
                                                  boolean own, boolean other, int seconds, int issue) {
        return new TradePackets.State(2, ID, revision, ownCoins, otherCoins, balance, own, other, seconds, issue);
    }

    private static Component tradeText(String key) {
        return Component.translatable("screen.regnum-economy.trade." + key);
    }

    private void checkMarketLinks() throws Exception {
        var c=Class.forName("dev.regnum.economy.market.Markets");
        var m=c.getDeclaredMethod("action",String.class,String.class,net.minecraft.network.chat.ClickEvent.Action.class); m.setAccessible(true);
        var action=(Component)m.invoke(null,"buy_action","/market buy 7 1",net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND);
        require(action.getStyle().getClickEvent().getAction()==net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND,"Buy link suggests; does not transfer");
        require(action.getStyle().getHoverEvent()!=null,"Market link explains command");
    }
    private void check(Minecraft mc,Screen screen,int[] size) throws Exception {
        screen.init(mc,size[0],size[1]);
        var widgets=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(w -> w.visible).toList();
        for (int i=0;i<widgets.size();i++) {
            var a=widgets.get(i); String context=screen.getClass().getSimpleName()+" "+Arrays.toString(size)+" "+a.getMessage().getString();
            require(a.getX()>=0&&a.getY()>=0&&a.getRight()<=size[0]&&a.getBottom()<=size[1],"Bounds: "+context);
            for (int j=i+1;j<widgets.size();j++) { var b=widgets.get(j);
                require(a.getX()>=b.getRight()||b.getX()>=a.getRight()||a.getY()>=b.getBottom()||b.getY()>=a.getBottom(),"Overlap: "+context+" / "+b.getMessage());
            }
        }
        layouts++;
    }
    private static int[] captureViewport(String capture) {
        if (capture.startsWith("trade") && (capture.endsWith("compact") || capture.endsWith("tooltip"))) {
            return new int[]{320, 240};
        }
        if (capture.endsWith("-mid")) return new int[]{640, 360};
        return new int[]{1100, 760};
    }

    private boolean prepareCapture(Minecraft mc) throws Exception {
        String capture = CAPTURES.get(stage - 2);
        String locale = capture.contains("-uk") ? "uk_ua" : "en_us";
        if (!mc.getLanguageManager().getSelected().equals(locale)) {
            mc.getLanguageManager().setSelected(locale);
            reload = mc.reloadResourcePacks();
            return false;
        }
        if (!reload.isDone()) return false;
        reload.join();
        var window = mc.getWindow();
        int[] viewport = captureViewport(capture);
        if (window.getWidth() != viewport[0] || window.getHeight() != viewport[1]) {
            GLFW.glfwSetWindowSize(window.getWindow(), viewport[0], viewport[1]);
            return false;
        }
        int scaleOption = capture.endsWith("normal") || capture.startsWith("nations-") ? 2 : 0;
        mc.options.guiScale().set(scaleOption);
        mc.resizeDisplay();
        int scale = window.calculateScale(scaleOption, mc.options.forceUnicodeFont().get());
        require(window.getGuiScaledWidth() == Math.ceilDiv(viewport[0], scale)
                        && window.getGuiScaledHeight() == Math.ceilDiv(viewport[1], scale),
                "Minecraft calculated the logical GUI size for " + capture);
        System.out.printf("UI capture %s: %dx%d at GUI scale %d (%dx%d logical)%n", capture,
                viewport[0], viewport[1], scale, window.getGuiScaledWidth(), window.getGuiScaledHeight());

        Screen screen;
        boolean itemTooltip = capture.endsWith("tooltip");
        if (capture.startsWith("trade")) {
            Inventory inventory = new Inventory(null);
            inventory.setItem(0, new ItemStack(Items.TORCH, 16));
            inventory.setItem(1, new ItemStack(Items.COOKED_BEEF, 12));
            inventory.setItem(9, new ItemStack(Items.OAK_LOG, 64));
            var axe = new ItemStack(Items.IRON_AXE);
            axe.setDamageValue(67);
            inventory.setItem(10, axe);
            var menu = tradeMenu(inventory);
            boolean maximum = capture.endsWith("-max");
            boolean countdown = capture.equals("trade-uk-compact") || capture.equals("trade-en-normal");
            boolean issue = capture.endsWith("inventory-warning");
            menu.setState(tradeState(7, maximum ? Long.MAX_VALUE : 100, maximum ? Long.MAX_VALUE : 200,
                    maximum ? Long.MAX_VALUE : 12_345, countdown, !issue, countdown ? 3 : 0,
                    issue ? TradePackets.State.INVENTORY_SPACE : 0));
            menu.setItem(0, 0, new ItemStack(Items.BREAD, 6));
            menu.setItem(1, 0, new ItemStack(Denomination.SILVER.item(), 3));
            menu.setItem(4, 0, new ItemStack(Items.ARROW, 24));
            menu.setItem(9, 0, new ItemStack(Items.IRON_INGOT, 8));
            menu.setItem(10, 0, new ItemStack(Denomination.COPPER.item(), 12));
            menu.setItem(13, 0, new ItemStack(Items.AMETHYST_SHARD, 4));
            var trade = new TradeScreen(menu, inventory,
                    Component.translatable("container.regnum-economy.trade", maximum ? "Long player display name ".repeat(3) : "Alex"));
            if (capture.endsWith("timeout") || capture.endsWith("pending")) {
                set(trade, "submittedRevision", 7L);
                set(trade, "submittedAction", TradePackets.Action.CONFIRM);
                set(trade, "submittedAt", System.currentTimeMillis() - (capture.endsWith("timeout") ? 20_000 : 0));
            }
            screen = trade;
        } else if (capture.equals("nations-kingdom-en")) {
            var overview = new KingdomManagementPayloads.Overview(ID, "Alderhaven", "Alex",
                    ResourceLocation.withDefaultNamespace("overworld"), BlockPos.ZERO, "present", 12, true, false, 2);
            screen = new KingdomManagementScreen(new KingdomManagementPayloads.State(0,
                    KingdomManagementPayloads.OPEN_MENU, null, ID, overview, null, "", true, ID, null));
        } else if (capture.equals("nations-economy-en")) {
            screen = new EconomyScreen(new TitleScreen(), EntityRef.kingdom(ID));
        } else {
            ShopView view = capture.startsWith("buyer") ? ShopView.BUYER : capture.startsWith("purchase") ? ShopView.PURCHASE
                    : capture.startsWith("sale") ? ShopView.SALE : ShopView.CATALOGUE;
            screen = new NpcScreen(shopPage(view, true, false, false));
        }
        // The native screens render authoritative-shaped data without a synthetic player's container ticking.
        mc.setScreen(new Screen(Component.literal("UI capture")) {
            private int mouseX = -1, mouseY = -1;
            @Override protected void init() {
                screen.init(mc, width, height);
                if (screen instanceof EconomyScreen economy) {
                    var entity = new EntitySnapshot(EntityRef.kingdom(ID), "Alderhaven", ID, ID, Optional.empty(),
                            Optional.empty(), 0xffa78e61, Optional.empty(), ResourceLocation.withDefaultNamespace("overworld"),
                            BlockPos.ZERO);
                    economy.accept(new EconomyNavigationPayloads.State(0, ApiResult.Status.VALID, Optional.of(entity),
                            "owner", Set.of()));
                }
                if (screen instanceof TradeScreen trade) {
                    try {
                        checkTradeSlots(trade, trade.getMenu(), trade.getMenu().state().seconds() > 0);
                        if (itemTooltip) {
                            var base = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class;
                            mouseX = containerOrigin(base, trade, "leftPos") + TradeMenu.OTHER_OFFER_X + 8;
                            mouseY = containerOrigin(base, trade, "topPos") + TradeMenu.OFFER_Y + 8;
                        }
                        if (capture.equals("trade-en-mid")) trade.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }
            }
            @Override public void render(net.minecraft.client.gui.GuiGraphics g, int x, int y, float delta) {
                g.fill(0, 0, width, height, 0xff171a1f);
                screen.render(g, mouseX, mouseY, delta);
            }
        });
        return true;
    }

    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
    private static Object get(Object o,String key) throws Exception { var f=o.getClass().getDeclaredField(key); f.setAccessible(true); return f.get(o); }
    private static void set(Object o,String key,Object value) throws Exception { var f=o.getClass().getDeclaredField(key); f.setAccessible(true); f.set(o,value); }
    private static void invoke(Object o,String key) throws Exception { var m=o.getClass().getDeclaredMethod(key); m.setAccessible(true); m.invoke(o); }
}
