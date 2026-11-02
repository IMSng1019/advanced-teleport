package advanced_teleport.storage;

import advanced_teleport.graph.GraphData.Snapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/** Loads once at startup; all later SQLite writes are coalesced on a single background thread. */
public final class AsyncGraphStore implements AutoCloseable {
    private final GraphStore store;
    private final Snapshot initial;
    private final ExecutorService worker;
    private record Write(Snapshot snapshot,long sequence){}
    private final AtomicReference<Write> latest=new AtomicReference<>();
    private final AtomicLong sequence=new AtomicLong();
    private final AtomicBoolean draining=new AtomicBoolean();
    private volatile long persistedRevision;
    private volatile Throwable failure;
    private volatile boolean closed;

    public AsyncGraphStore(Path database)throws IOException,SQLException {
        store=new GraphStore(database);
        try{initial=store.load();}catch(SQLException|RuntimeException error){try{store.close();}catch(SQLException close){error.addSuppressed(close);}throw error;}
        persistedRevision=initial.revision();
        worker=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"ATP SQLite writer");thread.setDaemon(true);return thread;});
    }
    public Snapshot initial(){return initial;}
    public long persistedRevision(){return persistedRevision;}
    public boolean healthy(){return failure==null;}
    public Throwable failure(){return failure;}
    public synchronized void submit(Snapshot snapshot) {
        if(closed)throw new IllegalStateException("Graph store is closed");
        Objects.requireNonNull(snapshot);
        if(snapshot.revision()<persistedRevision)throw new IllegalArgumentException("Cannot persist an older graph revision");
        var write=new Write(snapshot,sequence.incrementAndGet());
        latest.accumulateAndGet(write,(before,after)->before!=null&&before.snapshot().revision()>after.snapshot().revision()?before:after);
        start();
    }
    private void start() {
        if(!closed&&draining.compareAndSet(false,true))worker.execute(this::drain);
    }
    private void drain() {
        long failedSequence=-1;
        try {
            Write write;
            while((write=latest.getAndSet(null))!=null) {
                try{store.save(write.snapshot());persistedRevision=write.snapshot().revision();failure=null;}
                catch(Exception error){failure=error;failedSequence=write.sequence();latest.compareAndSet(null,write);break;}
            }
        } finally {
            draining.set(false);
            Write waiting=latest.get();
            if(waiting!=null&&(failure==null||waiting.sequence()!=failedSequence))start();
        }
    }
    /** Normal shutdown waits for the newest submitted graph; failures are surfaced to the caller. */
    @Override public synchronized void close()throws Exception {
        if(closed)return;
        closed=true;
        Future<?> finish=worker.submit(()->{
            try {
                Write write=latest.getAndSet(null);
                if(write!=null){store.save(write.snapshot());persistedRevision=write.snapshot().revision();failure=null;}
            } catch(SQLException error){failure=error;throw new CompletionException(error);}
            finally{try{store.close();}catch(SQLException error){throw new CompletionException(error);}}
        });
        worker.shutdown();
        try{finish.get();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
        catch(ExecutionException error){throw new IOException("Cannot flush ATP database",error.getCause());}
    }
}
