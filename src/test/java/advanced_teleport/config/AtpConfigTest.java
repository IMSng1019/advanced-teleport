package advanced_teleport.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtpConfigTest {
    @TempDir Path temporary;

    @Test void missingConfigurationIsCreatedWithRequestedDefaults() throws IOException {
        Path file = temporary.resolve("advanced_teleport/config.json");
        AtpConfig config = AtpConfig.load(file);
        assertEquals("default", config.mode);
        assertEquals(3, config.iceMinWidth);
        assertEquals(5, config.iceMaxWidth);
        assertEquals(3, config.portalConnectionDistance);
        assertTrue(Files.isRegularFile(file));
        assertEquals("default", AtpConfig.load(file).mode);
    }

    @Test void legacyDefautSpellingIsAccepted() throws IOException {
        Path file = temporary.resolve("config.json");
        Files.writeString(file, "{\"mode\":\"defaut\"}");
        assertEquals("default", AtpConfig.load(file).mode);
    }

    @Test void malformedConfigurationIsNotOverwritten() throws IOException {
        Path file = temporary.resolve("config.json");
        String invalid = "{\"mode\":\"unknown\",\"iceMinWidth\":9,\"iceMaxWidth\":3}";
        Files.writeString(file, invalid);
        assertThrows(IOException.class, () -> AtpConfig.load(file));
        assertEquals(invalid, Files.readString(file));
    }

    @Test void rejectsNonFiniteDistancesAndInvertedWidths() {
        AtpConfig config = new AtpConfig();
        config.roadConnectionDistance = Double.NaN;
        assertThrows(IllegalArgumentException.class, config::validate);
        config.roadConnectionDistance = 2;
        config.iceMinWidth = 8;
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test void strictModeAndTuningSurviveSave() throws IOException {
        AtpConfig config = new AtpConfig();
        config.mode = "strict";
        config.stationAccessDistance = 4.5;
        Path file = temporary.resolve("config.json");
        config.save(file);
        AtpConfig restored = AtpConfig.load(file);
        assertEquals("strict", restored.mode);
        assertEquals(4.5, restored.stationAccessDistance);
    }
}
