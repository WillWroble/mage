package spellbench.kit.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Removes only the launcher's explicitly assigned temporary directory after runner exit. */
public final class OwnedWork {
    private OwnedWork() { }

    /** Preflight cleanup for agent.sh before any front or runner has started. */
    public static void main(String[] args) throws IOException {
        remove(new java.io.File(args[0]).toPath(), new java.io.File(args[1]).toPath());
    }

    static boolean linked(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return true;
        try {
            Object bits = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
            return bits instanceof Integer && (((Integer) bits) & 0x400) != 0;
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    static boolean remove(Path assigned, Path parent) throws IOException {
        Path root = assigned.toAbsolutePath().normalize();
        Path expected = parent.toAbsolutePath().normalize();
        if (!root.getParent().equals(expected) || !root.getFileName().toString().startsWith("kit-agent-")
                || linked(root) || linked(expected) || !root.toRealPath().getParent().equals(expected.toRealPath())) {
            throw new IOException("work cleanup is outside the launcher's assigned temporary directory");
        }
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(900);
        while (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try {
                removeTree(root);
            } catch (java.nio.file.FileSystemException locked) {
                // Windows can report the killed process gone before its file
                // handles finish releasing. Retry only this owned tree, inside
                // the remainder of the host's two-second exit grace.
                if (System.nanoTime() >= until) throw locked;
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("owned work cleanup interrupted", e);
                }
            }
        }
        return true;
    }

    private static void removeTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (attributes.isOther() || linked(directory)) {
                    throw new IOException("work cleanup refuses a linked directory");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                // Deleting a file link itself does not follow its target.
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
