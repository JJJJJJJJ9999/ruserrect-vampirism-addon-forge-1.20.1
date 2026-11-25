package com.resurrect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** Marker effect: its presence (and remaining duration) drives the cutscene on server and clients. */
public class ResurrectEffect extends MobEffect {
    public ResurrectEffect() {
        super(MobEffectCategory.NEUTRAL, 0x8B0000);
    }
}
