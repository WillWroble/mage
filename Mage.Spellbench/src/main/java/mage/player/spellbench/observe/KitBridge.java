package mage.player.spellbench.observe;

import mage.abilities.Ability;
import mage.constants.PhaseStep;

import java.util.List;

/**
 * The XMage agent kit's shared library into the engine's observation code (design decision K2): the kit inverts the
 * engine's mapping, so it reads the engine's own vocabulary functions instead of copying them. This class and
 * {@code mage.player.spellbench.decide.KitBridge} are the only places where kit code reaches package-private engine
 * overlay code; everything else the kit uses from the overlay is public API ({@link ObservationBuilder},
 * {@link Observation}, {@link Look}). Lives in the kit's source tree, compiled against the engine's exact jars.
 */
public final class KitBridge {

    private KitBridge() {
    }

    /** Section 6.2 {@code phase_step} of an XMage step. */
    public static String phaseStep(PhaseStep step) {
        try {
            return Vocabulary.phaseStep(step);
        } catch (ObservationException e) {
            return null;
        }
    }

    /** Section 6.10 counter name of an XMage counter name (for example "+1/+1" is p1p1). */
    public static String counter(String xmageName) {
        try {
            return Vocabulary.counter(xmageName);
        } catch (ObservationException e) {
            return null;
        }
    }

    /** Section 6.10 keywords of an object's abilities. */
    public static List<Object> keywords(Iterable<Ability> abilities) {
        try {
            return Vocabulary.keywords(abilities);
        } catch (ObservationException e) {
            return null;
        }
    }

    /**
     * Section 6.4 characteristics an observation reports for a non-face-down object: its supertypes, types, subtypes
     * and colors (power, toughness and keywords are read separately). Null when the engine vocabulary cannot name one.
     */
    public static java.util.Map<String, Object> typesAndColors(mage.MageObject o, mage.game.Game game) {
        try {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("supertypes", Vocabulary.supertypes(o.getSuperType(game)));
            m.put("types", Vocabulary.cardTypes(o.getCardType(game)));
            m.put("subtypes", Vocabulary.subtypes(o.getSubtype(game)));
            m.put("colors", Vocabulary.colors(o.getColor(game)));
            return m;
        } catch (ObservationException e) {
            return null;
        }
    }

    /** Section 4.4 name normalization (NFC; an empty name is null). */
    public static String nfc(String name) {
        return ObservationBuilder.nfc(name);
    }

    /** Code-point order of Section 6.7. */
    public static int compareCodePoints(String a, String b) {
        return Vocabulary.CODE_POINT.compare(a, b);
    }
}
