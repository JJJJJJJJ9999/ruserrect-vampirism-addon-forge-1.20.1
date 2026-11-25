package com.resurrect.client;

import com.resurrect.ModEffects;
import com.resurrect.ResurrectMod;
import com.resurrect.ResurrectNetwork;
import org.joml.Vector3f;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.Input;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

public class ClientEvents {
    private static Screen lastScreen;
    private static Button button;
    private static long openedAt;

    private static boolean wasResurrecting;
    private static CameraType savedCamera = CameraType.FIRST_PERSON;
    private static BlockState[] gore;

    public static void init() {
        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener(ClientEvents::onScreenInit);
        bus.addListener(ClientEvents::onScreenRender);
        bus.addListener(ClientEvents::onClientTick);
        bus.addListener(ClientEvents::onRenderPre);
        bus.addListener(ClientEvents::onRenderPost);
        bus.addListener(ClientEvents::onMovementInput);
        bus.addListener(ClientEvents::onInteract);
    }

    // ------------------------------------------------------------------ death screen button

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof DeathScreen)) return;
        button = null;
        // The server tells us per death whether it is resurrectable (downed vampire) or final (sunlight, stake, ...)
        if (!ResurrectNetwork.clientCanResurrect) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getLevelData().isHardcore()) return;

        if (screen != lastScreen) { // init() is re-run on window resize; keep the timer in that case
            lastScreen = screen;
            openedAt = System.currentTimeMillis();
        }

        button = Button.builder(Component.translatable("button.resurrect.waiting", ResurrectMod.WAIT_SECONDS),
                        b -> onPress())
                .bounds(screen.width / 2 - 100, screen.height / 4 + 120, 200, 20)
                .build();
        button.active = false;
        event.addListener(button);
    }

    private static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (button == null || event.getScreen() != lastScreen || !(event.getScreen() instanceof DeathScreen)) return;
        long left = ResurrectMod.WAIT_SECONDS * 1000L - (System.currentTimeMillis() - openedAt);
        if (left > 0) {
            button.active = false;
            button.setMessage(Component.translatable("button.resurrect.waiting", (int) Math.ceil(left / 1000.0D)));
        } else {
            button.active = true;
            button.setMessage(Component.translatable("button.resurrect.resurrect"));
        }
    }

    private static void onPress() {
        Minecraft mc = Minecraft.getInstance();
        // 1) tell the server we want to resurrect, 2) send the normal vanilla respawn request
        ResurrectNetwork.CHANNEL.sendToServer(new ResurrectNetwork.RequestPacket());
        if (mc.player != null) {
            mc.player.respawn();
        }
        mc.setScreen(null);
    }

    // ------------------------------------------------------------------ cutscene: camera + gore

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            wasResurrecting = false;
            return;
        }

        boolean local = mc.player.hasEffect(ModEffects.RESURRECTING.get());
        if (local && !wasResurrecting) {
            savedCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        } else if (!local && wasResurrecting) {
            mc.options.setCameraType(savedCamera);
        }
        wasResurrecting = local;
        if (local) {
            // frozen in place for the whole cutscene (the server also pins the position)
            mc.player.setDeltaMovement(Vec3.ZERO);
        }

        if (mc.isPaused()) return;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p.hasEffect(ModEffects.RESURRECTING.get())) {
                spawnGore(mc, p);
            }
        }
    }

    private static BlockState[] goreStates() {
        if (gore == null) {
            gore = new BlockState[] {
                    Blocks.REDSTONE_BLOCK.defaultBlockState(),
                    Blocks.NETHER_WART_BLOCK.defaultBlockState(),
                    Blocks.NETHERRACK.defaultBlockState(),
                    Blocks.RED_MUSHROOM_BLOCK.defaultBlockState(),
                    Blocks.NETHER_WART_BLOCK.defaultBlockState(),
                    Blocks.BONE_BLOCK.defaultBlockState()
            };
        }
        return gore;
    }

    private static void spawnGore(Minecraft mc, Player p) {
        MobEffectInstance inst = p.getEffect(ModEffects.RESURRECTING.get());
        if (inst == null) return;

        float progress = ResurrectMod.progress(inst.getDuration());
        double height = p.getBbHeight();
        double front = progress * height; // height of the "assembly front", measured from the feet
        RandomSource r = mc.level.random;
        BlockState[] states = goreStates();

        for (int i = 0; i < 9; i++) {
            double ang = r.nextDouble() * Math.PI * 2.0D;
            double rad = 1.4D + r.nextDouble() * 2.2D;
            double sx = p.getX() + Math.cos(ang) * rad;
            double sz = p.getZ() + Math.sin(ang) * rad;
            // most chunks start near the assembly front, some crawl in along the floor
            double sy = r.nextInt(3) == 0
                    ? p.getY() + 0.05D
                    : p.getY() + Math.max(0.0D, front + (r.nextDouble() - 0.5D) * 1.4D);
            double ty = Mth.clamp(front + (r.nextDouble() - 0.5D) * 0.25D, 0.05D, height);
            double ox = (r.nextDouble() - 0.5D) * 0.4D;
            double oz = (r.nextDouble() - 0.5D) * 0.4D;
            mc.particleEngine.add(new GoreParticle(mc.level, sx, sy, sz, p, ty, ox, oz,
                    states[r.nextInt(states.length)]));
        }

        for (int i = 0; i < 3; i++) {
            mc.level.addParticle(new DustParticleOptions(new Vector3f(0.55F, 0.03F, 0.05F), 1.3F),
                    p.getX() + (r.nextDouble() - 0.5D) * 0.6D,
                    p.getY() + front,
                    p.getZ() + (r.nextDouble() - 0.5D) * 0.6D,
                    0.0D, 0.0D, 0.0D);
        }
    }

    // ------------------------------------------------------------------ cutscene: body assembles feet -> head

    private static void onRenderPre(RenderPlayerEvent.Pre event) {
        MobEffectInstance inst = event.getEntity().getEffect(ModEffects.RESURRECTING.get());
        float progress = inst == null ? 1.0F : ResurrectMod.progress(inst.getDuration() - event.getPartialTick());
        applyVisibility(event.getRenderer().getModel(), progress);
    }

    private static void onRenderPost(RenderPlayerEvent.Post event) {
        applyVisibility(event.getRenderer().getModel(), 1.0F);
    }

    /** Body parts pop back in from the feet upward. skipDraw survives vanilla's visibility resets. */
    private static void applyVisibility(PlayerModel<AbstractClientPlayer> m, float progress) {
        boolean rightLeg = progress < 0.12F;
        boolean leftLeg = progress < 0.20F;
        boolean torso = progress < 0.42F;
        boolean rightArm = progress < 0.60F;
        boolean leftArm = progress < 0.70F;
        boolean head = progress < 0.86F;

        m.rightLeg.skipDraw = rightLeg;
        m.rightPants.skipDraw = rightLeg;
        m.leftLeg.skipDraw = leftLeg;
        m.leftPants.skipDraw = leftLeg;
        m.body.skipDraw = torso;
        m.jacket.skipDraw = torso;
        m.rightArm.skipDraw = rightArm;
        m.rightSleeve.skipDraw = rightArm;
        m.leftArm.skipDraw = leftArm;
        m.leftSleeve.skipDraw = leftArm;
        m.head.skipDraw = head;
        m.hat.skipDraw = head;
    }

    // ------------------------------------------------------------------ cutscene: no control

    private static void onMovementInput(MovementInputUpdateEvent event) {
        if (event.getEntity().hasEffect(ModEffects.RESURRECTING.get())) {
            Input in = event.getInput();
            in.up = false;
            in.down = false;
            in.left = false;
            in.right = false;
            in.forwardImpulse = 0.0F;
            in.leftImpulse = 0.0F;
            in.jumping = false;
            in.shiftKeyDown = false;
        }
    }

    private static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.hasEffect(ModEffects.RESURRECTING.get())) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }
}
