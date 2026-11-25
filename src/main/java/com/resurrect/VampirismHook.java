package com.resurrect;

import java.lang.reflect.Method;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.LazyOptional;

/**
 * The only place that talks to Vampirism. It uses reflection on Vampirism's public API classes
 * (verified against Vampirism 1.10.x for 1.20.1) so this addon builds without Vampirism on the compile classpath.
 * If anything is missing the hook disables itself and Vampirism's own down-but-not-out keeps working untouched.
 */
final class VampirismHook {
    private static Method accessor;   // VampirismAPI.getVampirePlayer(Player)
    private static Method getLevel;   // IFactionPlayer.getLevel()
    private static Method isDown;     // IVampirePlayer.isDBNO()
    private static boolean ready;
    private static boolean warned;

    private VampirismHook() { }

    static void init() {
        try {
            Class<?> api = Class.forName("de.teamlapen.vampirism.api.VampirismAPI");
            Class<?> vampire = Class.forName("de.teamlapen.vampirism.api.entity.player.vampire.IVampirePlayer");
            accessor = api.getMethod("getVampirePlayer", Player.class);
            getLevel = vampire.getMethod("getLevel");
            isDown = vampire.getMethod("isDBNO");
            ready = true;
            ResurrectMod.LOGGER.info("Resurrect: hooked into Vampirism, down-but-not-out is replaced by Resurrect.");
        } catch (ReflectiveOperationException | LinkageError e) {
            ResurrectMod.LOGGER.error("Resurrect: could not hook into Vampirism, Vampirism's own down-but-not-out stays active.", e);
        }
    }

    private static Object vampire(Player player) {
        if (!ready) return null;
        try {
            Object r = accessor.invoke(null, player);
            if (r instanceof LazyOptional<?> lazy) {
                return lazy.resolve().orElse(null);
            }
            return r;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** True while Vampirism holds this player in its down-but-not-out state. */
    static boolean isDown(ServerPlayer player) {
        Object vp = vampire(player);
        if (vp == null) return false;
        try {
            return (Boolean) isDown.invoke(vp);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Vampire level, 0 if not a vampire. */
    static int level(ServerPlayer player) {
        Object vp = vampire(player);
        if (vp == null) return 0;
        try {
            return (Integer) getLevel.invoke(vp);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * Ends Vampirism's down state and lets the player really die. Uses the same method as the
     * "give up" button of Vampirism's own down screen (keeps the original death message), falling back to /kill.
     */
    static void endDownAndDie(ServerPlayer player) {
        Object vp = vampire(player);
        if (vp != null) {
            try {
                vp.getClass().getMethod("giveUpDBNO").invoke(vp);
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (!warned) {
                    warned = true;
                    ResurrectMod.LOGGER.warn("Resurrect: Vampirism giveUpDBNO unavailable, using a plain kill instead.", e);
                }
            }
        }
        if (!player.isDeadOrDying()) {
            player.kill();
        }
    }
}
