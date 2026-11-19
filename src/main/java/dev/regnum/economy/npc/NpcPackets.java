package dev.regnum.economy.npc;

import dev.regnum.economy.RegnumEconomy;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

public final class NpcPackets {
    public static final int MAX_CHOICES = 8;
    public static final int SHOP_PAGE_SIZE = 3;

    private NpcPackets() { }

    static void register() {
        PayloadTypeRegistry.playC2S().register(Select.TYPE, Select.CODEC);
        PayloadTypeRegistry.playS2C().register(Page.TYPE, Page.CODEC);
    }

    public record Select(UUID token, int choice, String input) implements CustomPacketPayload {
        public static final Type<Select> TYPE = new Type<>(RegnumEconomy.id("npc_select"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Select> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Select::token, ByteBufCodecs.VAR_INT, Select::choice,
                ByteBufCodecs.stringUtf8(RegnumNpc.DIALOGUE_LIMIT), Select::input, Select::new);

        @Override public Type<Select> type() { return TYPE; }
    }

    public enum ShopView { CATALOGUE, BUYER, PURCHASE, SALE }

    public enum ShopAction { FIRST, SECOND, THIRD, BUY, SELL, PREVIOUS, NEXT, BACK, CANCEL, CONFIRM }

    public record Shop(ShopView view, long wallet, List<ItemStack> listings, int selected, int page, int pages,
                       int quantity, int stock, int matching, long ask, long bid, Component buyIssue,
                       Component sellIssue, List<ShopAction> actions) {
        private static final StreamCodec<RegistryFriendlyByteBuf, List<ItemStack>> LISTINGS =
                ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list(SHOP_PAGE_SIZE));

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeEnum(view);
            buffer.writeVarLong(wallet);
            LISTINGS.encode(buffer, listings);
            buffer.writeVarInt(selected);
            buffer.writeVarInt(page);
            buffer.writeVarInt(pages);
            buffer.writeVarInt(quantity);
            buffer.writeVarInt(stock);
            buffer.writeVarInt(matching);
            buffer.writeVarLong(ask);
            buffer.writeVarLong(bid);
            ComponentSerialization.STREAM_CODEC.encode(buffer, buyIssue);
            ComponentSerialization.STREAM_CODEC.encode(buffer, sellIssue);
            buffer.writeCollection(actions, (buf, action) -> buf.writeEnum(action));
        }

        private static Shop read(RegistryFriendlyByteBuf buffer) {
            return new Shop(buffer.readEnum(ShopView.class), buffer.readVarLong(), LISTINGS.decode(buffer),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarLong(), buffer.readVarLong(),
                    ComponentSerialization.STREAM_CODEC.decode(buffer), ComponentSerialization.STREAM_CODEC.decode(buffer),
                    buffer.readCollection(net.minecraft.network.FriendlyByteBuf.limitValue(java.util.ArrayList::new, MAX_CHOICES),
                            buf -> buf.readEnum(ShopAction.class)));
        }

        public boolean review() { return view == ShopView.PURCHASE || view == ShopView.SALE; }
    }

    public record Page(UUID token, Component name, Component role, Component body, ItemStack item,
                       List<Component> choices, int inputLimit, Component inputHint, String inputValue, Shop shop)
            implements CustomPacketPayload {
        public static final Type<Page> TYPE = new Type<>(RegnumEconomy.id("npc_page_v2"));
        private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> CHOICES =
                ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CHOICES));

        public Page(UUID token, Component name, Component role, Component body, ItemStack item,
                    List<Component> choices, int inputLimit, Component inputHint, String inputValue) {
            this(token, name, role, body, item, choices, inputLimit, inputHint, inputValue, null);
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, Page> CODEC =
                StreamCodec.of((buffer, page) -> {
                    buffer.writeUUID(page.token());
                    ComponentSerialization.STREAM_CODEC.encode(buffer, page.name());
                    ComponentSerialization.STREAM_CODEC.encode(buffer, page.role());
                    ComponentSerialization.STREAM_CODEC.encode(buffer, page.body());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, page.item());
                    CHOICES.encode(buffer, page.choices());
                    buffer.writeVarInt(page.inputLimit());
                    ComponentSerialization.STREAM_CODEC.encode(buffer, page.inputHint());
                    buffer.writeUtf(page.inputValue(), RegnumNpc.DIALOGUE_LIMIT);
                    buffer.writeBoolean(page.shop() != null);
                    if (page.shop() != null) page.shop().write(buffer);
                }, buffer -> new Page(buffer.readUUID(), ComponentSerialization.STREAM_CODEC.decode(buffer),
                        ComponentSerialization.STREAM_CODEC.decode(buffer), ComponentSerialization.STREAM_CODEC.decode(buffer),
                        ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer), CHOICES.decode(buffer),
                        buffer.readVarInt(), ComponentSerialization.STREAM_CODEC.decode(buffer),
                        buffer.readUtf(RegnumNpc.DIALOGUE_LIMIT), buffer.readBoolean() ? Shop.read(buffer) : null));

        @Override public Type<Page> type() { return TYPE; }
    }
}
