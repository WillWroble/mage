package mage.player.spellbench.observe;

import mage.ObjectColor;
import mage.abilities.Ability;
import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.SuperType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The normalization tables of Section 6.10: XMage's supertypes, card types, subtypes, colors, counters, keyword
 * abilities and steps, as the protocol's lowercase snake_case vocabularies. A value with no protocol spelling fails
 * closed ({@link ObservationException}): the engine never guesses at a vocabulary value (Section 16).
 */
final class Vocabulary {

    private static final Pattern SNAKE = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern BOOST = Pattern.compile("([+-])(\\d+)/([+-])(\\d+)");
    private static final String KEYWORD_PACKAGE = "mage.abilities.keyword";

    /** Section 6.10 supertypes; XMage's ELITE (an Un-set supertype) has none. */
    private static final Set<String> SUPERTYPES = new HashSet<>(Arrays.asList(
            "basic", "legendary", "ongoing", "snow", "world"));

    /** Section 6.10 card types; XMage spells every one the same way. */
    private static final Set<String> CARD_TYPES = new HashSet<>(Arrays.asList(
            "artifact", "battle", "conspiracy", "creature", "dungeon", "enchantment", "instant", "kindred", "land",
            "phenomenon", "plane", "planeswalker", "scheme", "sorcery", "vanguard"));

    /**
     * XMage keyword classes whose CR 702 keyword is not their class name: variants that share one keyword
     * (landwalk, cycling, hexproof, protection, kicker, trample, banding, partner, mayhem).
     */
    private static final Map<String, String> KEYWORD_RENAMES = new HashMap<>();

    /** Classes in XMage's keyword package that are not CR 702 keyword abilities (ability words, helpers). */
    private static final Set<String> NOT_KEYWORDS = new HashSet<>(Arrays.asList(
            "BattalionAbility", "HeroicAbility", "InspiredAbility", "PackTacticsAbility",
            "CanAttackOnlyAloneAbility", "CanBlockSpaceflightAbility", "CantAttackOrBlockAloneAbility",
            "CantBeBlockedSourceAbility", "CastFromGraveyardAbility", "ClassLevelAbility", "ClassReminderAbility",
            "CompanionCondition", "LevelerCardBuilder", "PrepareReminderAbility", "StationLevelAbility",
            "HexproofBaseAbility", "BeholdAbility"));

    static {
        for (String c : new String[]{"Forestwalk", "Islandwalk", "Mountainwalk", "Plainswalk", "Swampwalk"}) {
            KEYWORD_RENAMES.put(c + "Ability", "landwalk");
        }
        for (String c : new String[]{"ArtifactLandcycling", "BasicLandcycling", "Forestcycling", "Islandcycling",
                "Mountaincycling", "Plainscycling", "Swampcycling"}) {
            KEYWORD_RENAMES.put(c + "Ability", "cycling");
        }
        for (String c : new String[]{"HexproofFromBlackAbility", "HexproofFromBlueAbility",
                "HexproofFromEachColorAbility", "HexproofFromGreenAbility", "HexproofFromInstantsAbility",
                "HexproofFromMonocoloredAbility", "HexproofFromMulticoloredAbility",
                "HexproofFromPlaneswalkersAbility", "HexproofFromRedAbility", "HexproofFromWhiteAbility",
                "HexproofFromArtifactsCreaturesAndEnchantments"}) {
            KEYWORD_RENAMES.put(c, "hexproof");
        }
        KEYWORD_RENAMES.put("ProtectionFromEachOpponentAbility", "protection");
        KEYWORD_RENAMES.put("ProtectionFromEverythingAbility", "protection");
        KEYWORD_RENAMES.put("KickerWithAnyNumberModesAbility", "kicker");
        KEYWORD_RENAMES.put("MultikickerAbility", "kicker");
        KEYWORD_RENAMES.put("TrampleOverPlaneswalkersAbility", "trample");
        KEYWORD_RENAMES.put("BandsWithOtherAbility", "banding");
        KEYWORD_RENAMES.put("PartnerWithAbility", "partner");
        KEYWORD_RENAMES.put("MayhemLandAbility", "mayhem");
        KEYWORD_RENAMES.put("AffinityForArtifactsAbility", "affinity");
        KEYWORD_RENAMES.put("JohanVigilanceAbility", "vigilance");
    }

    /** Code point order, as Python compares strings (Section 6.7 sorts known entries this way). */
    static final Comparator<String> CODE_POINT = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    private Vocabulary() {
    }

    /** Section 6.2 {@code phase_step} for an XMage step (both combat damage steps are {@code combat_damage}). */
    static String phaseStep(PhaseStep step) throws ObservationException {
        switch (step) {
            case UNTAP:
                return "untap";
            case UPKEEP:
                return "upkeep";
            case DRAW:
                return "draw";
            case PRECOMBAT_MAIN:
                return "precombat_main";
            case BEGIN_COMBAT:
                return "beginning_of_combat";
            case DECLARE_ATTACKERS:
                return "declare_attackers";
            case DECLARE_BLOCKERS:
                return "declare_blockers";
            case FIRST_COMBAT_DAMAGE:
            case COMBAT_DAMAGE:
                return "combat_damage";
            case END_COMBAT:
                return "end_of_combat";
            case POSTCOMBAT_MAIN:
                return "postcombat_main";
            case END_TURN:
                return "end_step";
            case CLEANUP:
                return "cleanup";
            default:
                throw new ObservationException("unmapped_step", step.name());
        }
    }

    static List<Object> supertypes(Collection<SuperType> types) throws ObservationException {
        List<Object> out = new ArrayList<>();
        for (SuperType t : types) {
            String v = t.name().toLowerCase();
            if (!SUPERTYPES.contains(v)) {
                throw new ObservationException("unmapped_supertype", v);
            }
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    static List<Object> cardTypes(Collection<CardType> types) throws ObservationException {
        List<Object> out = new ArrayList<>();
        for (CardType t : types) {
            String v = t.name().toLowerCase();
            if (!CARD_TYPES.contains(v)) {
                throw new ObservationException("unmapped_card_type", v);
            }
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    /** Subtypes in XMage's (printed) order, normalized per Section 6.10. */
    static List<Object> subtypes(Collection<SubType> types) throws ObservationException {
        List<Object> out = new ArrayList<>();
        for (SubType t : types) {
            String v = normalizeName(t.getDescription(), "subtype");
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    /** Colors in the order white, blue, black, red, green (Section 6.4). */
    static List<Object> colors(ObjectColor c) {
        List<Object> out = new ArrayList<>();
        if (c.isWhite()) {
            out.add("white");
        }
        if (c.isBlue()) {
            out.add("blue");
        }
        if (c.isBlack()) {
            out.add("black");
        }
        if (c.isRed()) {
            out.add("red");
        }
        if (c.isGreen()) {
            out.add("green");
        }
        return out;
    }

    /**
     * A counter's canonical name (Section 6.10): power/toughness counters as {@code p} or {@code m} per sign then
     * the digits ({@code +1/+1} is {@code p1p1}, {@code -0/-1} is {@code m0m1}); any other counter by its
     * normalized name.
     */
    static String counter(String xmageName) throws ObservationException {
        Matcher m = BOOST.matcher(xmageName.trim());
        if (m.matches()) {
            return (m.group(1).equals("+") ? "p" : "m") + Integer.parseInt(m.group(2))
                    + (m.group(3).equals("+") ? "p" : "m") + Integer.parseInt(m.group(4));
        }
        return normalizeName(xmageName, "counter");
    }

    /**
     * The CR 702 keyword abilities among {@code abilities}, without parameters, sorted and distinct. An ability is a
     * keyword when its class, or the first superclass that is, lives in XMage's keyword package.
     */
    static List<Object> keywords(Iterable<Ability> abilities) throws ObservationException {
        TreeSet<String> out = new TreeSet<>(CODE_POINT);
        for (Ability a : abilities) {
            String kw = keyword(a.getClass());
            if (kw != null) {
                out.add(kw);
            }
        }
        return new ArrayList<Object>(out);
    }

    private static String keyword(Class<?> cls) throws ObservationException {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            Package p = c.getPackage();
            String pkg = p == null ? "" : p.getName();
            if (!pkg.equals(KEYWORD_PACKAGE) && !pkg.equals(KEYWORD_PACKAGE + ".special")) {
                continue;
            }
            String simple = c.getSimpleName();
            if (NOT_KEYWORDS.contains(simple)) {
                return null;
            }
            String renamed = KEYWORD_RENAMES.get(simple);
            if (renamed != null) {
                return renamed;
            }
            String base = simple.endsWith("Ability") ? simple.substring(0, simple.length() - 7) : simple;
            return normalizeName(base.replaceAll("([a-z0-9])([A-Z])", "$1 $2"), "keyword");
        }
        return null;
    }

    /**
     * Section 6.10 normalization: lowercase, apostrophes removed, spaces and hyphens become {@code _}. Diacritics are
     * dropped (the vocabulary is ASCII). A result outside {@code [a-z][a-z0-9_]*} fails closed.
     */
    static String normalizeName(String raw, String what) throws ObservationException {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.toLowerCase(java.util.Locale.ROOT).replace("'", "").replace("’", "")
                .replace(' ', '_').replace('-', '_');
        if (!SNAKE.matcher(s).matches()) {
            throw new ObservationException("unmapped_" + what, raw);
        }
        return s;
    }
}
