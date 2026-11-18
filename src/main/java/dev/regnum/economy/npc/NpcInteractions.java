package dev.regnum.economy.npc;

import dev.regnum.economy.market.NpcMerchants;
import dev.regnum.economy.money.CoinBalances;
import dev.regnum.economy.money.WalletMenu;
import dev.sakhno.regnum.nations.api.v1.NationsApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import static dev.regnum.economy.npc.Npcs.text;

final class NpcInteractions {
    private static final long SESSION_MS = 60_000;
    private static final long OPEN_COOLDOWN_MS = 200;
    private static final Map<ServerPlayer, Session> SESSIONS = new HashMap<>();

    record Choice(Component label, Consumer<String> action) { }
    private record Field(int limit, Component hint, String value) {
        static final Field NONE = new Field(0, Component.empty(), "");
    }
    record Ticket(UUID token, long opened, int choices, int inputLimit) {
        boolean accepts(NpcPackets.Select request, long now) {
            return token.equals(request.token()) && now - opened < SESSION_MS
                    && request.choice() >= 0 && request.choice() < choices
                    && RegnumNpc.validText(request.input(), inputLimit);
        }
    }
    private record Session(RegnumNpc npc, Ticket ticket, boolean editing, List<Choice> choices) { }

    private NpcInteractions() { }

    static void register() {
        NpcPackets.register();
        ServerPlayNetworking.registerGlobalReceiver(NpcPackets.Select.TYPE, (request, context) -> select(context.player(), request));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.remove(handler.player));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.keySet().removeIf(player -> player.server == server));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 100 == 0) {
                long now = Util.getMillis();
                SESSIONS.entrySet().removeIf(entry -> entry.getKey().server == server && now - entry.getValue().ticket.opened() >= SESSION_MS);
            }
        });
    }

    static void open(ServerPlayer player, RegnumNpc npc) {
        var current = SESSIONS.get(player);
        if (current != null && Util.getMillis() - current.ticket.opened() < OPEN_COOLDOWN_MS) return;
        if (player.containerMenu != player.inventoryMenu || !ServerPlayNetworking.canSend(player, NpcPackets.Page.TYPE)) {
            player.displayClientMessage(text("result.unavailable"), true);
            return;
        }
        if (!npc.active() && Npcs.canManage(player, npc)) {
            edit(player, npc);
        } else if (Npcs.canInteract(player, npc)) {
            npc.lookAt(player, 180, 45);
            npc.setYBodyRot(npc.getYHeadRot());
            welcome(player, npc);
        } else {
            player.displayClientMessage(text("result.unavailable"), true);
        }
    }

    static void select(ServerPlayer player, NpcPackets.Select request) {
        Session session = SESSIONS.get(player);
        if (session == null || !session.ticket.accepts(request, Util.getMillis())) return;
        SESSIONS.remove(player);
        if (player.containerMenu != player.inventoryMenu
                || !(session.editing ? Npcs.canManage(player, session.npc) : Npcs.canInteract(player, session.npc))) {
            if (ServerPlayNetworking.canSend(player, NpcPackets.Page.TYPE)) {
                show(player, session.npc, false, text("result.unavailable"), ItemStack.EMPTY, Field.NONE, List.of());
            }
            return;
        }
        session.choices.get(request.choice()).action.accept(request.input());
    }

    private static void welcome(ServerPlayer player, RegnumNpc npc) {
        List<Choice> choices = new ArrayList<>();
        switch (npc.role().feature()) {
            case MERCHANT -> choices.add(choice("browse", () -> browse(player, npc, 0, Component.empty())));
            case WALLET -> choices.add(choice("bank", () -> bank(player, npc)));
            case CITY, DIALOGUE -> { }
        }
        choices.add(choice("more", () -> details(player, npc)));
        if (Npcs.city(player, npc) != null) choices.add(choice("city", () -> city(player, npc)));
        if (Npcs.canManage(player, npc)) choices.add(choice("edit", () -> edit(player, npc)));
        show(player, npc, false, npc.greeting().isEmpty() ? text("greeting") : Component.literal(npc.greeting()),
                ItemStack.EMPTY, Field.NONE, choices);
    }

    private static void details(ServerPlayer player, RegnumNpc npc) {
        Component details = npc.details().isEmpty() ? text("details.default") : Component.literal(npc.details());
        show(player, npc, false, details, ItemStack.EMPTY, Field.NONE, List.of(back(player, npc)));
    }

    private static void city(ServerPlayer player, RegnumNpc npc) {
        var city = Npcs.city(player, npc);
        if (city == null) { welcome(player, npc); return; }
        var owner = player.server.getPlayerList().getPlayer(city.owner());
        Component ownerLabel = owner == null ? Component.literal(city.owner().toString()) : owner.getDisplayName();
        Component kingdom = city.kingdom().map(id -> NationsApi.get(player.server).kingdom(id).value()
                .<Component>map(sovereign -> Component.literal(sovereign.name())).orElse(text("city.unknown")))
                .orElse(text("city.independent"));
        show(player, npc, false, text("city.info", city.name(), ownerLabel, kingdom),
                ItemStack.EMPTY, Field.NONE, List.of(back(player, npc)));
    }

    private static void bank(ServerPlayer player, RegnumNpc npc) {
        show(player, npc, false, text("bank.info", Long.toString(CoinBalances.get(player))), ItemStack.EMPTY, Field.NONE,
                List.of(choice("bank.open", () -> WalletMenu.open(player)), back(player, npc)));
    }

    private static void browse(ServerPlayer player, RegnumNpc npc, int index, Component feedback) {
        var catalog = NpcMerchants.catalog(player, npc);
        long wallet = CoinBalances.get(player);
        if (catalog.goods().isEmpty()) {
            var body = text(catalog.available() ? "merchant.empty" : "result.unavailable", Long.toString(wallet));
            show(player, npc, false, withFeedback(feedback, body), ItemStack.EMPTY, Field.NONE, List.of(back(player, npc)));
            return;
        }
        int slot = Math.clamp(index, 0, catalog.goods().size() - 1);
        var quote = catalog.goods().get(slot);
        boolean buy = quote.ask() > 0 && quote.stock() >= quote.quantity() && wallet >= quote.ask();
        boolean sell = quote.bid() > 0 && catalog.funds() >= quote.bid()
                && quote.quantity() <= NpcMerchants.STOCK_LIMIT - quote.stock();
        Component body = text("merchant.quote", quote.item().getHoverName(), quote.quantity(), slot + 1, catalog.goods().size(),
                quote.stock(), Long.toString(wallet), price(quote.ask(), buy), price(quote.bid(), sell));
        List<Choice> choices = new ArrayList<>();
        if (buy) {
            choices.add(choice("review_buy", () -> review(player, npc, slot, quote, true)));
        }
        if (sell) {
            choices.add(choice("review_sell", () -> review(player, npc, slot, quote, false)));
        }
        if (slot > 0) choices.add(choice("previous", () -> browse(player, npc, slot - 1, Component.empty())));
        if (slot + 1 < catalog.goods().size()) choices.add(choice("next", () -> browse(player, npc, slot + 1, Component.empty())));
        choices.add(back(player, npc));
        show(player, npc, false, withFeedback(feedback, body), quote.item().copyWithCount(quote.quantity()), Field.NONE, choices);
    }

    private static Component withFeedback(Component feedback, Component body) {
        return feedback.equals(Component.empty()) ? body : feedback.copy().append("\n").append(body);
    }

    private static Component price(long price, boolean available) {
        if (price == 0) return text("merchant.disabled");
        return text(available ? "merchant.copper" : "merchant.unavailable_price", Long.toString(price));
    }

    private static void review(ServerPlayer player, RegnumNpc npc, int slot, NpcMerchants.Quote quote, boolean purchase) {
        var body = text(purchase ? "merchant.confirm_buy" : "merchant.confirm_sell",
                quote.quantity(), quote.item().getHoverName(), Long.toString(quote.price(purchase)));
        show(player, npc, false, body, quote.item().copyWithCount(quote.quantity()), Field.NONE, List.of(
                choice("cancel", () -> browse(player, npc, slot, Component.empty())),
                choice("confirm", () -> {
                    var result = NpcMerchants.trade(player, npc, quote, purchase);
                    browse(player, npc, slot, result.message());
                })));
    }

    static boolean edit(ServerPlayer player, RegnumNpc npc) {
        if (!Npcs.canManage(player, npc) || !ServerPlayNetworking.canSend(player, NpcPackets.Page.TYPE)
                || player.containerMenu != player.inventoryMenu) return false;
        List<Choice> choices = new ArrayList<>();
        choices.add(choice("edit_appearance", () -> editAppearance(player, npc)));
        choices.add(choice("edit_greeting", () -> editText(player, npc, "edit_greeting", RegnumNpc.DIALOGUE_LIMIT,
                npc.greeting(), npc::greeting)));
        choices.add(choice("edit_details", () -> editText(player, npc, "edit_details", RegnumNpc.DIALOGUE_LIMIT,
                npc.details(), npc::details)));
        choices.add(choice(npc.active() ? "deactivate" : "activate", () -> {
            npc.active(!npc.active());
            edit(player, npc);
        }));
        if (npc.role().feature() == NpcRole.Feature.MERCHANT && Npcs.canManageStock(player, npc)) {
            choices.add(choice("edit_offer", () -> editOffer(player, npc, Component.empty())));
        }
        if (npc.active()) choices.add(back(player, npc));
        show(player, npc, true, text("admin.info", npc.getStringUUID()), ItemStack.EMPTY, Field.NONE, choices);
        return true;
    }

    private static void editText(ServerPlayer player, RegnumNpc npc, String key, int limit, String initial, Consumer<String> save) {
        show(player, npc, true, text(key), ItemStack.EMPTY, new Field(limit, text(key), initial), List.of(
                choice("cancel", () -> edit(player, npc)),
                new Choice(text("save"), value -> {
                    if (key.equals("name") && value.isBlank()) {
                        editText(player, npc, key, limit, initial, save);
                        return;
                    }
                    save.accept(value);
                    edit(player, npc);
                })));
    }

    private static void editAppearance(ServerPlayer player, RegnumNpc npc) {
        show(player, npc, true, text("edit_appearance"), ItemStack.EMPTY, Field.NONE, List.of(
                choice("name", () -> editText(player, npc, "name", RegnumNpc.NAME_LIMIT,
                        npc.getName().getString(), value -> npc.setCustomName(Component.literal(value)))),
                choice("role_title", () -> editText(player, npc, "role_title", RegnumNpc.NAME_LIMIT, npc.roleTitle(), npc::roleTitle)),
                choice("appearance", () -> appearance(player, npc)),
                choice("back", () -> edit(player, npc))));
    }

    private static void appearance(ServerPlayer player, RegnumNpc npc) {
        List<Choice> choices = new ArrayList<>();
        for (var appearance : NpcAppearance.values()) {
            choices.add(choice("appearance." + appearance.id(), () -> {
                npc.appearance(appearance);
                edit(player, npc);
            }));
        }
        choices.add(choice("back", () -> edit(player, npc)));
        show(player, npc, true, text("appearance"), ItemStack.EMPTY, Field.NONE, choices);
    }

    private static void editOffer(ServerPlayer player, RegnumNpc npc, Component feedback) {
        if (!Npcs.canManageStock(player, npc)) { edit(player, npc); return; }
        var held = player.getMainHandItem().copyWithCount(1);
        var catalog = NpcMerchants.catalog(player, npc);
        var current = catalog.goods().stream().filter(quote -> ItemStack.isSameItemSameComponents(quote.item(), held))
                .findFirst().orElse(null);
        String initial = current == null ? "" : current.quantity() + " " + current.ask() + " " + current.bid();
        var body = text("admin.offer", Long.toString(catalog.funds()));
        show(player, npc, true, withFeedback(feedback, body), held, new Field(45, text("admin.offer_hint"), initial), List.of(
                choice("back", () -> edit(player, npc)),
                new Choice(text("save"), value -> {
                    var result = NpcMerchants.Result.INVALID;
                    String[] terms = value.strip().split(" +");
                    if (terms.length == 3 && ItemStack.isSameItemSameComponents(held, player.getMainHandItem())) {
                        try {
                            result = NpcMerchants.offer(player, npc, Integer.parseInt(terms[0]),
                                    Long.parseLong(terms[1]), Long.parseLong(terms[2]));
                        } catch (NumberFormatException ignored) { }
                    }
                    editOffer(player, npc, result.message());
                })));
    }

    private static Choice back(ServerPlayer player, RegnumNpc npc) {
        return choice("back", () -> welcome(player, npc));
    }

    private static Choice choice(String key, Runnable action) {
        return new Choice(text(key), ignored -> action.run());
    }

    private static void show(ServerPlayer player, RegnumNpc npc, boolean editing, Component body, ItemStack item,
                             Field field, List<Choice> choices) {
        UUID token = issue(player, npc, editing, field.limit, choices);
        ServerPlayNetworking.send(player, new NpcPackets.Page(token, npc.getName(), npc.roleLabel(), body, item,
                choices.stream().map(Choice::label).toList(), field.limit, field.hint, field.value));
    }

    static UUID issue(ServerPlayer player, RegnumNpc npc, boolean editing, int inputLimit, List<Choice> choices) {
        UUID token = UUID.randomUUID();
        SESSIONS.put(player, new Session(npc, new Ticket(token, Util.getMillis(), choices.size(), inputLimit), editing, List.copyOf(choices)));
        return token;
    }
}
