package mage.player.ai.encoder;


import javafx.util.Pair;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * used only for logging feature encodings for research. not needed for any AI functionality.
 */
public class FeatureMap implements Serializable {

    private Map<Integer, Set<String>> map = new HashMap<>();

    public int getFeatureCount() {
        int count = 0;
        for (Map.Entry<Integer, Set<String>> entry : map.entrySet()) {
            count += entry.getValue().size();
        }
        return count;
    }
    public int getIndexCount() {
        return map.size();
    }

    public void addFeature(String name, int idx) {
        if(map.containsKey(idx)) {
            map.get(idx).add(name);
        } else {
            Set<String> empty = new HashSet<>();
            empty.add(name);
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
    public void printFeatureTable(String filePath) throws IOException {
        // Get all indices and sort them
        List<Integer> sortedIndices = new ArrayList<>(map.keySet());
        Collections.sort(sortedIndices);

        // Create a BufferedWriter to write to the file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            // Write each index and its associated features
            for (Integer idx : sortedIndices) {
                StringBuilder sb = new StringBuilder();
                sb.append(idx).append(": ");

                Set<String> features = map.get(idx);
                if (features != null && !features.isEmpty()) {
                    boolean first = true;
                    for (String s : features) {
                        if (!first) {
                            sb.append(", ");
                        }
                        sb.append("[").append(s).append("]");
                        first = false;
                    }
                }

                writer.write(sb.toString());
                writer.newLine();
            }
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
