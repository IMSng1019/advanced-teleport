package advanced_teleport.storage;

import advanced_teleport.graph.GraphData.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.sql.DriverManager;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class AsyncGraphStoreTest {
    @TempDir Path temporary;
    @Test void closeFlushesNewestSubmittedGraphAndPreservesItOnReopen()throws Exception {
        Path database=temporary.resolve("async.sqlite");Snapshot newest=Snapshot.empty();
        try(AsyncGraphStore store=new AsyncGraphStore(database)) {
            assertEquals(Snapshot.empty(),store.initial());
            for(int i=1;i<=100;i++){
                Road road=new Road("r","minecraft:overworld",List.of(new Point("minecraft:overworld",0,64,0),
                        new Point("minecraft:overworld",i,64,0)),RoadKind.API,true);
                newest=new Snapshot(i,List.of(),List.of(road),List.of(),List.of());store.submit(newest);
            }
        }
        try(GraphStore store=new GraphStore(database)){assertEquals(newest,store.load());}
        try(AsyncGraphStore store=new AsyncGraphStore(database)){assertEquals(newest,store.initial());assertTrue(store.healthy());}
    }
    @Test void failedBackgroundWriteIsVisibleAndLaterSubmissionCanRecover()throws Exception {
        Path database=temporary.resolve("recovery.sqlite");
        Road road=new Road("r","minecraft:overworld",List.of(new Point("minecraft:overworld",0,64,0),new Point("minecraft:overworld",1,64,0)),RoadKind.API,true);
        Snapshot target=new Snapshot(1,List.of(),List.of(road),List.of(),List.of());
        try(AsyncGraphStore store=new AsyncGraphStore(database);
            var connection=DriverManager.getConnection("jdbc:sqlite:"+database);var sql=connection.createStatement()) {
            sql.execute("CREATE TRIGGER reject_road BEFORE INSERT ON roads BEGIN SELECT RAISE(ABORT,'test rejection'); END");
            store.submit(target);await(()->store.failure()!=null);
            assertFalse(store.healthy());assertEquals(0,store.persistedRevision());
            sql.execute("DROP TRIGGER reject_road");store.submit(target);
            await(()->store.persistedRevision()==1&&store.healthy());
        }
        try(GraphStore store=new GraphStore(database)){assertEquals(target,store.load());}
    }
    private static void await(BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);
        assertTrue(condition.getAsBoolean(),"Background persistence did not reach the expected state");
    }
}
