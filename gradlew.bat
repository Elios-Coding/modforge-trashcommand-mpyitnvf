@rem ModForge AI gradlew bootstrapper
@echo off
setlocal
set DIR=%~dp0
set WRAPPER_JAR=%DIR%gradle\wrapper\gradle-wrapper.jar
if not exist "%DIR%gradle\wrapper" mkdir "%DIR%gradle\wrapper"
if not exist "%WRAPPER_JAR%" (
  echo Downloading gradle-wrapper.jar (Gradle 8.14.3)...
  powershell -Command "Invoke-WebRequest -Uri https://raw.githubusercontent.com/gradle/gradle/v8.14.3/gradle/wrapper/gradle-wrapper.jar -OutFile '%WRAPPER_JAR%'"
)
java -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
