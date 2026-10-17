package com.grandstrategy.crowdperf;

/** Added to every entity: whether it is currently drawn from the batch (and so skipped by the normal renderer). */
public interface CrowdEntity {
    boolean crowdperf$batched();

    void crowdperf$setBatched(boolean batched);

    /** Tick of the last position update from the server, and the average gap between updates (0 = unknown). */
    int crowdperf$lastUpdate();

    void crowdperf$setLastUpdate(int tick);

    float crowdperf$updateInterval();

    void crowdperf$setUpdateInterval(float ticks);

    /** Packed light of the batched entity, cached until it moves to another block or the cache is renewed. */
    int crowdperf$light();

    long crowdperf$lightKey();

    void crowdperf$setLight(int light, long key);

    /** The atlas slot of the entity's look, valid while {@link #crowdperf$lookKey()} is the same object. */
    int crowdperf$slot();

    Object crowdperf$lookKey();

    void crowdperf$setSlot(int slot, Object key);
}
