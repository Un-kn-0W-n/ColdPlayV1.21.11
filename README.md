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

## Using the client

- Press **Right Shift** in-game to open the ClickGUI.
  - **Left-click** a module to toggle it.
  - **Right-click** a module (or its key chip) to open its settings beside the row.
  - **Middle-click** a module to bind it to a key; Escape, Backspace or Delete unbinds.
  - Drag a panel or settings header to move it; the search box filters modules.
  - The bottom-left **Settings** button opens the HUD editor (**Edit GUI**), where the
    watermark and module list can be dragged and resized by their corners.
- The **Hud** module draws the module list, the watermark and pill-style health, armor,
  hunger and experience bars in place of the vanilla rows.
- **Alt Manager** sits on the title screen under the Realms button. The **Premium** tab takes
  a Microsoft refresh token (`M.C...`) or a Minecraft access token; the **Cracked** tab sets an
  offline username. Saved accounts are listed per tab (click to log in, `x` to delete), and a
  SOCKS5 proxy for server connections can be saved and switched on or off.

Config is stored at `config/coldplay.json` under the game directory (dev runs: `run/config/coldplay.json`).
