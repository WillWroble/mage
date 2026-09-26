package org.mage.magezero;

import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.cards.repository.RepositoryUtil;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class MageZeroMain {

    public static void main(String[] args) throws IOException {
        // Load config
        if (args.length > 0) {
            Config.load(args[0]);
        } else {
            Config.loadDefault();
        }
        // Initialize card database
        //RepositoryUtil.bootstrapLocalDb();
        //CardScanner.scan();
        Path lockPath = Paths.get("db", "magezero-bootstrap.lock");
        Files.createDirectories(lockPath.getParent());
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            RepositoryUtil.bootstrapLocalDb();
            CardScanner.scan();
        }


        // Run training
        ParallelDataGenerator generator = new ParallelDataGenerator();
        generator.generateData();

        //CardRepository.instance.closeDB(true);
        System.exit(0);
    }
}