package com.familyguard.agent;

import java.nio.file.*;import java.util.*;

final class CriticalProcessGuard {
    private static final Set<String>NAMES=Set.of("explorer.exe","winlogon.exe","logonui.exe","lsass.exe","csrss.exe","services.exe","smss.exe","svchost.exe","dwm.exe","taskmgr.exe","applicationframehost.exe","wwahost.exe","system","registry","familyguard-agent.exe","java.exe");
    boolean canTerminate(String name,String path){
        String n=name==null?"":name.toLowerCase(Locale.ROOT);if(NAMES.contains(n))return false;
        if(path==null||path.isBlank())return false;
        try{Path target=Path.of(path).toAbsolutePath().normalize();String windir=System.getenv().getOrDefault("WINDIR","C:\\Windows");
            if(target.startsWith(Path.of(windir).toAbsolutePath().normalize()))
                return Set.of("notepad.exe","mspaint.exe","calc.exe").contains(n);
            return true;}catch(Exception e){return false;}
    }
}
