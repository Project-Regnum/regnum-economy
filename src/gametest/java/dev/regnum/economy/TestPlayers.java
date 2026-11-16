package dev.regnum.economy;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

/** Vanilla GameTest mocks need a listener for transaction sync and later recipe/advancement ticks. */
public final class TestPlayers {
    private TestPlayers() { }
    public static ServerPlayer create(GameTestHelper h) {
        var player=h.makeMockServerPlayerInLevel();
        connect(player); return player;
    }
    public static void connect(ServerPlayer player) {
        if (player.connection==null) player.connection=new ServerGamePacketListenerImpl(player.serverLevel().getServer(),
                new Connection(PacketFlow.SERVERBOUND),player,CommonListenerCookie.createInitial(player.getGameProfile(),false));
    }
}
