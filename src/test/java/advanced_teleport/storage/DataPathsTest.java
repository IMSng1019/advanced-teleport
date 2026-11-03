package advanced_teleport.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

    @Test void flatDedicatedAndSingleplayerWorldsKeepTheirExistingDatabaseDirectories() {
        Path server = temporary.resolve("server");
        Path saves = temporary.resolve("client/saves");
        Path data = temporary.resolve("advanced_teleport");
        assertEquals("world", DataPaths.worldIdentity(server, server.resolve("world/.")));
        assertEquals(DataPaths.worldDirectory(data, "0.0.0.0:25565", "world"),
                DataPaths.worldDirectory(data, "0.0.0.0:25565",
                        DataPaths.worldIdentity(server, server.resolve("world/."))));
        assertEquals(DataPaths.worldDirectory(data, "singleplayer", "New World"),
                DataPaths.worldDirectory(data, "singleplayer",
                        DataPaths.worldIdentity(saves, saves.resolve("New World/."))));
    }

    @Test void nestedWorldsWithTheSameLeafNameUseSeparateDatabases() {
        Path server = temporary.resolve("server");
        Path data = server.resolve("advanced_teleport");
        String first = DataPaths.worldIdentity(server, server.resolve("season1/world"));
        String second = DataPaths.worldIdentity(server, server.resolve("season2/world"));
        assertEquals("season1/world", first);
        assertEquals("season2/world", second);
        assertNotEquals(DataPaths.worldDirectory(data, "0.0.0.0:25565", first),
                DataPaths.worldDirectory(data, "0.0.0.0:25565", second));
        // A shared legacy basename database must not silently become either nested world's data.
        assertNotEquals(DataPaths.worldDirectory(data, "0.0.0.0:25565", "world"),
                DataPaths.worldDirectory(data, "0.0.0.0:25565", first));
    }

    @Test void equivalentWorldPathsHaveTheSameNormalizedIdentity() {
        Path server = temporary.resolve("server");
        assertEquals("season1/world", DataPaths.worldIdentity(server.resolve("."),
                server.resolve("unused/../season1/./world/.")));
    }

    @Test void relocatingTheInstallationPreservesRelativeWorldIdentity() {
        Path original = temporary.resolve("original/server");
        Path relocated = temporary.resolve("relocated/server");
        assertEquals(DataPaths.worldIdentity(original, original.resolve("season1/world")),
                DataPaths.worldIdentity(relocated, relocated.resolve("season1/world")));
    }

    @Test void customUniverseDirectoriesRemainPartOfWorldIdentity() {
        Path server = temporary.resolve("server");
        String first = DataPaths.worldIdentity(server, server.resolve("../universe1/world"));
        String second = DataPaths.worldIdentity(server, server.resolve("../universe2/world"));
        assertEquals("../universe1/world", first);
        assertNotEquals(first, second);
    }

    @Test void worldAtTheSaveRootHasANonemptyIdentity() {
        Path server = temporary.resolve("server");
        assertEquals(".", DataPaths.worldIdentity(server, server.resolve(".")));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void worldsOnAnotherVolumeKeepTheirFullLocation() {
        String first = DataPaths.worldIdentity(Path.of("C:/server"), Path.of("D:/season1/world"));
        String second = DataPaths.worldIdentity(Path.of("C:/server"), Path.of("D:/season2/world"));
        assertEquals("D:/season1/world", first);
        assertNotEquals(first, second);
    }
}
