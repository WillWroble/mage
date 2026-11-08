package spellbench.kit.xmage;

import java.lang.reflect.Field;

/** Field access by reflection, for the event-free injection of design Section 3.1 (ported from mzbridge, MIT). */
final class Reflect {

    private Reflect() {
    }

    static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("no field " + owner.getSimpleName() + "." + name, e);
        }
    }

    static Object get(Class<?> owner, Object target, String name) {
        try {
            return field(owner, name).get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static void set(Class<?> owner, Object target, String name, Object value) {
        try {
            field(owner, name).set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
