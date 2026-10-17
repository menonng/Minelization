package com.grandstrategy.crowdperf;

/** Added to every entity: whether it is currently drawn from the batch (and so skipped by the normal renderer). */
public interface CrowdEntity {
    boolean crowdperf$batched();

    void crowdperf$setBatched(boolean batched);

    /** Whether the entity is in the far batch (decided when that batch is rebuilt). */
    boolean crowdperf$far();

    void crowdperf$setFar(boolean far);

    /** Packed light of the batched entity, cached until it moves to another block or the cache is renewed. */
    int crowdperf$light();

    long crowdperf$lightKey();

    void crowdperf$setLight(int light, long key);

    /** The atlas slot of the entity's look, valid while {@link #crowdperf$lookKey()} is the same object. */
    int crowdperf$slot();

    Object crowdperf$lookKey();

    void crowdperf$setSlot(int slot, Object key);
}
