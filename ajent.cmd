@echo off
setlocal

set "AJENT_ROOT=%~dp0"
if exist "%AJENT_ROOT%ajent.jar" (
  set "AJENT_JAR=%AJENT_ROOT%ajent.jar"
) else (
  set "AJENT_JAR=%AJENT_ROOT%ajent-cli\target\ajent.jar"
)

if defined AJENT_JAVA_HOME (
  set "AJENT_JAVA=%AJENT_JAVA_HOME%\bin\java.exe"
) else if defined JAVA_HOME (
  set "AJENT_JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "AJENT_JAVA=java"
)

if not exist "%AJENT_JAR%" (
  echo Ajent has not been packaged. Run: mvn -q package 1>&2
  exit /b 1
)

"%AJENT_JAVA%" -jar "%AJENT_JAR%" %*
exit /b %ERRORLEVEL%
