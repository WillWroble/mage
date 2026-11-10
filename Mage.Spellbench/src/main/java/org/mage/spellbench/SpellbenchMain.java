package org.mage.spellbench;

import mage.cards.repository.CardScanner;
import mage.cards.repository.RepositoryUtil;
import org.mage.magezero.Config;

/**
 * Runs lockstep games between MageZero and an Archon, as MageZeroMain runs data generation:
 * {@code java -Dspellbench.lockstep=<settings.json> org.mage.spellbench.SpellbenchMain <config.yml>}.
 */
public final class SpellbenchMain {

    private SpellbenchMain() {
    }

    public static void main(String[] args) {
        Config.load(args[0]);
        RepositoryUtil.bootstrapLocalDb();
        CardScanner.scan();
        new LockstepGenerator().generateData();
        System.exit(0);
    }
}
