# ColdPlay 1.21.11

Minimal client-only Fabric project for Minecraft 1.21.11.

## IntelliJ IDEA

1. Open this folder as a Gradle project.
2. Set both the Project SDK and Gradle JVM to JDK 21.
3. Reload Gradle, then run the generated **Minecraft Client** configuration.

## Terminal

This machine's global `JAVA_HOME` points to Java 8, so select JDK 21 for the current PowerShell session:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'
.\gradlew.bat build
.\gradlew.bat runClient
```

The built mod is `build/libs/coldplay-1.0.0.jar`.
