package com.grandstrategy.crowdperf.mixin;

import com.grandstrategy.crowdperf.CrowdEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Entity.class)
public abstract class EntityMixin implements CrowdEntity {
    @Unique
    private boolean crowdperf$batched;
    @Unique
    private int crowdperf$lastUpdate;
    @Unique
    private float crowdperf$updateInterval;
    @Unique
    private int crowdperf$light;
    @Unique
    private long crowdperf$lightKey = Long.MIN_VALUE;
    @Unique
    private int crowdperf$slot;
    @Unique
    private Object crowdperf$lookKey;

    @Override
    public boolean crowdperf$batched() {
        return this.crowdperf$batched;
    }

    @Override
    public void crowdperf$setBatched(boolean batched) {
        this.crowdperf$batched = batched;
    }

    @Override
    public int crowdperf$lastUpdate() {
        return this.crowdperf$lastUpdate;
    }

    @Override
    public void crowdperf$setLastUpdate(int tick) {
        this.crowdperf$lastUpdate = tick;
    }

    @Override
    public float crowdperf$updateInterval() {
        return this.crowdperf$updateInterval;
    }

    @Override
    public void crowdperf$setUpdateInterval(float ticks) {
        this.crowdperf$updateInterval = ticks;
    }

    @Override
    public int crowdperf$light() {
        return this.crowdperf$light;
    }

    @Override
    public long crowdperf$lightKey() {
        return this.crowdperf$lightKey;
    }

    @Override
    public void crowdperf$setLight(int light, long key) {
        this.crowdperf$light = light;
        this.crowdperf$lightKey = key;
    }

    @Override
    public int crowdperf$slot() {
        return this.crowdperf$slot;
    }

    @Override
    public Object crowdperf$lookKey() {
        return this.crowdperf$lookKey;
    }

    @Override
    public void crowdperf$setSlot(int slot, Object key) {
        this.crowdperf$slot = slot;
        this.crowdperf$lookKey = key;
    }
}
