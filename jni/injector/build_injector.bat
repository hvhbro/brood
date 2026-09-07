@echo off
setlocal EnableExtensions

set "VSWHERE=C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe"
for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSINSTALL=%%i"

if not defined VSINSTALL (
  echo [build] FAILED: Visual Studio with VC x64 tools was not found.
  exit /b 1
)

call "%VSINSTALL%\VC\Auxiliary\Build\vcvars64.bat" >nul

set "ROOT=%~dp0"
set "OUT=%ROOT%..\build\injector"
if not exist "%OUT%" mkdir "%OUT%"

cl /nologo /O2 /MT /Zi /EHsc /std:c++17 /utf-8 ^
   "%ROOT%src\injector.cpp" ^
   /Fo"%OUT%\\" /Fe"%OUT%\inject.exe" /Fd"%OUT%\inject.pdb" ^
   /link kernel32.lib user32.lib psapi.lib

if errorlevel 1 (
  echo [build] FAILED.
  exit /b 1
)

echo [build] OK -^> %OUT%\inject.exe
endlocal
