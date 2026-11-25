package com.resurrect;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-side logic.
 * <ul>
 *   <li>Turns Vampirism's down-but-not-out state into a real death. Vampirism itself decides who gets downed
 *       (so sunlight, stakes, hunters, holy water, ... still kill outright); only a downed vampire of high enough
 *       level is marked as resurrectable.</li>
 *   <li>Remembers where players died, validates Resurrect requests and runs the cutscene.</li>
 *   <li>Revives on a safe spot and keeps the player frozen in place during the cutscene.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ResurrectMod.MODID)
public class ResurrectManager {
    private static final long WAIT_MS = ResurrectMod.WAIT_SECONDS * 1000L;
    private static final long TOLERANCE_MS = 750L;
    private static final int MAX_DOWN_ATTEMPTS = 5;

    private record DeathRecord(long millis, ResourceKey<Level> dim, double x, double y, double z,
                               float yRot, float xRot, boolean allowed) { }
    private record Pending(DeathRecord death, long requestedMillis) { }
    private record Anchor(ResourceKey<Level> dim, double x, double y, double z) { }

    private static final Map<UUID, DeathRecord> DEATHS = new ConcurrentHashMap<>();
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Anchor> FROZEN = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> ATTEMPTS = new ConcurrentHashMap<>();
    /** Players about to die from a converted down state that may resurrect afterwards. */
    private static final Set<UUID> ELIGIBLE = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------ death bookkeeping

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        UUID id = sp.getUUID();
        boolean allowed = ELIGIBLE.remove(id) && !sp.server.isHardcore();
        DEATHS.put(id, new DeathRecord(System.currentTimeMillis(), sp.level().dimension(),
                sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), sp.getXRot(), allowed));
        PENDING.remove(id);
        ATTEMPTS.remove(id);
        unfreeze(sp);
        ResurrectNetwork.sendStatus(sp, allowed);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && sp.hasEffect(ModEffects.RESURRECTING.get())) {
            // logged out in the middle of a cutscene: the saved player may still have gravity switched off
            sp.removeEffect(ModEffects.RESURRECTING.get());
            sp.setNoGravity(false);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        if (event.getEntity() instanceof ServerPlayer sp) {
            unfreeze(sp);
        }
        DEATHS.remove(id);
        PENDING.remove(id);
        ATTEMPTS.remove(id);
        ELIGIBLE.remove(id);
    }

    /** Called when the client presses Resurrect (just before it sends the normal respawn request). */
    public static void handleRequest(ServerPlayer player) {
        if (!player.isDeadOrDying() || player.server.isHardcore()) return;
        DeathRecord death = DEATHS.get(player.getUUID());
        if (death == null || !death.allowed()) return;
        long now = System.currentTimeMillis();
        if (now - death.millis() < WAIT_MS - TOLERANCE_MS) return; // too early
        PENDING.put(player.getUUID(), new Pending(death, now));
    }

    // ------------------------------------------------------------------ per-tick work

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer sp)) return;
        UUID id = sp.getUUID();

        // 1) Vampirism put this vampire into its down-but-not-out state: replace that with a real death
        if (!sp.isDeadOrDying()) {
            if (VampirismHook.isDown(sp)) {
                convertDownedVampire(sp);
            } else {
                ATTEMPTS.remove(id);
            }
        }

        // 2) a Resurrect request is waiting for the vanilla respawn to finish
        Pending pending = PENDING.get(id);
        if (pending != null) {
            if (System.currentTimeMillis() - pending.requestedMillis() > 15_000L) {
                PENDING.remove(id);
            } else if (!sp.isDeadOrDying()) {
                // The vanilla respawn has completed and this is the fresh player entity.
                PENDING.remove(id);
                startResurrection(sp, pending.death());
            }
        }

        // 3) cutscene
        MobEffectInstance inst = sp.getEffect(ModEffects.RESURRECTING.get());
        if (inst != null) {
            holdInPlace(sp);
            tickCutscene(sp, inst);
        } else if (FROZEN.containsKey(id)) {
            unfreeze(sp); // effect was removed early (milk, /effect clear, ...)
        }
    }

    private static void convertDownedVampire(ServerPlayer sp) {
        UUID id = sp.getUUID();
        int tries = ATTEMPTS.getOrDefault(id, 0);
        if (tries >= MAX_DOWN_ATTEMPTS) return; // something keeps cancelling the death, leave Vampirism's state alone
        ATTEMPTS.put(id, tries + 1);

        if (VampirismHook.level(sp) >= ResurrectConfig.MIN_VAMPIRE_LEVEL.get()) {
            ELIGIBLE.add(id);
        } else {
            ELIGIBLE.remove(id);
        }
        VampirismHook.endDownAndDie(sp);
        if (!sp.isDeadOrDying()) {
            ELIGIBLE.remove(id);
        }
    }

    // ------------------------------------------------------------------ resurrection

    private static void startResurrection(ServerPlayer sp, DeathRecord d) {
        ServerLevel level = sp.server.getLevel(d.dim());
        Vec3 spot = level == null ? null : findSafeSpot(level, d);
        if (spot != null) {
            sp.teleportTo(level, spot.x, spot.y, spot.z, d.yRot(), d.xRot());
        } // else: died in the void / buried too deep -> just stay where vanilla respawned us

        // freeze: no gravity, no drifting, pinned to this exact spot until the cutscene ends
        sp.setNoGravity(true);
        sp.setDeltaMovement(Vec3.ZERO);
        sp.fallDistance = 0.0F;
        sp.clearFire();
        FROZEN.put(sp.getUUID(), new Anchor(sp.level().dimension(), sp.getX(), sp.getY(), sp.getZ()));

        int total = ResurrectMod.CUTSCENE_TICKS;
        sp.addEffect(new MobEffectInstance(ModEffects.RESURRECTING.get(), total, 0, false, false, false));
        sp.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, total + 60, 4, false, false, false));
        sp.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, total + 100, 0, false, false, false));
    }

    /**
     * Finds a standing spot at the place of death. Lifts the player out of blocks (suffocation deaths, slabs, sand)
     * and settles them onto the floor, so they never end up inside the ground and fall through it.
     * Returns null when no sane spot exists (void, buried deep, nothing below for 64 blocks).
     */
    private static Vec3 findSafeSpot(ServerLevel level, DeathRecord d) {
        double minY = level.getMinBuildHeight();
        if (d.y() <= minY) return null;

        double x = d.x();
        double z = d.z();
        level.getChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4); // make sure the ground is actually loaded

        // 1) climb out of any blocks (up to 12 blocks)
        double y = d.y();
        boolean clear = false;
        for (int i = 0; i <= 48; i++) {
            double ty = d.y() + i * 0.25D;
            if (fits(level, x, ty, z)) {
                y = ty;
                clear = true;
                break;
            }
        }
        if (!clear) return null;

        // 2) died in water/lava: stay at that height
        if (!level.getFluidState(BlockPos.containing(x, y + 0.5D, z)).isEmpty()) {
            return new Vec3(x, y, z);
        }

        // 3) died in mid-air: settle onto the first floor below (up to 64 blocks)
        for (int i = 0; i < 1280; i++) {
            if (!fits(level, x, y - 0.05D, z)) {
                return new Vec3(x, y, z);
            }
            y -= 0.05D;
            if (y <= minY) return null;
        }
        return null;
    }

    private static boolean fits(ServerLevel level, double x, double y, double z) {
        return level.noCollision(new AABB(x - 0.3D, y, z - 0.3D, x + 0.3D, y + 1.8D, z + 0.3D));
    }

    /** Keeps the player pinned to the cutscene spot (mobs, water, pistons, lag... cannot move them). */
    private static void holdInPlace(ServerPlayer sp) {
        Anchor a = FROZEN.get(sp.getUUID());
        if (a == null) return;
        sp.setDeltaMovement(Vec3.ZERO);
        sp.fallDistance = 0.0F;
        boolean sameLevel = sp.level().dimension().equals(a.dim());
        if (!sameLevel || sp.position().distanceToSqr(a.x(), a.y(), a.z()) > 0.0025D) {
            ServerLevel level = sp.server.getLevel(a.dim());
            if (level != null) {
                sp.teleportTo(level, a.x(), a.y(), a.z(), sp.getYRot(), sp.getXRot());
            }
        }
    }

    private static void unfreeze(ServerPlayer sp) {
        if (FROZEN.remove(sp.getUUID()) != null) {
            sp.setNoGravity(false);
            sp.fallDistance = 0.0F;
        }
    }

    private static void tickCutscene(ServerPlayer sp, MobEffectInstance inst) {
        int remaining = inst.getDuration();
        int elapsed = ResurrectMod.CUTSCENE_TICKS - remaining;
        ServerLevel level = sp.serverLevel();

        if (elapsed % 20 == 1) {
            level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.WARDEN_HEARTBEAT,
                    SoundSource.PLAYERS, 1.2F, 0.8F);
        }
        if (elapsed % 5 == 0) {
            level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.SLIME_SQUISH,
                    SoundSource.PLAYERS, 0.6F, 0.5F + sp.getRandom().nextFloat() * 0.4F);
        }
        if (remaining == 1) {
            level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ZOMBIE_VILLAGER_CURE,
                    SoundSource.PLAYERS, 1.0F, 0.9F);
        }
    }
}
