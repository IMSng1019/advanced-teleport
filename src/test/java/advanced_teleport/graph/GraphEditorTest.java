package advanced_teleport.graph;

import advanced_teleport.graph.GraphData.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GraphEditorTest {
    private final GraphSettings settings = new GraphSettings(2, 3, 2, 3);
    private Point point(double x, double z) { return new Point("minecraft:overworld", x, 64, z); }
    private Road road(String id, boolean protectedEntry) {
        return new Road(id, "minecraft:overworld", List.of(point(0, 0), point(10, 0)), RoadKind.API, protectedEntry);
    }

    @Test void automaticRescansCannotRemoveOrReplaceProtectedRoads() {
        Road protectedRoad = road("auto:existing", true);
        GraphEditor editor = new GraphEditor(new Snapshot(9, List.of(), List.of(protectedRoad), List.of(), List.of()), settings);
        assertFalse(editor.applyAutomatic(List.of(), Set.of("auto:existing")));
        assertFalse(editor.applyAutomatic(List.of(road("auto:existing", false)), Set.of("auto:existing")));
        assertEquals(protectedRoad, editor.snapshot().roads().get(0));
        assertEquals(9, editor.snapshot().revision());
    }

    @Test void protectedOrphanStationSurvivesAutomaticRoadRemoval() {
        Station protectedStation = new Station("station", "auto:road", point(0, 0), true);
        Station automaticStation = new Station("auto:station", "auto:road", point(10, 0), false);
        GraphEditor editor = new GraphEditor(new Snapshot(0, List.of(), List.of(road("auto:road", false)),
                List.of(protectedStation, automaticStation), List.of()), settings);
        assertTrue(editor.applyAutomatic(List.of(), Set.of("auto:road")));
        assertEquals(List.of(protectedStation), editor.snapshot().stations());
        assertTrue(editor.snapshot().roads().isEmpty());
        assertEquals(1, editor.snapshot().revision());
    }

    @Test void registrationProtectsEntriesAndRejectsOffRoadStation() {
        GraphEditor editor = new GraphEditor(Snapshot.empty(), settings);
        editor.registerRoad(road("api:road", false));
        assertTrue(editor.snapshot().roads().get(0).protectedEntry());
        assertThrows(IllegalArgumentException.class, () -> editor.registerStation(
                new Station("bad", "api:road", point(5, 3), false)));
        assertThrows(IllegalArgumentException.class, () -> editor.registerRoad(road("auto:reserved", true)));
        editor.registerStation(new Station("good", "api:road", point(5, 0), false));
        assertTrue(editor.snapshot().stations().get(0).protectedEntry());
        assertEquals(2, editor.snapshot().revision());
    }

    @Test void unchangedUpdatesDoNotIncrementRevision() {
        GraphEditor editor = new GraphEditor(Snapshot.empty(), settings);
        Road automatic = road("auto:road", false);
        assertTrue(editor.applyAutomatic(List.of(automatic), Set.of("auto:road")));
        assertFalse(editor.applyAutomatic(List.of(automatic), Set.of("auto:road")));
        assertFalse(editor.remove("road", "missing"));
        assertEquals(1, editor.snapshot().revision());
        editor.registerRoad(road("api:road", false));
        editor.registerRoad(road("api:road", false));
        assertEquals(2, editor.snapshot().revision());
        assertTrue(editor.remove("road", "api:road"));
        assertEquals(3, editor.snapshot().revision());
    }

    @Test void onlyAutomaticPortalEntriesCanBeAutomaticallyRemoved() {
        GraphEditor editor = new GraphEditor(Snapshot.empty(), settings);
        PortalLink observed = new PortalLink("auto:portal", point(0, 0), new Point("minecraft:the_nether", 0, 64, 0), true);
        assertTrue(editor.putAutoPortal(observed));
        assertFalse(editor.snapshot().portals().get(0).protectedEntry());
        assertFalse(editor.putAutoPortal(observed));
        editor.registerPortal(new PortalLink("api:portal", observed.first(), observed.second(), false));
        assertFalse(editor.removeAutoPortal("api:portal"));
        assertTrue(editor.removeAutoPortal("auto:portal"));
        assertEquals(3, editor.snapshot().revision());
    }

    @Test void scansDoNotModifyUnexaminedRoadsOrAcceptUnexpectedIds() {
        GraphEditor editor = new GraphEditor(Snapshot.empty(), settings);
        editor.applyAutomatic(List.of(road("auto:far", false)), Set.of("auto:far"));
        assertFalse(editor.applyAutomatic(List.of(), Set.of("auto:near")));
        assertThrows(IllegalArgumentException.class,
                () -> editor.applyAutomatic(List.of(road("auto:unexpected", false)), Set.of("auto:near")));
        assertEquals(1, editor.snapshot().roads().size());
        assertEquals(1, editor.snapshot().revision());
    }
}
