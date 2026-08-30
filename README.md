<div align="center">

<img src="docs/quill-logo.gif" alt="Quill" width="200">

# Quill

**An offline-first note-taking and study app for Android and Wear OS.**

Write notes, collaborate on whiteboards, turn Q&A blocks into flashcards and quizzes, and review them on your
wrist. Quill does not require an account, backend, or internet connection for its core features.

</div>

---

## Submission files

| File                    | Description          |
|-------------------------|----------------------|
| **`Quill Report.docx`** | Written report       |
| **`Quill Report.pdf`**  | Written report (PDF) |
| **`Quill_demo.mp4`**    | Demo video           |

All three files are in the repository root.

---

## Modules

Quill is split into three Gradle modules. The main reason for this separation is to keep the
study logic independent from Android so that it can be shared between the phone and the watch.

| Module       | Language                            | Description                                                                                                                                                                                                   |
| ------------ | ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`:app`**   | Java · `minSdk 26` / `targetSdk 36` | Main Android application. Contains the UI, SQLite database, sharing, P2P collaboration, exports, and home-screen widgets. Uses one `Activity` with Navigation Component fragments.                            |
| **`:shared`** | Java · plain JVM                    | Contains the study logic: SM-2 scheduling, review sessions, quiz generation, and the data structures used to communicate with the watch. It is deliberately an Android-free `java-library`.                   |
| **`:wear`**  | Kotlin + Compose · `minSdk 30`      | Wear OS companion app. Contains the tile, complication, flashcard review, voice capture, and read-aloud controls. Kotlin is used here because the `protolayout-material3` API does not provide Java builders. |

The `:wear` module depends on `:shared`, so both the phone and watch use the same SM-2
implementation instead of maintaining separate versions of the scheduling logic.

---

## Features

### Writing

* **Markdown notes** — headings, bold, italic, underline, and bullet lists. Each note is stored as a single Markdown document rather than separate database rows for each paragraph.
* **Rich embeds** — images, voice recordings, and whiteboards can be inserted directly into a note.
* **Whiteboards** — pan and zoom around a large drawing canvas. Boards can be standalone or attached to a note, with thumbnails and image export.
* **Collections and tags** — organise notes into collections and use colour-coded tags across collections.
* **Search, filtering, and sorting** — search note titles and content, filter by tags or pinned status, and sort using four different orderings.

### Studying

* **Flashcards** — Q&A blocks inside a note can be turned into a flashcard deck. Cards are scheduled using SM-2 and are graded as correct or incorrect.
* **Quizzes** — timed multiple-choice quizzes are generated locally from the flashcard pool. Other cards are used to generate distractor answers, so quiz grading does not depend on string matching or AI.
* **Study reminders and streaks** — WorkManager sends daily reminders, and the app keeps track of actual study activity.
* **Read-aloud** — notes can be read using text-to-speech, including voice recordings embedded in the note. Playback continues through a mini player when navigating to another screen.

### Sharing

* **`.quill` bundles** — export a note, whiteboard, or collection as a self-contained ZIP file through the Android share sheet. Users without Quill can still open the included Markdown.
* **Live whiteboard collaboration** — multiple devices can draw on the same board using Nearby Connections. A session is joined by scanning a QR code and does not require a server. Strokes have unique IDs, allowing repeated messages to be ignored safely.
* **Export** — notes can be exported as PDF or Markdown.

### Platform features

* **Biometric-locked collections** — collections can be protected using `BiometricPrompt`. Locked content is excluded from search, widgets, and the watch.
* **Home-screen widgets** — widgets for pinned notes and collections, recent whiteboards, and cards that are currently due.
* **Picture-in-Picture** — a shared whiteboard can continue running in a PiP window, including updates from another user.
* **Wear OS** — a tile and complication show cards that are due, while flashcard review, dictation, and playback controls are available from the watch.

---

## Building and installing

### Requirements

* **JDK 21**
* **Android SDK API 36** and build-tools
* Android device or emulator running **API 26+**
* Wear OS device or emulator running **API 30+** for the companion app

The Gradle wrapper is included, so a separate Gradle installation is not required.

If Android Studio cannot find your SDK, set the path in `local.properties`:

```properties
sdk.dir=/Users/you/Library/Android/sdk
```

### Phone app

Build and install the debug version:

```bash
./gradlew :app:installDebug
```

If more than one device is attached — a phone alongside a running emulator, say — adb cannot
guess which one you mean and the command fails with *"more than one device/emulator"*. Name the
target explicitly:

```bash
adb devices                                        # copy the serial you want

ANDROID_SERIAL=<phone-serial> ./gradlew :app:installDebug
```

Build a release APK:

```bash
./gradlew :app:assembleRelease
```

The APK will be created at:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

### Signing a release build

Neither module declares a `signingConfig`, so `assembleRelease` emits **unsigned** APKs — as the
filename says — and Android refuses to install those. Sign one before installing it:

```bash
BT=$ANDROID_HOME/build-tools/36.1.0

cp app/build/outputs/apk/release/app-release-unsigned.apk Quill-1.0.apk

$BT/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  Quill-1.0.apk

$BT/apksigner verify Quill-1.0.apk        # should print nothing and exit 0
```

The same command signs the Wear APK; only the input path changes.

The debug keystore is enough for grading and for installing over an existing debug build — same
key, so app data survives the upgrade. A build signed with a *different* key fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` and has to be uninstalled first. Anything distributed more
widely should use a keystore of its own.

### Installing on a physical phone

With USB debugging already enabled, the signed APK installs directly:

```bash
adb -s <phone-serial> install -r Quill-1.0.apk
```

Without it — and this is the shorter route if you just want the app on a phone — no cable or
developer options are needed:

1. Send `Quill-1.0.apk` to the phone by AirDrop, email or Google Drive.
2. Tap it in Files or from the download notification.
3. Android will block the install the first time: tap **Settings**, then allow the app doing the
   opening (Files, Chrome, Drive) to install unknown apps.
4. Go back, tap the APK again, and choose **Install**.

To enable USB debugging for the first route: **Settings → About phone**, tap **Build number**
seven times, then **Settings → System → Developer options → USB debugging**. Accept the
*"Allow USB debugging?"* prompt when the phone is plugged in — until you do, `adb devices` lists
it as `unauthorized`.

### Wear OS companion

The watch app is built as a separate APK and is installed directly onto the watch.

With both devices connected, first find the watch's serial:

```bash
adb devices
```

Then install the debug build:

```bash
ANDROID_SERIAL=<watch-serial> ./gradlew :wear:installDebug
```

Or build the release APK:

```bash
./gradlew :wear:assembleRelease
```

The release APK will be created at:

```text
wear/build/outputs/apk/release/wear-release-unsigned.apk
```

After installation, add the Quill tile from the watch's tile picker and add the complication
through the watch face editor.

> **Pairing note:** The tile and complication can render without a paired phone, but the
> phone-to-watch card synchronisation requires the devices to be paired through the Wear OS
> companion app. Two unpaired emulators will show **"Open on phone"** as the empty state.

---

## Tests

Run the JVM tests:

```bash
./gradlew :app:testDebugUnitTest :shared:test
```

Run the instrumented Android tests:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The `:shared` tests cover SM-2 scheduling, review sessions, quiz generation and scoring, and the
phone-to-watch due-card projection. Keeping this code in the Android-free `:shared` module means
these tests can run on the JVM.

---

## Repository layout

```text
app/            Phone application (:app)
shared/         Android-free study logic shared with the watch (:shared)
wear/           Wear OS companion (:wear)
docs/           README assets
memory/         Architecture notes, requirements, and build log
```

`memory/note.md` contains the longer architecture documentation, including the reasoning behind
some of the implementation decisions.

---
