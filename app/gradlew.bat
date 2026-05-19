@echo off
rem Minimal gradlew.bat. Android Studio regenerates the wrapper JAR on first sync.
set DIR=%~dp0
set JAR=%DIR%gradle\wrapper\gradle-wrapper.jar
if exist "%JAR%" (
    "%JAVA_HOME%\bin\java.exe" -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
) else (
    where gradle >nul 2>&1
    if %ERRORLEVEL%==0 (
        echo gradle-wrapper.jar missing - falling back to system gradle
        gradle %*
    ) else (
        echo gradle-wrapper.jar is missing and no 'gradle' on PATH.
        echo Open the project in Android Studio once ^(it regenerates the wrapper^),
        echo or install Gradle 8.9 and run: gradle wrapper --gradle-version 8.9
        exit /b 1
    )
)
