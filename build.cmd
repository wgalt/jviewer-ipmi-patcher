@echo off
rem Patch a set of JViewer jars (Windows): build.cmd [-o OUT_DIR] JAR or DIR ...
setlocal
set "D=%~dp0"
set "D=%D:~0,-1%"
if not defined JAVA set "JAVA=java"
if "%JVIEWER_SKIP_CHECKS%"=="1" goto run
set "JV="
set "JM="
for /f "tokens=2" %%v in ('"%JAVA%" --version 2^>nul') do if not defined JV set "JV=%%v"
if defined JV for /f "delims=.-+" %%m in ("%JV%") do set "JM=%%m"
if not defined JM (
  echo warning: could not run "%JAVA%" --version; a JDK 24 or newer is needed, e.g. 'winget install EclipseAdoptium.Temurin.25.JDK' or from https://adoptium.net, or set JAVA=C:\path\to\java.exe 1>&2
) else if %JM% LSS 24 (
  echo warning: "%JAVA%" is Java %JV%; a JDK 24 or newer is needed, e.g. 'winget install EclipseAdoptium.Temurin.25.JDK' or from https://adoptium.net 1>&2
)
:run
"%JAVA%" -Djviewer.home="%D%" "%D%\tools\JViewerPatcher.java" build %*
exit /b %ERRORLEVEL%
