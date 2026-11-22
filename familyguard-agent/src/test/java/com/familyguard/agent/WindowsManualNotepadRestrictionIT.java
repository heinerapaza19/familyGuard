package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Prueba manual dirigida: inicia solamente un Bloc de notas creado por la prueba. */
@EnabledOnOs(OS.WINDOWS)
class WindowsManualNotepadRestrictionIT {
    @Test void cierraNotepadYCompruebaElPid() throws Exception {
        var before=ProcessHandle.allProcesses().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        Process launcher=new ProcessBuilder("notepad.exe").start();
        ProcessHandle process=null;
        try {
            Thread.sleep(2000);
            process=ProcessHandle.allProcesses().filter(p->!before.contains(p.pid()))
                    .filter(p->p.info().command().map(c->c.toLowerCase().endsWith("notepad.exe")).orElse(false))
                    .findFirst().orElseThrow(()->new AssertionError("Windows no creó un proceso interactivo de Bloc de notas"));
            String path=process.info().command().orElseThrow();
            var result=new WindowsProcessRestrictionController().terminate(process.pid(),path,"notepad.exe");
            assertTrue(result.successful(),result.detail());
            for(int i=0;i<20&&process.isAlive();i++)Thread.sleep(100);
            assertFalse(process.isAlive(),"El PID de Bloc de notas siguió activo");
        } finally { if(process!=null&&process.isAlive())process.destroyForcibly();if(launcher.isAlive())launcher.destroyForcibly(); }
    }
}
