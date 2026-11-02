package advanced_teleport.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Stable locations independent of the process working directory and safe on Windows. */
public final class DataPaths {
    private DataPaths() { }

    public static Path dataRoot(Path jarOrDirectory, Path gameDir) {
        Objects.requireNonNull(gameDir, "gameDir");
        if (jarOrDirectory != null) {
            Path origin = jarOrDirectory.toAbsolutePath().normalize();
            Path mods = origin.getFileName() != null && origin.getFileName().toString().equalsIgnoreCase("mods")
                    ? origin : origin.getParent();
            if (mods != null && mods.getFileName() != null
                    && mods.getFileName().toString().equalsIgnoreCase("mods") && mods.getParent() != null) {
                return mods.getParent().resolve("advanced_teleport");
            }
        }
        return gameDir.toAbsolutePath().normalize().resolve("advanced_teleport");
    }

    public static Path worldDirectory(Path root, String endpoint, String worldName) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(worldName, "worldName");
        // The hash distinguishes IPv6 colons, slashes, case-insensitive names and truncated labels.
        String identity = endpoint.length() + ":" + endpoint + worldName.length() + ":" + worldName;
        String folder = safe(endpoint) + "+" + safe(worldName) + "-" + digest(identity);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(folder).normalize();
        if (!normalizedRoot.equals(result.getParent())) throw new IllegalArgumentException("Invalid world folder");
        return result;
    }

    private static String safe(String input) {
        StringBuilder result = new StringBuilder();
        input.codePoints().limit(48).forEach(codepoint -> {
            if (Character.isLetterOrDigit(codepoint) || codepoint == '-' || codepoint == '_') {
                result.appendCodePoint(codepoint);
            } else result.append('_');
        });
        return result.isEmpty() ? "unnamed" : result.toString();
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }
}
