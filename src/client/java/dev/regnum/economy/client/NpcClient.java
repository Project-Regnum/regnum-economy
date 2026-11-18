package dev.regnum.economy.client;

import dev.regnum.economy.RegnumEconomy;
import dev.regnum.economy.npc.Npcs;
import dev.regnum.economy.npc.NpcPackets;
import dev.regnum.economy.npc.RegnumNpc;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import dev.regnum.economy.npc.NpcAppearance;

final class NpcClient {
    private NpcClient() { }

    static void register() {
        EntityRendererRegistry.register(Npcs.TYPE, Renderer::new);
        ClientPlayNetworking.registerGlobalReceiver(NpcPackets.Page.TYPE, (page, context) -> {
            var client = context.client();
            if (client.player == null || client.level == null) return;
            if (client.screen instanceof NpcScreen npc) npc.update(page);
            else if (client.screen == null || client.screen instanceof net.minecraft.client.gui.screens.ChatScreen) {
                client.setScreen(new NpcScreen(page));
            }
        });
    }

    private static final class Renderer extends MobRenderer<RegnumNpc, PlayerModel<RegnumNpc>> {
        private static final Map<NpcAppearance, ResourceLocation> SKINS = new EnumMap<>(NpcAppearance.class);

        static {
            for (var appearance : NpcAppearance.values()) {
                SKINS.put(appearance, RegnumEconomy.id("textures/entity/npc/" + appearance.id() + ".png"));
            }
        }

        Renderer(EntityRendererProvider.Context context) {
            super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.45F);
        }

        @Override
        public ResourceLocation getTextureLocation(RegnumNpc npc) {
            return SKINS.get(npc.appearance());
        }
    }
}
