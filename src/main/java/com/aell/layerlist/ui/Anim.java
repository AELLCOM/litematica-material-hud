package com.aell.layerlist.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class Anim
{
    private final float durationMs;

    private float value;
    private long updatedAt;

    public Anim(float durationMs)
    {
        this.durationMs = durationMs;
    }

    public float update(boolean towardsOne)
    {
        long now = System.nanoTime();

        if (this.updatedAt == 0L)
        {
            this.updatedAt = now;
            this.value = towardsOne ? 1.0F : 0.0F;

            return this.value;
        }

        float deltaMs = (now - this.updatedAt) / 1_000_000.0F;
        this.updatedAt = now;

        float step = this.durationMs <= 0.0F ? 1.0F : Math.min(1.0F, deltaMs / this.durationMs);
        float target = towardsOne ? 1.0F : 0.0F;

        this.value += (target - this.value) * step;

        if (Math.abs(target - this.value) < 0.004F)
        {
            this.value = target;
        }

        return eased(this.value);
    }

    public float value()
    {
        return eased(this.value);
    }

    private static float eased(float t)
    {
        return t * t * (3.0F - 2.0F * t);
    }
}
