package com.resurrect;

import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ResurrectMod.MODID);

    public static final RegistryObject<MobEffect> RESURRECTING =
            EFFECTS.register("resurrecting", ResurrectEffect::new);
}
