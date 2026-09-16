# TeleStream

Fork di DrKLO/Telegram, ridotto al solo modulo Android TV.

## Moduli
- `TMessagesProj` — libreria condivisa (Java + nativo via CMake/NDK)
- `TMessagesProj_AppTV` — app Android TV, l'unico modulo applicativo del repo

Gli altri moduli ereditati dall'upstream (App, AppStandalone, AppHuawei,
AppHockeyApp, AppTests) sono stati rimossi: questo repo serve solo per la TV.

## Aggiornamenti da upstream
Il remote `upstream` punta a https://github.com/DrKLO/Telegram.git.
Serve principalmente per tirare giù aggiornamenti di `TMessagesProj`
(fix di sicurezza, nuove funzionalità Telegram). Sync manuale, nessun
automatismo — va chiesto esplicitamente.

## Ambiente di sviluppo
Server remoto Hetzner CPX42 (Ubuntu 24.04, 8 vCPU, 16 GB, 300 GB),
accesso via VS Code Remote-SSH come utente `luigi`.

- JDK 17 (installato anche il 21, selezionabile con update-alternatives)
- Android SDK 35, build-tools 35.0.0
- NDK 27.2.12479018
- CMake 3.22.1 (il progetto dichiarava 3.10.2, sostituita in tutti i
  build.gradle; il pacchetto SDK esiste come `cmake;3.10.2.4988404`
  se serve tornare indietro)
- ccache attivo, 20 GB
- I build lunghi si lanciano dentro `tmux` (sessione `build`)

## Comandi
./gradlew :TMessagesProj_AppTV:assembleDebug
Varianti disponibili: debug, release. Nessun product flavor.

## Deploy
Niente emulatore. Si installa su TV Android fisiche via adb, o scaricando
l'APK in locale o con un tunnel SSH inverso verso la TV.
