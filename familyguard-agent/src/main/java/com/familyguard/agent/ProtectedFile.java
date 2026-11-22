package com.familyguard.agent;

import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Archivo local cifrado con DPAPI (cuenta de Windows actual). Las pruebas usan un protector sin cifrado. */
final class ProtectedFile {
    interface Protector { byte[] protect(byte[] data); byte[] unprotect(byte[] data); }
    static final Protector DPAPI = new Protector() {
        public byte[] protect(byte[] data) { return Crypt32Util.cryptProtectData(data); }
        public byte[] unprotect(byte[] data) { return Crypt32Util.cryptUnprotectData(data); }
    };

    private final Path file;
    private final Protector protector;

    ProtectedFile(Path file, Protector protector) { this.file = file; this.protector = protector; }

    Path path() { return file; }
    boolean exists() { return Files.exists(file); }
    byte[] read() throws Exception { return protector.unprotect(Files.readAllBytes(file)); }

    void write(byte[] data) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, protector.protect(data), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo guardar " + file.getFileName(), e);
        }
    }
}
