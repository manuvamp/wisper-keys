@echo off
rem Builds the APK with the portable toolchain in %USERPROFILE%\dev (see README). Output: app\build\outputs\apk\release\
set JAVA_HOME=%USERPROFILE%\dev\jdk17
set ANDROID_HOME=%USERPROFILE%\dev\android-sdk
cd /d "%~dp0"
call gradlew.bat assembleRelease %*
