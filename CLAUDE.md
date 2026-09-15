# TeleStream

Fork di DrKLO/Telegram con un modulo aggiuntivo per Android TV.

## Moduli
- `TMessagesProj` — libreria condivisa (Java + nativo via CMake/NDK)
- `TMessagesProj_AppTV` — app Android TV, il modulo su cui si lavora
- Altri moduli (App, AppStandalone, AppHuawei, AppHockeyApp, AppTests) ereditati dall'upstream

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

## Stato attuale
Il build nativo (ffmpeg, boringssl, libvpx, dav1d, opus) compila senza errori.
Si ferma su `compileDebugJavaWithJavac` del modulo TV.

Errori aperti:
1. TvLoginActivity:380 — QRCodeWriter.encode() con argomenti nell'ordine
   sbagliato: manca BarcodeFormat.QR_CODE come secondo parametro
2. BotSession.java e MessageParser.java — TLRPC.TL_keyboardButtonRow e
   TLRPC.KeyboardButton non esistono con quel nome qualificato; vanno
   individuate le classi reali nel codebase

Già risolto: ZXing non era visibile al modulo TV perché dichiarato come
`implementation` in TMessagesProj. Aggiunto
`implementation 'com.google.zxing:core:3.5.4'` in TMessagesProj_AppTV.

## Deploy
Niente emulatore. Si installa su TV Android fisiche via adb, o scaricando
l'APK in locale o con un tunnel SSH inverso verso la TV.