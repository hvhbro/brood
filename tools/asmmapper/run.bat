@echo off
REM Прогон на новом дампе: Main --dump <dir> --main <entry> --out <dir>
cd /d %~dp0
if not exist bin (echo no bin, run build.bat first & exit /b 1)
set DUMP=%1
if "%DUMP%"=="" set DUMP=..\..\newdump\minecraft
set OUTDIR=%2
if "%OUTDIR%"=="" set OUTDIR=out
java -cp "lib/asm-9.7.jar;lib/asm-tree-9.7.jar;bin" asmmapper.Main --dump "%DUMP%" --main ru.meproject.Main --out "%OUTDIR%"
