@echo off
setlocal EnableExtensions

set "VSWHERE=C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe"
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSINSTALL=%%i"

if not defined VSINSTALL (
  echo [build] FAILED: Visual Studio with VC x64 tools was not found.
  echo Install Visual Studio with "Desktop development with C++" workload.
  pause
  exit /b 1
)

call "%VSINSTALL%\VC\Auxiliary\Build\vcvars64.bat" >nul

set "ROOT=%~dp0"
pushd "%ROOT%"
set "OUT=%ROOT%..\build\dll"
if not exist "%OUT%" mkdir "%OUT%"

rem ===== 1) Java-агент: компиляция + protected-кодирование → agent_payload.h =====
set "PYTHON=python"
set "KOTLIN_CP=%ROOT%..\..\tools\game_cp.jar;%ROOT%..\..\tools\game_stubs.jar"
%PYTHON% "%ROOT%..\..\tools\pack_agent.py" --src "%ROOT%..\agent\src" --out "%ROOT%src" --build "%ROOT%..\agent\build" --cp "%KOTLIN_CP%"
if errorlevel 1 (
  echo [build] FAILED: pack_agent.
  popd
  echo.
  pause
  exit /b 1
)

rem ===== 2) Сборка DLL =====
set "RSP=%OUT%\cl_args.rsp"
(
  echo /nologo
  echo /O2
  echo /MT
  echo /Zi
  echo /EHsc
  echo /std:c++17
  echo /utf-8
  echo /LD
  echo "%ROOT%src\dllmain.cpp"
  echo "%ROOT%src\logger.cpp"
  echo "%ROOT%src\memory_utils.cpp"
  echo /I"%ROOT%src"
  echo /Fo"%OUT%\\"
  echo /Fe"%OUT%\jni_rva_check.dll"
  echo /Fd"%OUT%\jni_rva_check.pdb"
  echo /link
  echo kernel32.lib
  echo user32.lib
) > "%RSP%"

cl @"%RSP%"

if errorlevel 1 (
  echo [build] FAILED.
  popd
  echo.
  pause
  exit /b 1
)

echo [build] OK -^> %OUT%\jni_rva_check.dll
popd
echo.
pause
endlocal
