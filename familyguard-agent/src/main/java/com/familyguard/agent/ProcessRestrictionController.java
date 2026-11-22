package com.familyguard.agent;

import java.time.Duration;import java.util.*;

interface ProcessRestrictionController { Result terminate(long pid,String expectedPath,String expectedName);
    record Result(boolean successful,String detail){} }

final class WindowsProcessRestrictionController implements ProcessRestrictionController {
    private final CriticalProcessGuard guard=new CriticalProcessGuard();
    public Result terminate(long pid,String expectedPath,String expectedName){
        Optional<ProcessHandle> found=ProcessHandle.of(pid);if(found.isEmpty())return new Result(true,"El proceso ya no estaba en ejecucion");
        ProcessHandle process=found.get();String path=process.info().command().orElse(null);String user=process.info().user().orElse("");
        String current=System.getProperty("user.name","");
        if(!user.equalsIgnoreCase(current)&&!user.toLowerCase(Locale.ROOT).endsWith("\\"+current.toLowerCase(Locale.ROOT)))
            return new Result(false,"Proceso de otro usuario protegido");
        if(!ExecutablePaths.same(path,expectedPath))return new Result(false,"La ruta del proceso no coincide con la regla");
        if(!guard.canTerminate(expectedName,path))return new Result(false,"Proceso critico o no verificable protegido");
        boolean requested=process.destroy();
        try{process.onExit().get(Duration.ofSeconds(2).toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);}catch(Exception ignored){
            if(process.isAlive()){
                process.destroyForcibly();
                try{process.onExit().get(Duration.ofSeconds(3).toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);}catch(Exception ignoredAgain){}
            }
        }
        if(process.isAlive()&&System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")){
            try{
                new ProcessBuilder("taskkill.exe","/PID",Long.toString(pid),"/T","/F").redirectErrorStream(true).start().waitFor(5,java.util.concurrent.TimeUnit.SECONDS);
                process.onExit().get(Duration.ofSeconds(2).toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);
            }catch(Exception ignored){}
        }
        return new Result(!process.isAlive(),process.isAlive()?"Windows no permitio cerrar el proceso":"Proceso cerrado por regla parental");
    }
}
