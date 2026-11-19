@echo off
cd /d "%~dp0"
if exist out rmdir /s /q out
if exist gen rmdir /s /q gen
echo Le code sera recompile au prochain DEMARRER.bat.
pause
