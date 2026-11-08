package mage.player.spellbench;

import mage.abilities.MageSingleton;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Process boot step shared by the engine server and the X1 harness: run with the boot router installed.
 */
public final class Warmup {

    private Warmup() {
    }

    /**
     * Initializes every class of the XMage framework jar, in name order, under the boot router. Static
     * initializers that mint ids (MageSingleton abilities, StackAbility's empty costs, ...) then draw from the
     * fixed boot stream once per process, never from a game's stream: otherwise the first game in a JVM would
     * draw ids that a rerun does not, and every later id would shift.
     */
    public static int[] framework() throws IOException {
        int[] core = classesOf(MageSingleton.class, "");
        // The AI package (X5): engine autopay's object choices (PayChoice) score permanents with XMage's
        // ArtificialScoringSystem, whose MagicAbility initializer mints ids. Initialized mid-game, it drew from that
        // game's stream only in the first such game of a process, so digests depended on process history.
        int[] ai = classesOf(mage.player.ai.ComputerPlayer.class, "mage/player/ai/");
        return new int[]{core[0] + ai[0], core[1] + ai[1]};
    }

    /** Initializes, in name order, every class under {@code prefix} in the jar or directory holding {@code anchor}. */
    private static int[] classesOf(Class<?> anchor, String prefix) throws IOException {
        URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
        File file;
        try {
            file = new File(location.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        List<String> names = new ArrayList<>();
        if (file.isDirectory()) {
            Path root = file.toPath();
            try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .map(p -> root.relativize(p).toString().replace(File.separatorChar, '/'))
                        .filter(n -> n.startsWith(prefix))
                        .forEach(names::add);
            }
        } else {
            try (JarFile jar = new JarFile(file)) {
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName();
                    if (name.endsWith(".class") && name.startsWith(prefix)) {
                        names.add(name);
                    }
                }
            }
        }
        Collections.sort(names);
        ClassLoader loader = Warmup.class.getClassLoader();
        int ok = 0;
        int failed = 0;
        for (String name : names) {
            String cls = name.substring(0, name.length() - 6).replace('/', '.');
            try {
                Class.forName(cls, true, loader);
                ok++;
            } catch (Throwable t) {
                failed++; // a class whose initializer needs a running game; reported, not fatal
            }
        }
        return new int[]{ok, failed};
    }

}
