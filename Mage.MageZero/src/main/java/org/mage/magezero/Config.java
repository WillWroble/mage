package org.mage.magezero;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

public class Config {
    public static Config INSTANCE;

    public final String goesFirst;
    public final PlayerConfig playerA;
    public final PlayerConfig playerB;
    public final TrainingConfig training;
    public final ServerConfig server;
    public final LoggingConfig logging;

    public static void load(String path) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> raw = yaml.load(new FileInputStream(path));
            INSTANCE = new Config(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: " + path, e);
        }
    }

    public static void loadDefault() {
        try {
            Yaml yaml = new Yaml();
            File configFile = new File("config/config.yml");
            Map<String, Object> raw = yaml.load(Files.newInputStream(configFile.toPath()));
            INSTANCE = new Config(raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load default config", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Config(Map<String, Object> raw) {
        this.goesFirst = (String) raw.getOrDefault("goes_first", "random");
        this.playerA = new PlayerConfig((Map<String, Object>) raw.get("player_a"));
        this.playerB = new PlayerConfig((Map<String, Object>) raw.get("player_b"));
        this.training = new TrainingConfig((Map<String, Object>) raw.get("training"));
        this.server = new ServerConfig((Map<String, Object>) raw.get("server"));
        this.logging = new LoggingConfig((Map<String, Object>) raw.get("logging"));
    }

    public static class PlayerConfig {
        public String deckPath;
        public String type;
        public String outputDir;
        public final PriorsConfig priors;
        public final NoiseConfig noise;
        public final MctsConfig mcts;
        public final MulligansConfig mulligans;

        @SuppressWarnings("unchecked")
        public PlayerConfig(Map<String, Object> raw) {
            this.deckPath = (String) raw.get("deckPath");
            this.type = (String) raw.getOrDefault("type", "mcts");
            this.outputDir = (String) raw.getOrDefault("output_dir", "data/");
            this.priors = new PriorsConfig((Map<String, Object>) raw.getOrDefault("priors", Collections.emptyMap()));
            this.noise = new NoiseConfig((Map<String, Object>) raw.getOrDefault("noise", Collections.emptyMap()));
            this.mcts = new MctsConfig((Map<String, Object>) raw.getOrDefault("mcts", Collections.emptyMap()));
            this.mulligans = new MulligansConfig((Map<String, Object>) raw.getOrDefault("mulligans", Collections.emptyMap()));
        }
    }

    public static class PriorsConfig {
        public final boolean priority;
        public final boolean target;
        public final boolean binary;
        public final boolean opponent;

        public PriorsConfig(Map<String, Object> raw) {
            this.priority = (boolean) raw.getOrDefault("priority", false);
            this.target = (boolean) raw.getOrDefault("target", false);
            this.binary = (boolean) raw.getOrDefault("binary", false);
            this.opponent = (boolean) raw.getOrDefault("opponent", false);
        }
    }

    public static class NoiseConfig {
        public final boolean enabled;

        public NoiseConfig(Map<String, Object> raw) {
            this.enabled = (boolean) raw.getOrDefault("enabled", false);
        }
    }

    public static class MctsConfig {
        public final int searchBudget;
        public final int timeoutMs;
        public final double cPuct;
        public final double tdDiscount;

        public MctsConfig(Map<String, Object> raw) {
            this.searchBudget = ((Number) raw.getOrDefault("search_budget", 300)).intValue();
            this.timeoutMs = ((Number) raw.getOrDefault("timeout_ms", 4000)).intValue();
            this.cPuct = ((Number) raw.getOrDefault("c_puct", 1.0)).doubleValue();
            this.tdDiscount = ((Number) raw.getOrDefault("td_discount", 0.95)).doubleValue();
        }
    }

    public static class MulligansConfig {
        public final boolean enabled;

        public MulligansConfig(Map<String, Object> raw) {
            this.enabled = (boolean) raw.getOrDefault("enabled", true);
        }
    }

    public static class TrainingConfig {
        public int games;
        public final int gamesPerFile;
        public int maxTurns;
        public int threads;
        public final boolean trainOpponentHead;

        public TrainingConfig(Map<String, Object> raw) {
            this.games = ((Number) raw.getOrDefault("games", 1000)).intValue();
            this.gamesPerFile = ((Number) raw.getOrDefault("games_per_file", 200)).intValue();
            this.maxTurns = ((Number) raw.getOrDefault("max_turns", 50)).intValue();
            this.threads = ((Number) raw.getOrDefault("threads", 2)).intValue();
            this.trainOpponentHead = (boolean) raw.getOrDefault("train_opponent_head", false);
        }
    }

    public static class ServerConfig {
        public final String host;
        public final int port;
        public final int opponentPort;

        public ServerConfig(Map<String, Object> raw) {
            this.host = (String) raw.getOrDefault("host", "localhost");
            this.port = ((Number) raw.getOrDefault("port", 8080)).intValue();
            this.opponentPort = ((Number) raw.getOrDefault("opponent_port", 8081)).intValue();
        }
    }

    public static class LoggingConfig {
        public final boolean logFeatureHash;
        public final boolean writeFinalWR;
        public final boolean showWr;

        public LoggingConfig(Map<String, Object> raw) {
            this.logFeatureHash = (boolean) raw.getOrDefault("log_feature_hash", false);
            this.writeFinalWR = (boolean) raw.getOrDefault("save_final_wr", true);
            this.showWr = (boolean) raw.getOrDefault("show_wr", true);
        }
    }
}