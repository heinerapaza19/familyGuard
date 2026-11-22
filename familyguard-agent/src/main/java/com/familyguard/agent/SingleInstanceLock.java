package com.familyguard.agent;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

final class SingleInstanceLock implements AutoCloseable {
    private final FileChannel channel; private final FileLock lock;
    private SingleInstanceLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
    static SingleInstanceLock acquire() throws IOException {
        return acquire(AgentPaths.userData("agent.lock"));
    }
    static SingleInstanceLock acquire(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try { lock = channel.tryLock(); }
        catch (OverlappingFileLockException exception) {
            channel.close();
            throw new IOException("FamilyGuard Agente ya está ejecutándose", exception);
        }
        if (lock == null) { channel.close(); throw new IOException("FamilyGuard Agente ya está ejecutándose"); }
        return new SingleInstanceLock(channel, lock);
    }
    @Override public void close() throws IOException { lock.release(); channel.close(); }
}
