package mage.util;

/**
 * 64-bit FNV-1a over the characters of everything added, in order: two sequences of additions hash the same
 * exactly when their concatenated strings are equal (up to 64-bit collisions). Used instead of building
 * state value strings that are only compared.
 */
public class ValueHash {

    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private long hash = OFFSET;

    public ValueHash add(String s) {
        for (int i = 0; i < s.length(); i++) {
            hash = (hash ^ s.charAt(i)) * PRIME;
        }
        return this;
    }

    public ValueHash add(Object o) {
        return add(String.valueOf(o));
    }

    public long get() {
        return hash;
    }
}
