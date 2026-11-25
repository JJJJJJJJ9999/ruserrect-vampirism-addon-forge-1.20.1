package com.resurrect.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/** A chunk of "flesh" that flies inward toward the body axis of the player being reassembled. */
public class GoreParticle extends TerrainParticle {
    private final Entity target;
    private final double targetHeight;
    private final double offX;
    private final double offZ;
    private final double swirl;

    public GoreParticle(ClientLevel level, double x, double y, double z, Entity target,
                        double targetHeight, double offX, double offZ, BlockState state) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D, state);
        this.target = target;
        this.targetHeight = targetHeight;
        this.offX = offX;
        this.offZ = offZ;
        this.swirl = (this.random.nextDouble() - 0.5D) * 0.06D;
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.lifetime = 30 + this.random.nextInt(20);
        this.xd = 0.0D;
        this.yd = 0.0D;
        this.zd = 0.0D;
        this.quadSize = 0.05F + this.random.nextFloat() * 0.07F;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;

        if (this.age++ >= this.lifetime || this.target.isRemoved()) {
            this.remove();
            return;
        }

        double gx = this.target.getX() + this.offX;
        double gy = this.target.getY() + this.targetHeight;
        double gz = this.target.getZ() + this.offZ;
        double dx = gx - this.x;
        double dy = gy - this.y;
        double dz = gz - this.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.2D) {
            this.remove();
            return;
        }

        double speed = Math.min(0.10D + this.age * 0.02D, 0.55D);
        speed = Math.min(speed, dist);
        double nx = dx / dist;
        double ny = dy / dist;
        double nz = dz / dist;

        // slight spiral so the gore wraps around the body as it streams in
        this.move(nx * speed - nz * this.swirl * dist, ny * speed, nz * speed + nx * this.swirl * dist);
    }
}
