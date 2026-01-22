package org.mage.magezero;

import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.cards.repository.RepositoryUtil;

public class MageZeroMain {

    public static void main(String[] args) {
        // Load config
        if (args.length > 0) {
            Config.load(args[0]);
        } else {
            Config.loadDefault();
        }
        // Initialize card database
        RepositoryUtil.bootstrapLocalDb();
        CardScanner.scan();

        // Run training
        ParallelDataGenerator generator = new ParallelDataGenerator();
        generator.generateData();

        CardRepository.instance.closeDB(true);
        System.exit(0);
    }
}