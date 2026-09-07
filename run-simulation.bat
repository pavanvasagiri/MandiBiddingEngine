@echo off
rem Windows helper: compiles, runs the engine tests, then the live trading simulation.
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

echo Running engine regression tests ...
java -cp "out;lib\mysql-connector-j-8.3.0.jar" com.mandi.engine.DoubleAuctionEngineTest
echo Running the live mandi simulation ...
java -cp "out;lib\mysql-connector-j-8.3.0.jar" com.mandi.main.Main
echo Done. See historical_mandi_bids.csv for the settlement log.
pause
