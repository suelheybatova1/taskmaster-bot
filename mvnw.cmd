@ECHO OFF
SETLOCAL
SET "BASE_DIR=%~dp0"
FOR /F "tokens=2 delims==" %%A IN ('findstr /B "distributionUrl=" "%BASE_DIR%\.mvn\wrapper\maven-wrapper.properties"') DO SET "MAVEN_URL=%%A"
FOR %%A IN ("%MAVEN_URL%") DO SET "ARCHIVE_NAME=%%~nxA"
SET "MAVEN_VERSION=%ARCHIVE_NAME:apache-maven-=%"
SET "MAVEN_VERSION=%MAVEN_VERSION:-bin.zip=%"
IF DEFINED MAVEN_USER_HOME (SET "M2_HOME=%MAVEN_USER_HOME%") ELSE (SET "M2_HOME=%USERPROFILE%\.m2")
SET "MAVEN_DIR=%M2_HOME%\wrapper\dists\apache-maven-%MAVEN_VERSION%"
SET "MAVEN_BIN=%MAVEN_DIR%\apache-maven-%MAVEN_VERSION%\bin\mvn.cmd"
IF NOT EXIST "%MAVEN_BIN%" (
  MKDIR "%MAVEN_DIR%" 2>NUL
  powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%MAVEN_URL%' -OutFile '%MAVEN_DIR%\maven.zip'; Expand-Archive -Path '%MAVEN_DIR%\maven.zip' -DestinationPath '%MAVEN_DIR%' -Force; Remove-Item '%MAVEN_DIR%\maven.zip'"
)
CALL "%MAVEN_BIN%" %*
ENDLOCAL
