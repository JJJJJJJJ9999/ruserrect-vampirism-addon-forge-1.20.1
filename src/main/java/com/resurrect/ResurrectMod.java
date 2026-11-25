package com.resurrect;

import com.mojang.logging.LogUtils;
import com.resurrect.client.ClientEvents;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ResurrectMod.MODID)
public class ResurrectMod {
    public static final String MODID = "resurrect";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Seconds the player must wait on the death screen before Resurrect unlocks. */
    public static final int WAIT_SECONDS = 15;
    /** Length of the regeneration cutscene in ticks (20 ticks = 1 second). */
    public static final int CUTSCENE_TICKS = 160;

    public ResurrectMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEffects.EFFECTS.register(modBus);
        modBus.addListener(this::commonSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ResurrectConfig.SPEC);
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientEvents::init);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ResurrectNetwork.register();
            VampirismHook.init();
        });
    }

    /** 0 = just started, 1 = fully regenerated. */
    public static float progress(float remainingTicks) {
        return Mth.clamp(1.0F - remainingTicks / (float) CUTSCENE_TICKS, 0.0F, 1.0F);
    }
}
