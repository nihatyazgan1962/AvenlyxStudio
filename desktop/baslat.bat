@echo off
cd /d "%~dp0"
if exist "build\AvenlyxStudio.exe" (
    start "" "%~dp0build\AvenlyxStudio.exe" %*
    exit /b 0
)
if exist "build\Formatci.exe" (
    start "" "%~dp0build\Formatci.exe" %*
    exit /b 0
)
echo [HATA] build\AvenlyxStudio.exe bulunamadi!
pause
exit /b 1
