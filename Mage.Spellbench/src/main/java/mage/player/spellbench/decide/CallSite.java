package mage.player.spellbench.decide;

import java.util.ArrayList;
import java.util.List;

/**
 * Which XMage code asked a player callback. XMage's callbacks carry no purpose (a {@code chooseUse} is a kicker,
 * an "unless" payment or a "you may" alike), so the mapper reads the calling classes from the stack (Section 7.4:
 * "a more specific value is used whenever it applies", else {@code other}). The stack is code, not game state, so
 * the classification is deterministic and carries nothing hidden.
 */
final class CallSite {

    private final List<String> names = new ArrayList<>(); // fully qualified class names, innermost first

    private CallSite() {
    }

    static CallSite here() {
        CallSite c = new CallSite();
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String cls = e.getClassName();
            if (cls.startsWith("mage.player.spellbench.") || cls.startsWith("java.")) {
                continue;
            }
            c.names.add(cls);
            if (c.names.size() >= 48) {
                break;
            }
        }
        return c;
    }

    private static String simple(String cls) {
        int dot = cls.lastIndexOf('.');
        String s = cls.substring(dot + 1);
        int dollar = s.indexOf('$');
        return dollar < 0 ? s : s.substring(0, dollar);
    }

    /** The simple name of the innermost caller whose simple name contains any of {@code parts}, or null. */
    String find(String... parts) {
        for (String cls : names) {
            String s = simple(cls);
            for (String p : parts) {
                if (s.contains(p)) {
                    return s;
                }
            }
        }
        return null;
    }

    /** The innermost non-mana cost class paying now ({@code mage.abilities.costs..*Cost}), or null. */
    String cost() {
        for (String cls : names) {
            if (!cls.startsWith("mage.abilities.costs.") || cls.startsWith("mage.abilities.costs.mana.")) {
                continue;
            }
            String s = simple(cls);
            if (s.endsWith("Cost")) {
                return s;
            }
        }
        return null;
    }

    /** The cost_kind of a cost class (Section 7.4). */
    static String costKind(String costClass) {
        if (costClass.startsWith("Sacrifice")) {
            return "sacrifice";
        }
        if (costClass.startsWith("Discard")) {
            return "discard";
        }
        if (costClass.startsWith("Exile")) {
            return "exile";
        }
        if (costClass.startsWith("Untap")) {
            return "untap";
        }
        if (costClass.startsWith("Tap")) {
            return "tap";
        }
        if (costClass.startsWith("ReturnToHand")) {
            return "return_to_hand";
        }
        if (costClass.startsWith("Reveal")) {
            return "reveal";
        }
        if (costClass.startsWith("Remove")) {
            return "remove_counter";
        }
        return "other";
    }

    /** True when the innermost caller is the class with this simple name. */
    boolean innermost(String simpleName) {
        return !names.isEmpty() && simple(names.get(0)).equals(simpleName);
    }

    String innermost() {
        return names.isEmpty() ? "none" : simple(names.get(0));
    }
}
