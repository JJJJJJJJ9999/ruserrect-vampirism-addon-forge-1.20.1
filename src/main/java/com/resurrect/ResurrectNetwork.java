package com.resurrect;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class ResurrectNetwork {
    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ResurrectMod.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    /** Client side: whether the death the player is currently looking at can be resurrected from. */
    public static volatile boolean clientCanResurrect = false;

    /** Client -> server: "I pressed Resurrect". Carries no data. */
    public static class RequestPacket {
    }

    /** Server -> client: sent with every player death, says if the Resurrect button should be offered. */
    public static class StatusPacket {
        public final boolean allowed;

        public StatusPacket(boolean allowed) {
            this.allowed = allowed;
        }
    }

    public static void register() {
        CHANNEL.messageBuilder(RequestPacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((msg, buf) -> { })
                .decoder(buf -> new RequestPacket())
                .consumerMainThread((msg, ctx) -> {
                    ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) {
                        ResurrectManager.handleRequest(sender);
                    }
                })
                .add();

        CHANNEL.messageBuilder(StatusPacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> buf.writeBoolean(msg.allowed))
                .decoder(buf -> new StatusPacket(buf.readBoolean()))
                .consumerMainThread((msg, ctx) -> {
                    clientCanResurrect = msg.allowed;
                })
                .add();
    }

    public static void sendStatus(ServerPlayer player, boolean allowed) {
        if (player.connection == null) return; // fake players
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StatusPacket(allowed));
    }
}
