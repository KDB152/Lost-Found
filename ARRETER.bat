@echo off
echo Arret de LostFound...
taskkill /fi "WINDOWTITLE eq LostFound - interface web*" /t /f >nul 2>nul
taskkill /fi "WINDOWTITLE eq LostFound - serveur CORBA*" /t /f >nul 2>nul
taskkill /fi "WINDOWTITLE eq LostFound - nommage*" /t /f >nul 2>nul
taskkill /im orbd.exe /f >nul 2>nul
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":4173 .*LISTENING"') do taskkill /pid %%p /f >nul 2>nul
echo LostFound est arrete. Les donnees restent dans le dossier data.
timeout /t 2 >nul
