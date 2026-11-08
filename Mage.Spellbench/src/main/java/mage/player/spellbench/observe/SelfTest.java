package mage.player.spellbench.observe;

import mage.abilities.Ability;
import mage.abilities.keyword.FirstStrikeAbility;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.ForestwalkAbility;
import mage.abilities.keyword.HexproofFromBlackAbility;
import mage.abilities.keyword.KickerAbility;
import mage.abilities.keyword.WardAbility;
import mage.constants.SubType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Fixed checks of the Section 6.10 normalization tables; an empty list means every check passed. */
public final class SelfTest {

    private SelfTest() {
    }

    public static List<String> run() {
        List<String> failures = new ArrayList<>();
        try {
            expect(failures, "counter +1/+1", "p1p1", Vocabulary.counter("+1/+1"));
            expect(failures, "counter -1/-1", "m1m1", Vocabulary.counter("-1/-1"));
            expect(failures, "counter -0/-1", "m0m1", Vocabulary.counter("-0/-1"));
            expect(failures, "counter +1/+0", "p1p0", Vocabulary.counter("+1/+0"));
            expect(failures, "counter loyalty", "loyalty", Vocabulary.counter("loyalty"));
            expect(failures, "subtype Urza's", "urzas", Vocabulary.normalizeName("Urza's", "subtype"));
            expect(failures, "subtype Time Lord", "time_lord", Vocabulary.normalizeName("Time Lord", "subtype"));
            expect(failures, "subtype Assembly-Worker", "assembly_worker",
                    Vocabulary.subtypes(Collections.singletonList(SubType.ASSEMBLY_WORKER)).get(0));
            List<Ability> abilities = Arrays.<Ability>asList(FlyingAbility.getInstance(),
                    FirstStrikeAbility.getInstance(), new ForestwalkAbility(), HexproofFromBlackAbility.getInstance(),
                    new KickerAbility("{1}"), new WardAbility(new mage.abilities.costs.mana.ManaCostsImpl<>("{2}")));
            expect(failures, "keywords", "[first_strike, flying, hexproof, kicker, landwalk, ward]",
                    String.valueOf(Vocabulary.keywords(abilities)));
        } catch (ObservationException | RuntimeException e) {
            failures.add("threw " + e);
        }
        return failures;
    }

    private static void expect(List<String> failures, String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures.add(what + ": expected " + expected + ", got " + actual);
        }
    }
}
