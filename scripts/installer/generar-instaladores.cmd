@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0..\.."
title FamilyGuard - generando instaladores
echo FamilyGuard: generando FG-Admin.exe y FG-Agente.exe
echo Primero se ejecutan TODAS las pruebas y luego se empaqueta. Tarda varios minutos.
echo NO cierres esta ventana ni presiones Ctrl+C hasta que termine.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-installers.ps1" %*
if errorlevel 1 (
  echo.
  echo No se generaron los instaladores. Revisa el mensaje en rojo y build\windows-package\resultado.txt
) else (
  echo.
  echo Listo. Instaladores en %CD%\dist
)
pause
