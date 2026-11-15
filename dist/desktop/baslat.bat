@echo off
cd /d "%~dp0"
if exist "AvenlyxStudio.exe" (
    start "" "%~dp0AvenlyxStudio.exe" %*
    exit /b 0
)
echo [HATA] Calistirilabilir dosya bulunamadi!
pause
exit /b 1
