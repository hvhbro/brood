@echo off
REM Сборка ASM-автомаппера (нужен только JDK, без Maven/Gradle).
cd /d %~dp0
if not exist bin mkdir bin
javac -encoding UTF-8 -nowarn -cp "lib/asm-9.7.jar;lib/asm-tree-9.7.jar" -d bin src/asmmapper/*.java
if errorlevel 1 (echo BUILD FAILED & exit /b 1)
echo BUILD OK
