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

rem ===== 1) Java-агент: компиляция + protected-кодирование → agent_payload.h + agent_payload.rs =====
set "PYTHON=python"
set "KOTLIN_CP=%ROOT%..\agent\libs\game_cp.jar;%ROOT%..\agent\libs\game_stubs.jar"
%PYTHON% "%ROOT%..\..\tools\pack_agent.py" --src "%ROOT%..\agent\src" --out "%ROOT%src" --build "%ROOT%..\agent\build" --cp "%KOTLIN_CP%"
if errorlevel 1 (
  echo [build] FAILED: pack_agent.
  popd
  echo.
  pause
  exit /b 1
)

rem ===== 2) Сборка Rust-DLL (Cargo.toml в этом каталоге) =====
cargo build --release --manifest-path "%ROOT%Cargo.toml"
if errorlevel 1 (
  echo [build] FAILED: cargo.
  popd
  echo.
  pause
  exit /b 1
)

rem ===== 3) Копия в каталог инжекта =====
copy /y "%ROOT%target\release\brood.dll" "%OUT%\brood.dll" >nul
if errorlevel 1 (
  echo [build] FAILED: copy dll.
  popd
  echo.
  pause
  exit /b 1
)

echo [build] OK --^> %OUT%\brood.dll
popd
echo.
pause
endlocal
