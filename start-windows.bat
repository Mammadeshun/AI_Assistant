@echo off
REM Double-click this on Windows, or run it from the terminal.
cd /d "%~dp0"

python --version >nul 2>&1
if errorlevel 1 (
  echo.
  echo Python is not installed, or not on your PATH.
  echo.
  echo Install it from https://www.python.org/downloads/
  echo IMPORTANT: tick "Add python.exe to PATH" on the first screen.
  echo.
  pause
  exit /b 1
)

python start.py
pause
