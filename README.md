# Pi Terminal

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

Eine native Android-App für den SSH-Zugriff auf den Raspberry Pi – als Ersatz für den browserbasierten "Pi Connect", der auf Android zwei große Schwachstellen hat: keine Befehls-Historie über die Pfeiltasten und häufige Verbindungsabbrüche, sobald der Browser in den Hintergrund wandert.

Pi Terminal öffnet eine echte SSH-Sitzung mit einem echten Pseudo-Terminal (PTY) – genau wie ein Terminal-Programm am PC. Dadurch übernimmt die Shell auf dem Pi (bash/zsh) selbst die Zeilenbearbeitung, und Pfeiltasten-Historie, Tab-Vervollständigung & Co. funktionieren wie gewohnt.

## Funktionen

- **Echtes Terminal statt Textfeld**: SSH-Verbindung mit PTY (über [sshj](https://github.com/hierynomus/sshj)) und ein selbst geschriebener VT100/ANSI-Terminal-Emulator (Cursor-Steuerung, Farben, Bildschirm löschen, Alternate-Screen für Programme wie `vim`/`htop`).
- **Verbindung bleibt im Hintergrund bestehen**: Ein Foreground-Service hält die SSH-Verbindung aufrecht (SSH-Keepalive-Pakete, WakeLock, automatischer Reconnect mit Backoff), solange die App nur in den Hintergrund wechselt (Bildschirm aus, App-Wechsel). Wird die App aus der Liste der zuletzt genutzten Apps **weggewischt**, wird die Verbindung sauber getrennt und der Dienst beendet.
- **Passwort- oder Schlüssel-Login**: Anmeldung per Passwort oder privatem SSH-Schlüssel (OpenSSH-Format), inklusive Passphrase-Unterstützung.
- **Verschlüsselte Speicherung**: Host, Benutzername, Passwort/Schlüssel werden über den Android Keystore verschlüsselt auf dem Gerät abgelegt (`EncryptedSharedPreferences`).
- **Host-Key-Prüfung (Trust-on-first-use)**: Beim ersten Verbindungsaufbau zu einem Server wird dessen Schlüssel-Fingerabdruck gespeichert; ändert er sich später (z. B. bei einem möglichen Man-in-the-Middle-Angriff oder einer Neuinstallation des Pi), warnt die App explizit.
- **Copy & Paste**: Text im Terminal per Long-Press markieren und kopieren, per Taste in der Zusatztastenleiste einfügen.
- **Zusatztastenleiste**: Esc, Tab, Ctrl (als Umschalter für die nächste Taste), Pfeiltasten, Einfügen sowie gängige Shell-Sonderzeichen (`| ~ / -`) oberhalb der Bildschirmtastatur.
- **Korrekte Tastatureingabe**: Das Eingabefeld ist so konfiguriert, dass Tastaturen keine Autokorrektur/Wortvorschläge einblenden (das würde Shell-Eingaben verfälschen), inklusive Sonderbehandlung für Tastaturen, die Zeichen unterschiedlich übermitteln.

## Screenshots

*(noch nicht vorhanden – die App wurde bisher ausschließlich über reale Testläufe auf einem Gerät gegen einen echten Raspberry Pi verifiziert.)*

## Installation

### Fertige APK

Die aktuell gebaute Debug-APK liegt im Ordner [`releases/`](releases/) in diesem Repository, alternativ unter [Releases](../../releases), sofern dort eine Version veröffentlicht wurde.

Da die APK nicht über den Play Store installiert wird, muss auf dem Android-Gerät einmalig die Installation aus der Quelle erlaubt werden, aus der die Datei geöffnet wird (z. B. Dateien-App oder Browser-Downloads) – Android fragt danach automatisch beim ersten Installationsversuch.

### Voraussetzungen

- Android 8.0 (API 26) oder neuer.
- Ein Raspberry Pi mit aktiviertem SSH-Server (`sudo raspi-config` → *Interface Options* → *SSH* → *Enable*, oder eine leere Datei namens `ssh` im Boot-Verzeichnis der SD-Karte vor dem ersten Start).
- Handy und Pi müssen sich gegenseitig erreichen können – entweder im selben WLAN, oder über ein Overlay-Netzwerk wie [Tailscale](https://tailscale.com/) (auf beiden Geräten installiert), falls der Zugriff auch außerhalb des Heimnetzes funktionieren soll.

## Eine Verbindung einrichten

1. App öffnen → **+** (unten rechts) antippen.
2. **Name**: frei wählbar (z. B. „Wohnzimmer-Pi“).
3. **Host/IP-Adresse**: lokale IP des Pi (z. B. `192.168.1.42`) oder dessen Tailscale-IP (`100.x.x.x`, per `tailscale ip -4` auf dem Pi ermittelbar).
4. **Port**: `22` (Standard).
5. **Benutzername**: z. B. `pi`.
6. **Authentifizierung**: Passwort oder privater Schlüssel.
7. **Speichern** – die Verbindung erscheint in der Liste und lässt sich per Antippen öffnen.

Beim allerersten Verbindungsaufbau zu einem neuen Server wird kurz der Host-Schlüssel-Fingerabdruck bestätigt; das ist normal und passiert nur einmalig pro Server (Trust-on-first-use).

## Architektur

```
app/src/main/java/com/raspberryconnect/terminal/
├── data/        Verbindungsprofile, Validierung, verschlüsselte Speicherung
├── ssh/         SSH-Transportschicht (sshj), Host-Key-TOFU-Logik, Reconnect-Backoff
├── service/     Foreground-Service, hält die Verbindung im Hintergrund am Leben
├── terminal/    VT100/ANSI-Terminal-Emulator, Terminal-View (Rendering + Eingabe), Tastatur-Mapping
└── ui/          Activities: Verbindungsliste, Verbindung anlegen/bearbeiten, Terminal-Bildschirm
```

Design-Prinzip: Die reine Logik (Terminal-Emulator, Tastatur-Mapping, SSH-Transport, Reconnect-Strategie, Host-Key-Prüfung, Repository) ist bewusst frei von Android-spezifischem Code gehalten und dadurch ohne Emulator auf der JVM testbar. Android-spezifischer Code (Views, Activities, Services) bindet diese Bausteine nur zusammen.

### Warum ein eigener Terminal-Emulator?

Der eigentliche Kern des ursprünglichen Problems ("Pfeiltasten-Historie funktioniert im Browser nicht") liegt daran, dass ein normales Web-Eingabefeld kein echtes Terminal ist: Es gibt keine Zeilenbearbeitung durch die Shell, keine Cursor-Steuerung, kein "Bildschirm". `TerminalEmulator.kt` interpretiert die vom Pi zurückgesendeten VT100/ANSI-Escape-Sequenzen (Cursor bewegen, Zeile löschen, Farben, Bildschirm wechseln) genauso, wie es ein Terminal-Programm am PC tun würde – dadurch funktioniert bashs eigene Zeilenbearbeitung samt Historie korrekt.

## Sicherheit

- Passwörter und private Schlüssel werden ausschließlich verschlüsselt über den Android Keystore gespeichert, nie im Klartext.
- Host-Keys werden nach dem Trust-on-first-use-Prinzip geprüft; ein späterer Wechsel des Schlüssels wird nicht automatisch akzeptiert, sondern der Nutzerin/dem Nutzer explizit zur Entscheidung vorgelegt.
- Für den Schlüsselaustausch wird die vollständige BouncyCastle-Implementierung eingebunden, da Androids eigene, abgespeckte BouncyCastle-Variante moderne Algorithmen wie X25519/Curve25519 nicht unterstützt.

## Bekannte Einschränkungen

- Vollbildprogramme wie `vim` oder `htop` funktionieren größtenteils (Alternate-Screen-Umschaltung wird unterstützt), aber nicht jede denkbare Escape-Sequenz ist implementiert.
- Keine echten 24-Bit-Farben (Truecolor); SGR-Truecolor-Anfragen werden auf die nächstliegende 256-Farben-Palette abgebildet.
- Keine Portweiterleitung/SFTP/SCP – die App ist bewusst auf den interaktiven Shell-Zugriff fokussiert.

## Entwicklung

### Bauen

```bash
./gradlew assembleDebug
```

Die fertige APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.

### Tests

```bash
./gradlew testDebugUnitTest
```

Die Test-Suite deckt sowohl reine Logik (Terminal-Emulator, Tastatur-Mapping, Reconnect-Strategie, Verbindungs-Repository, Host-Key-TOFU-Logik) als auch einen echten Protokolltest ab: `SshjTerminalTransportIntegrationTest` startet einen eingebetteten SSH-Testserver (Apache MINA SSHD) und prüft Authentifizierung, PTY-Zuweisung, Datenaustausch und Host-Key-Verhalten gegen die reale sshj-Implementierung – ohne echten Raspberry Pi und ohne Mocking des SSH-Protokolls.

### Tech-Stack

| Bereich | Bibliothek |
|---|---|
| SSH-Client | [sshj](https://github.com/hierynomus/sshj) |
| Kryptografie | Bouncy Castle (`bcprov-jdk18on`, `bcpkix-jdk18on`) |
| Nebenläufigkeit | Kotlin Coroutines |
| Serialisierung | kotlinx.serialization |
| UI | AndroidX, Material Components, ViewBinding |
| Sicherer Speicher | AndroidX Security Crypto (`EncryptedSharedPreferences`) |
| Tests | JUnit 4, Apache MINA SSHD (eingebetteter Testserver) |

## Lizenz

Dieses Projekt steht unter der [GNU General Public License v3.0](LICENSE) (GPL-3.0). Das bedeutet insbesondere: Du darfst die App frei nutzen, verändern und weitergeben, musst dabei aber den Quellcode (auch von Änderungen) unter derselben Lizenz verfügbar machen.
