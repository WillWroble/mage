package mage.util;

import java.util.UUID;

/**
 * 64-bit hash of a sequence of pieces: two sequences of the same pieces hash the same. A string piece goes in as
 * its String.hashCode (vectorized in the JDK) and length, so sequences that differ in a string piece collide with
 * a chance of about 2^-32; ids and numbers go in whole. Used instead of building state value strings that are
 * only compared.
 */
public class ValueHash {

    private long hash = 0x9E3779B97F4A7C15L;

    public ValueHash add(String s) {
        return mix((long) s.hashCode() << 32 | s.length());
    }

    public ValueHash add(Object o) {
        return o instanceof UUID ? add((UUID) o) : add(String.valueOf(o));
    }

    public ValueHash add(UUID id) {
        return id == null ? add("null") : mix(id.getMostSignificantBits()).mix(id.getLeastSignificantBits());
    }

    public ValueHash add(long value) {
        return mix(value);
    }

    public ValueHash add(boolean value) {
        return mix(value ? 1 : 0);
    }

    private ValueHash mix(long value) {
        long z = hash + value;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        hash = z ^ (z >>> 31);
        return this;
    }

    public long get() {
        return hash;
    }
}
