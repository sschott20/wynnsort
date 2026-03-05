@echo off
set "SRC=build\libs\wynnsort-1.0.0.jar"
set "DST=C:\Users\sebal\AppData\Roaming\PrismLauncher\instances\Wynncraft 101\minecraft\mods\wynnsort-1.0.0.jar"

if not exist "%SRC%" (
    echo No build found. Run: .\gradlew build
    exit /b 1
)

fc /b "%SRC%" "%DST%" >nul 2>&1
if %errorlevel%==0 (
    echo Already up to date.
) else (
    copy /Y "%SRC%" "%DST%" >nul
    echo Deployed. Restart Minecraft to apply.
)
