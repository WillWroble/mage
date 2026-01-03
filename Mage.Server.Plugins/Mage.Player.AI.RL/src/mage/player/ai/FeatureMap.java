package mage.player.ai;


import javafx.util.Pair;
import org.apache.log4j.Logger;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * used only for logging feature encodings for research. not needed for any AI functionality.
 */
public class FeatureMap implements Serializable {

    private Map<Integer, Set<Pair<Long, String>>> map = new HashMap<>();
    private static final Logger logger = Logger.getLogger(FeatureMap.class);

    public void addFeature(String name, long nameSpace, int idx) {
        if(map.containsKey(idx)) {
            map.get(idx).add(new Pair<>(nameSpace, name));
        } else {
            Set<Pair<Long, String>> empty = new HashSet<>();
            empty.add(new Pair<>(nameSpace, name));
            map.put(idx, empty);
        }
    }

    public synchronized void merge(FeatureMap fm) {
        for (int i : fm.map.keySet()) {
            if(map.containsKey(i)) {
                map.get(i).addAll(fm.map.get(i));
            } else {
                map.put(i, fm.map.get(i));
            }
        }
    }

    /**
     * Prints the full index table with one index per row, sorted from least to greatest.
     * Each row lists the set of namespace-hash/name pairs for that index.
     */
    public void printFeatureTable() {
        // Get all indices and sort them
        List<Integer> sortedIndices = new ArrayList<>(map.keySet());
        Collections.sort(sortedIndices);

        // Print each index and its associated features
        for (Integer idx : sortedIndices) {
            StringBuilder sb = new StringBuilder();
            sb.append(idx).append(": ");

            Set<Pair<Long, String>> features = map.get(idx);
            if (features != null && !features.isEmpty()) {
                boolean first = true;
                for (Pair<Long, String> pair : features) {
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append("[").append(pair.getKey()).append("/").append(pair.getValue()).append("]");
                    first = false;
                }
            }

            logger.info(sb.toString());
        }
    }
    //implement load/save from a file.
    public void saveToFile(String filePath) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(Paths.get(filePath)))) {
            out.writeObject(this);
        }
    }

    public static FeatureMap loadFromFile(String filePath) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(Paths.get(filePath)))) {
            return (FeatureMap) in.readObject();
        }
    }

}
