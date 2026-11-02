package advanced_teleport.config;

import advanced_teleport.graph.GraphSettings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Server-owned settings. A copy is sent to clients for the same geometric calculations. */
public final class AtpConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public String mode = "default";
    public double roadConnectionDistance = 2;
    public double portalConnectionDistance = 3;
    public double roadAccessDistance = 2;
    public double stationAccessDistance = 3;
    public int railStraightGap = 1;
    public int railCornerMinGap = 2;
    public int railCornerMaxGap = 3;
    public int iceMinWidth = 3;
    public int iceMaxWidth = 5;
    public int maxPendingChangesPerTick = 4;
    public int clientResponseTimeoutTicks = 100;
    public int maxGraphEntries = 100_000;

    public void validate() {
        if (mode == null) throw new IllegalArgumentException("mode must be default, strict or off");
        mode = mode.strip().toLowerCase(Locale.ROOT);
        if (mode.equals("defaut")) mode = "default";
        if (!mode.equals("default") && !mode.equals("strict") && !mode.equals("off")) {
            throw new IllegalArgumentException("Unknown teleport mode: " + mode);
        }
        distance("roadConnectionDistance", roadConnectionDistance);
        distance("portalConnectionDistance", portalConnectionDistance);
        distance("roadAccessDistance", roadAccessDistance);
        distance("stationAccessDistance", stationAccessDistance);
        range("railStraightGap", railStraightGap, 0, 8);
        range("railCornerMinGap", railCornerMinGap, 0, 8);
        range("railCornerMaxGap", railCornerMaxGap, railCornerMinGap, 8);
        range("iceMinWidth", iceMinWidth, 1, 9);
        range("iceMaxWidth", iceMaxWidth, iceMinWidth, 9);
        range("maxPendingChangesPerTick", maxPendingChangesPerTick, 1, 256);
        range("clientResponseTimeoutTicks", clientResponseTimeoutTicks, 20, 1200);
        range("maxGraphEntries", maxGraphEntries, 100, 1_000_000);
    }

    public GraphSettings graphSettings() {
        validate();
        return new GraphSettings(roadConnectionDistance, portalConnectionDistance,
                roadAccessDistance, stationAccessDistance);
    }

    public static AtpConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            AtpConfig defaults = new AtpConfig();
            defaults.save(file);
            return defaults;
        }
        if (Files.size(file) > 65_536) throw new IOException("ATP config exceeds 64 KiB: " + file);
        try {
            AtpConfig config = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AtpConfig.class);
            if (config == null) throw new IllegalArgumentException("Configuration must be a JSON object");
            config.validate();
            return config;
        } catch (JsonParseException | IllegalArgumentException exception) {
            throw new IOException("Invalid ATP configuration at " + file + ": " + exception.getMessage(), exception);
        }
    }

    public void save(Path file) throws IOException {
        validate();
        Path absolute = file.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".atp-config-", ".json.tmp");
        try {
            Files.writeString(temporary, JSON.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void distance(String name, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 128) {
            throw new IllegalArgumentException(name + " must be finite and between 0 and 128");
        }
    }

    private static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
