@echo off
rem Windows helper: builds (if needed) and starts the Mandi dashboard on http://localhost:8080
setlocal
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
  echo JDK 17+ is required. Install from https://adoptium.net then re-run.
  pause
  exit /b 1
)

if not exist out mkdir out
if not exist sources.txt dir /s /b src\*.java test\*.java > sources.txt
echo Compiling ...
javac --release 17 -cp lib\mysql-connector-j-8.3.0.jar -d out @sources.txt
if errorlevel 1 (
  echo Compile failed.
  pause
  exit /b 1
)
del sources.txt

echo Starting dashboard on http://localhost:8080 ...
start "" cmd /c "timeout /t 5 /nobreak >nul & start http://localhost:8080"
java -cp "out;lib\mysql-connector-j-8.3.0.jar" com.mandi.server.MandiServerMain
pause
