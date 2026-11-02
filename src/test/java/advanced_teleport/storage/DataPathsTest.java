package advanced_teleport.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DataPathsTest {
    @TempDir Path temporary;

    @Test void installedJarUsesModsParent() {
        Path root = temporary.resolve("server");
        assertEquals(root.resolve("advanced_teleport"),
                DataPaths.dataRoot(root.resolve("mods/atp.jar"), temporary.resolve("unrelated")));
    }

    @Test void developmentUsesGameDirectory() {
        assertEquals(temporary.resolve("run/advanced_teleport"),
                DataPaths.dataRoot(temporary.resolve("build/classes/java/main"), temporary.resolve("run")));
    }

    @Test void worldFoldersCannotEscapeRootAndKeepIdentityAfterSanitizing() {
        Path root = temporary.resolve("advanced_teleport");
        Path first = DataPaths.worldDirectory(root, "[::1]:25565", "../../城市:1");
        assertEquals(root, first.getParent());
        assertNotEquals(first, DataPaths.worldDirectory(root, "[::1]:25565", "..\\..\\城市:1"));
        assertNotEquals(first, DataPaths.worldDirectory(root, "[::1]:25566", "../../城市:1"));
        assertEquals(first, DataPaths.worldDirectory(root, "[::1]:25565", "../../城市:1"));
    }
}
