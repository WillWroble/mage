package mage.util;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.UnaryOperator;

/**
 * Copy-on-access holder for a part of an object (e.g. an ability's modes): copies of the object share the holder
 * instead of copying the part, and a holder that is not the only one copies the part before its first access.
 * <p>
 * Only a part that was never accessed is shared: an access hands out references into the part (effects, targets)
 * that its user may keep and change later, so once accessed, the part is copied as before when its object is.
 * <p>
 * Usage: {@code part = part.share(copier)} in the copy constructor, {@code part = part.owned(copier)} before every
 * access. Holders are counted, never uncounted when they are collected, so a count can be too high (an extra copy)
 * but never too low. The count is atomic because MCTS snapshots are copied from several threads.
 */
public final class SharedPart<T> implements Serializable {

    private static final AtomicIntegerFieldUpdater<SharedPart> HOLDERS = AtomicIntegerFieldUpdater.newUpdater(SharedPart.class, "holders");

    private final T value;
    private volatile int holders = 1;
    private boolean exposed = false; // accessed: references into the part may be kept elsewhere

    public SharedPart(T value) {
        this.value = value;
    }

    /**
     * Holder for the copy of the part's object: this one shared, or a new one with a copy of an accessed part
     */
    public SharedPart<T> share(UnaryOperator<T> copier) {
        if (exposed) {
            return new SharedPart<>(copier.apply(value));
        }
        HOLDERS.incrementAndGet(this);
        return this;
    }

    /**
     * Holder the caller owns alone: this one, or a new one with a copy of the part (copied before this one is
     * released, so a remaining holder can't change the part while it is copied)
     */
    public SharedPart<T> owned(UnaryOperator<T> copier) {
        if (holders == 1) {
            exposed = true;
            return this;
        }
        SharedPart<T> own = new SharedPart<>(copier.apply(value));
        own.exposed = true;
        HOLDERS.decrementAndGet(this);
        return own;
    }

    /**
     * The part, for reads that don't change it or keep references into it
     */
    public T get() {
        return value;
    }
}
