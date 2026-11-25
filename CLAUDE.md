# TeleStream

Fork di DrKLO/Telegram, ridotto al solo modulo Android TV.

## Moduli
- `TMessagesProj` — libreria condivisa (Java + nativo via CMake/NDK)
- `TMessagesProj_AppTV` — app Android TV, l'unico modulo applicativo del repo

Gli altri moduli ereditati dall'upstream (App, AppStandalone, AppHuawei,
AppHockeyApp, AppTests) sono stati rimossi: questo repo serve solo per la TV.

## Struttura dell'app TV
Package `org.telegram.tv` in `TMessagesProj_AppTV/src/main/java`:
- `login/TvLaunchActivity` — entry point (LEANBACK_LAUNCHER): splash animata,
  poi va a `TvMainActivity` se l'account è già attivo, altrimenti al login
- `login/TvLoginActivity` — login via QR code (`exportLoginToken`, zxing)
- `activity/TvMainActivity` — griglia degli eventi; dialoga con il bot
  `@CherryStreaming_cbot` tramite `bot/BotSession`
- `bot/BotSession` — risoluzione del bot, `/start`, join dei canali,
  mute + archivio; `bot/MessageParser` trasforma i messaggi in `model/StreamEvent`
- `ui/TeamLogoLoader` (loghi da TheSportsDB v1), `ui/EventStatsLoader`
  (spettatori della live via `phone.getGroupCall`), `ui/SmoothFocusScrollView`
  (scroll fluido con il D-PAD)
- `activity/TvSettingsActivity` — impostazioni

I testi della UI sono in italiano, scritti direttamente nel codice.

## Aggiornamenti da upstream
Il remote `upstream` punta a https://github.com/DrKLO/Telegram.git.
Serve principalmente per tirare giù aggiornamenti di `TMessagesProj`
(fix di sicurezza, nuove funzionalità Telegram). Sync manuale, nessun
automatismo — va chiesto esplicitamente.

Al merge (su un branch separato) i conflitti "modify/delete" sui moduli
rimossi e sul `Dockerfile` si risolvono confermando la cancellazione.
Git considera `TMessagesProj_AppTV/build.gradle` derivato da quello di
AppStandalone, quindi le modifiche di upstream a quel file arrivano lì
come conflitti: vanno portate a mano, tenendo le parti specifiche della TV
(niente flavor, ABI solo ARM, `applicationId`, dipendenze leanback/zxing).

## Ambiente di sviluppo
In locale su Mac mini (Apple Silicon M6, 12 core, 16 GB RAM, SSD 228 GB,
macOS 27). IDE: Android Studio con il plugin Claude Code (si usa anche
VS Code). Il vecchio server remoto Hetzner non si usa più.

### Toolchain
Fornita da Android Studio, non ci sono JDK o SDK installati a parte:
- JDK: il JBR incluso in Android Studio
  (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) è un
  JDK 25, troppo nuovo per Gradle 8.13. Per Gradle si usa il JBR 21
  scaricato da Android Studio in `~/Library/Java/JavaVirtualMachines/jbr-21.0.11`
  (in Android Studio: Settings → Build, Execution, Deployment → Build Tools
  → Gradle → Gradle JDK)
- Android SDK: `~/Library/Android/sdk` (Android Studio scrive il path in
  `local.properties`, che è ignorato da git)
- Componenti SDK necessari: `platforms;android-36`, `build-tools;36.0.0`,
  `ndk;27.2.12479018`, `cmake;3.22.1`, `platform-tools`

### Versioni fissate
Allineate all'upstream (Telegram 13.2.2): non aggiornarle senza chiedere, e
non accettare l'AGP Upgrade Assistant che Android Studio propone all'apertura.
- Gradle 8.13 (wrapper), Android Gradle Plugin 8.13.2, Kotlin plugin 2.1.0
- compileSdk / targetSdk 36, minSdk 23, build-tools 36.0.0
- NDK 27.2.12479018, CMake 3.22.1, ABI solo `arm64-v8a` e `armeabi-v7a`

### Stringhe
Dalla 13.x upstream non usa più le stringhe come risorse Android: il plugin
`org.telegram.build-app-plugin` (in `buildSrc`) prende i `values*/strings.xml`
della libreria e dell'app TV, li impacchetta come asset `.pack` letti da
`LocaleController`, e in release li scarta dalle risorse (`tools:discard`).
Quindi nell'app TV:
- leggere sempre con `LocaleController.getString(...)`, mai con
  `getString()`/`setText(R.string...)` di Android o `android:text="@string/..."`
  nei layout (lì solo `tools:text`)
- le stringhe della libreria sono in `org.telegram.messenger.R`, quelle TV in
  `org.telegram.messenger.tv.R` (R non transitivo)
- le stringhe usate dal manifest (`app_name`) stanno in `res/values/app_name.xml`,
  non in `strings.xml`

### Primo setup
I submodule vanno inizializzati prima di aprire il progetto: senza,
il sync Gradle fallisce (`settings.gradle` include `TMessagesProj/lib/jlatexmath`
e `TMessagesProj_Modules/media`) e mancano i sorgenti nativi (ffmpeg, boringssl,
tgcalls, td, ecc.).

    git submodule update --init --recursive --depth=1

In Android Studio si apre la cartella radice del repo (Open, non Import).

## Comandi
Nel terminale, anche in quello di Android Studio, `JAVA_HOME` non è
impostato e `adb` non è nel PATH:

    export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home"
    export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
    ./gradlew :TMessagesProj_AppTV:assembleDebug

Varianti disponibili: debug, release. Nessun product flavor.
APK in `TMessagesProj_AppTV/build/outputs/apk/<variante>/`.

Entrambe le varianti firmano con `TMessagesProj/config/release.keystore`,
con le credenziali `RELEASE_*` in `gradle.properties`.

## Deploy
Si testa su TV Android fisiche via adb: in rete locale con
`adb connect <ip-tv>:5555` (debug di rete attivo nelle opzioni
sviluppatore della TV), oppure via USB. Dopo l'`adb connect` la TV
compare anche nel selettore dei dispositivi di Android Studio.
