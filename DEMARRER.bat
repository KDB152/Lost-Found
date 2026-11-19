@echo off
setlocal
title LostFound - CORBA
cd /d "%~dp0"
chcp 65001 >nul

REM CORBA (idlj, orbd, org.omg.*) n'existe que jusqu'a Java 10 : il faut un JDK 8.
where idlj >nul 2>nul || goto :nojdk8
where orbd >nul 2>nul || goto :nojdk8
REM On utilise java/javac du MEME JDK 8 que idlj (meme si un Java plus recent est installe)
for /f "delims=" %%i in ('where idlj') do if not defined JBIN set "JBIN=%%~dpi"

if not exist "out\lostfound\web\WebGateway.class" (
  echo Compilation...
  if exist gen rmdir /s /q gen
  if exist out rmdir /s /q out
  mkdir gen
  mkdir out
  echo  - idlj : IDL vers Java
  "%JBIN%idlj" -fall -td gen idl\LostFound.idl
  if errorlevel 1 (echo [ERREUR] idlj a echoue. & pause & exit /b 1)
  if exist sources.txt del sources.txt
  for /r gen %%f in (*.java) do call :addsrc "%%f"
  for /r src %%f in (*.java) do call :addsrc "%%f"
  echo  - javac
  "%JBIN%javac" -nowarn -encoding UTF-8 -d out @sources.txt
  if errorlevel 1 (echo [ERREUR] La compilation a echoue. & del sources.txt & pause & exit /b 1)
  del sources.txt
)

set ORBARGS=-ORBInitialPort 1050 -ORBInitialHost 127.0.0.1 -ORBServerHost 127.0.0.1

echo 1/3 Service de nommage CORBA (orbd, port 1050)...
start "LostFound - nommage" /min "%JBIN%orbd" -ORBInitialPort 1050 -ORBServerHost 127.0.0.1
timeout /t 4 /nobreak >nul
echo 2/3 Serveur CORBA LostFound...
start "LostFound - serveur CORBA" /min "%JBIN%java" -Dfile.encoding=UTF-8 -cp out lostfound.server.ServerMain %ORBARGS%
timeout /t 4 /nobreak >nul
echo 3/3 Interface web...
start "LostFound - interface web" /min "%JBIN%java" -Dfile.encoding=UTF-8 -cp out lostfound.web.WebGateway %ORBARGS%
timeout /t 3 /nobreak >nul
start "" http://localhost:4173

echo.
echo  LostFound est lance : http://localhost:4173
echo  Les autres PC du reseau ouvrent http://IP-DE-CE-PC:4173
echo  (l'adresse exacte est affichee dans la fenetre "interface web").
echo  Pour tout arreter : ARRETER.bat
echo.
pause
exit /b 0

:nojdk8
echo [ERREUR] idlj / orbd introuvables.
echo CORBA a ete retire de Java a partir de Java 11 : installez un JDK 8
echo (par exemple Eclipse Temurin 8, en cochant "Add to PATH"), puis relancez.
pause
exit /b 1

:addsrc
set "f=%~1"
set "f=%f:\=/%"
>>sources.txt echo "%f%"
exit /b 0
