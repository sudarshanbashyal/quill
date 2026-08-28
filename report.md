# Project Report — Quill

**Team:**

| Name | Student ID |
|---|---|
| _TODO_ | _TODO_ |
| _TODO_ | _TODO_ |

---

## 1. Use Case

**Problem statement**
Students accumulate notes, voice memos, sketches, and flashcards across multiple apps (a notes app, a separate whiteboard/drawing tool, a flashcard app, sometimes a voice recorder) with no single place that ties them together, and with no offline-first guarantee when they need to review on the go or in a lecture hall with poor connectivity.

**Motivation**
Quill was built to consolidate note-taking, hand-drawn whiteboards, voice memos, spaced-repetition flashcards, and quizzes into one offline-first Android app, so a student's study material lives in one coherent place instead of being scattered across single-purpose tools.

**Real-world relevance**
Every student who studies from lecture notes has hit the same friction: sketching a diagram means switching apps, turning notes into flashcards means re-typing them elsewhere, and studying on a commute or in a room with no signal means half these tools stop working. Quill's offline-first design (local SQLite, no server, P2P collaboration over Nearby Connections instead of the internet) targets exactly that gap.

---

## 2. UX Design and Target Group

### 2.1 Target Group

- **Primary users:** University/college students who take notes during lectures or self-study and want to turn those notes into review material (flashcards, quizzes) without switching apps.
- **Secondary users:** Study-group partners who join a live collaborative whiteboard session with a primary user (e.g. to co-annotate a diagram during a group study session), and users of the Wear OS companion (same primary user, on their watch) who capture a quick voice memo or review due flashcards without pulling out their phone.
- **User needs:** Capture information quickly during a lecture (typed notes, voice memo, or a quick sketch) in a single tool; convert that material into spaced-repetition flashcards and quizzes; keep sensitive collections (e.g. exam drafts) locked behind biometrics; study on the move, including from a smartwatch, without needing network connectivity.
- **Key usage scenario / user story:** A student is in a lecture and the professor draws a diagram on the board. Rather than switching to a separate whiteboard app, they open Quill, sketch the diagram directly into their lecture note, and later convert the key definitions in that note into flashcards for spaced review — reviewing the first few due cards from their watch on the walk out of the building.

### 2.2 Mockups

_TODO — insert 2-3 low-fidelity mockups here (Figma/Excalidraw/hand-drawn/AI-generated; state which tool was used) covering, at minimum: Note Editor (text + whiteboard + voice), Flashcard Review, and Whiteboard Collaboration join flow._

| Screen | Mockup | Addresses which user need? |
|---|---|---|
| Note Editor | _TODO_ | Capture text, sketch, and voice in one place |
| Flashcard Review | _TODO_ | Convert notes into spaced-repetition review material |
| Whiteboard Collaboration (join via QR) | _TODO_ | Study-group co-annotation without a network/server |

### 2.3 Design Rationale

**What was deliberately left out / simplified:**
- No cloud sync or account system — all data is local SQLite; whiteboard collaboration uses peer-to-peer Nearby Connections instead of a server, trading multi-device continuity for zero-infrastructure offline use.
- No Room/DataStore — persistence is a hand-rolled `SQLiteOpenHelper` layer with repository classes wrapping raw `Cursor`/`ContentValues`, and a single-threaded `AppExecutors` to avoid fighting SQLite's writer lock. This was a deliberate simplification to keep the threading model predictable rather than introducing full reactive (Flow/LiveData) data layers.
- Whiteboard Picture-in-Picture does not support drawing while in PIP — see incident below; this was consciously scoped down rather than solved with a heavier overlay-window rework.

**A UX decision an AI assistant suggested differently:**
When asked to make the whiteboard drawable while in Picture-in-Picture mode, the AI assistant explained this is impossible with Android's system PIP by platform design (PIP surfaces are touch-inert) and that true interactivity would require abandoning system PIP for a `SYSTEM_ALERT_WINDOW` overlay — a substantially larger rework. Rather than picking a direction unilaterally, it presented the trade-off and let the team decide. The team chose to keep system PIP with tap-to-expand instead of taking on the overlay-window rework, prioritizing shipping a working feature over a marginal interaction improvement.

---

## 3. System Architecture Diagram

_TODO — insert your hand-built/hand-edited diagram here. Suggested components based on the codebase: `:app` (Java, Views/XML, Navigation Component) ↔ local SQLite (via `SQLiteOpenHelper` + repositories) ↔ `AudioPlaybackService` (foreground service) ↔ Nearby Connections (P2P whiteboard collab, no server) ↔ Play Services Wearable Data Layer ↔ `:wear` (Kotlin/Compose, Wear Compose Material3 + ProtoLayout tiles) ↔ `:study` (plain Java library, SM-2 scheduling + quiz generation, shared by `:app` and `:wear`)._

### 3.1 Architecture Justification

**Why this structure?**
_TODO — 5-8 sentences. Suggested angle: `:study` is a plain `java-library` module (deliberately with no Android dependency) so scheduling/quiz logic is identical and independently testable on both the phone app and the Wear OS companion, and so it fails to compile if an Android import ever leaks in._

**What alternative was considered, and why was it rejected?**
_TODO — e.g. Room vs. hand-rolled SQLiteOpenHelper; a client-server model for whiteboard collaboration vs. Nearby Connections P2P._

**Walk-through:** trace the "sketch a diagram into a lecture note" user action (from 2.1) through every component in the diagram — _TODO, once the diagram exists._

### 3.2 Third-Party Libraries

| Library | Purpose | Why this one (over alternatives)? |
|---|---|---|
| AndroidX AppCompat / Material / ConstraintLayout / RecyclerView | Core UI scaffolding, Views-based | Standard, well-supported baseline for a Views/XML (non-Compose) phone app |
| Navigation Component (fragment/ui-ktx) | Single-Activity, fragment-based navigation | Avoids manual FragmentTransaction bookkeeping across the app's many screens |
| `androidx.biometric` | Fingerprint/face lock on private collections | Official Jetpack API for biometric prompts, avoids handling raw `BiometricManager` callbacks manually |
| `androidx.work:work-runtime` | Background jobs (e.g. scheduled flashcard reminders) | Standard WorkManager guarantees deferred/constrained execution survives process death |
| `play-services-nearby` | Peer-to-peer whiteboard collaboration transport | No server needed — fits the offline-first design; avoids building/hosting a signaling backend |
| `play-services-wearable` | Phone ↔ watch Data Layer sync | Official transport for Wear OS companion communication (MessageClient/DataClient) |
| `play-services-code-scanner`, `barcode-scanning-common`, `zxing-core` | QR-code join flow for whiteboard sessions | Avoids manually requesting camera permission — Play Services' own scanner UI/GMS barcode scanner handles that |
| Wear Compose Material3, ProtoLayout, `wear-tiles`, `watchface-complications-data-source-ktx` | Wear OS UI (review screen), tiles, complications | Required Jetpack Wear APIs for a native watch companion experience |

_TODO — add "why this one over alternatives" detail specific to your team's actual discussions where the placeholder above is generic._

---

## 4. Developer Diary

### 4.1 Development Milestones

| # | Date | Activity | Result | Issues |
|---|---|---|---|---|
| 1.0 | _TODO (project start)_ | Initial app architecture, note editor | Java/Views app skeleton, SQLite persistence layer | No validation considered initially |
| 2.0 | _TODO_ | Flashcards + SM-2 spaced repetition (`:study` module) | Flashcard decks, review scheduling shared between app and wear | — |
| 2.1 | 2026-07-13 | Note editor keyboard/scroll handling | Reverted after ~10 failed AI-driven attempts; kept only unrelated good parts (headings/lists, tap-to-focus) | Each fix traded one visible bug for another (toolbar detaching, notes flying off-screen) |
| 3.0 | 2026-08-06 | Audio waveform block in notes | Long-press-to-delete implemented | Draft gesture logic would have broken vertical scroll-through; caught and fixed before shipping |
| 4.0 | 2026-08-12 | Live whiteboard collaboration (Epic C, Nearby Connections) | Two-device sync working (strokes, undo, clear) | Joiner crash from `whiteboardId` foreign-key mismatch on first live test |
| 4.1 | 2026-08-16 | Home-screen widgets (Collections, Whiteboards, Flashcards) | Three `AppWidgetProvider` widgets shipped | 5-bug chain around locked-collection interaction; two bugs found by code reading, not stack traces |
| 4.2 | 2026-08-20 | Whiteboard collab stability (`fix/whiteboard`) | Non-host quit crash fixed | `requireActivity()` called from a detached fragment in an async callback |
| 4.3 | 2026-08-23 | Whiteboard Picture-in-Picture (`feat/pip`) | System PIP wired up, tap-to-expand instead of draw-in-PIP | Feature scope negotiated rather than over-engineered; session unverified on real device (no adb access) |

_TODO — fill in milestones 1.0 and 2.0 dates, plus anything before 2026-07-13 that isn't reflected above (this list only covers what's traceable from `memory/conversation.md` and recent git history)._

### 4.2 AI Interaction Log — Critical Incidents

| # | Milestone | What happened | AI's output (brief) | Your action / validation |
|---|---|---|---|---|
| 1 | 2.1 — Note editor keyboard/scroll (2026-07-13) | Editor keyboard covered the active line; auto-scroll didn't compensate. AI iterated ~10 times across different mechanisms (`fullScroll`, custom cursor-scroll math, `WindowInsetsCompat`, `adjustResize`/`adjustPan`), each attempt fixing one visible symptom while breaking another (toolbar detaching, short notes flying off-screen, title scrolled out of view). | Repeated code patches per mechanism, no working end state reached. | Rejected the entire line of attempts and reverted all scroll-related changes, keeping only the unrelated good parts (headings/lists, tap-to-focus). Lesson recorded: ask for a differentiated repro earlier and stop after 2-3 failed attempts at the same mechanism. |
| 2 | 3.0 — Audio waveform delete gesture (2026-08-06) | Long-pressing an audio waveform block to delete it instead scrubbed/played the clip. | First draft treated any drag exceeding touch slop as a scrub — which would have swallowed vertical scroll gestures whenever a recording sat under the finger. | Caught during the AI's own code review before shipping (not via a bug report); fixed with a per-direction gesture split so vertical drags return `false` and pass through to the parent scroll view. Verified on emulator by hand. |
| 3 | 4.0 — Live whiteboard collaboration crash (2026-08-12) | First live two-device test: the joiner app silently crashed to home screen; the host only saw a misleading "session ended" message. | AI diagnosed the true cause as a `SQLiteConstraintException` — incoming strokes kept the sender's `whiteboardId`, violating a local foreign-key constraint since host and joiner have independent local board rows. | Applied the AI's fix (re-tag incoming items onto the local `whiteboardId` at all three receive sites, deliberately leaving `authorId` untouched) and verified sync, undo, and clear across both devices. |
| 4 | 4.1 — Home-screen widgets + collection locking (2026-08-16) | After locking a collection, a pinned note stayed visible in its widget and tapping it deleted the real note; creating a note in a locked collection then crashed the app on every subsequent launch. | First fix (exception guards in the widget's `RemoteViewsFactory`) did not stop the crash — the real second cause was unguarded Wear OS publisher calls in `MainActivity.onCreate`, a genuinely separate bug rather than a failed first fix. | Diagnosed and fixed sequentially across five rounds; two further bugs (in `NoteRepository`/`NoteEditorFragment`) were found by manual code reading rather than from a stack trace. Verified on-device after each fix. |
| 5 | 4.3 — Whiteboard Picture-in-Picture scope (2026-08-23) | Asked for the whiteboard to be drawable while in PIP mode. | AI explained this is impossible with Android's system PIP by platform design (PIP surfaces are touch-inert), and that real interactivity would require replacing system PIP with a `SYSTEM_ALERT_WINDOW` overlay — a much larger rework — then presented that trade-off instead of deciding unilaterally. Separately caught a `FLAG_SECURE`-causes-black-PIP-thumbnail interaction with the app's biometric lock before shipping. | Chose to keep system PIP with tap-to-expand rather than take on the overlay rework. Session's changes were not verified on a real device (no adb access at the time) — flagged explicitly rather than claimed as tested. |

---

## 5. Android Features Report

### 5.1 Features Used

| Feature | Where it's used (screen/class) | Why this feature was needed |
|---|---|---|
| Foreground Service (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`) | `AudioPlaybackService` | Keeps voice-memo/read-aloud playback alive with lock-screen/notification controls when the app isn't in the foreground |
| App Widgets (`AppWidgetProvider` + `RemoteViewsService`) | Three home-screen widgets: Collections, Whiteboards, Flashcards | Quick access to pinned notes/whiteboards/due flashcards without opening the app |
| Nearby Connections (Bluetooth/BLE + Wi-Fi permissions) | Whiteboard collaboration session join/host flow | Peer-to-peer live collaboration without a server, consistent with the app's offline-first design |
| Biometric authentication (`androidx.biometric`) | Private/locked collections | Protects sensitive notes (e.g. exam drafts) behind fingerprint/face auth |
| Wear OS companion (Data Layer: `MessageClient`/`DataClient`) | `:wear` module — Capture, ReadAloud, Review activities; Tile services; Complication service | Voice capture and flashcard review from the wrist without pulling out the phone |
| WorkManager | Background/scheduled jobs (e.g. flashcard due reminders) | Guarantees deferred work survives process death and respects constraints |
| Custom URI/MIME deep links (`singleTask` `MainActivity`) | `.quill`/`.quillboard`/`.quillpack` file types, `quill://whiteboard/join` scheme | Import/export of notebooks and QR-code-based whiteboard session joining |
| Local persistence via `SQLiteOpenHelper` | Repository classes across the app | Offline-first data storage without a Room dependency, single-threaded `AppExecutors` to avoid fighting SQLite's writer lock |
| Notifications (`POST_NOTIFICATIONS`) | Playback controls, flashcard reminders | User-visible controls/reminders outside the app UI |

_TODO — confirm sensor/location usage: ACCESS_COARSE/FINE_LOCATION are declared for Nearby Connections' Bluetooth requirements, not for actual GPS features; state this explicitly if true, since a grader may otherwise assume a location feature exists._

### 5.2 Implementation Details

_TODO — pick at least 3 features from 5.1 and describe implementation depth (key classes/APIs, lifecycle considerations, permission handling). Suggested candidates given the depth of material already gathered: (a) Nearby Connections whiteboard collaboration — including the `whiteboardId` foreign-key bug and its fix; (b) Home-screen widgets — including the locked-collection interaction bug chain; (c) the foreground `AudioPlaybackService` for playback._

**Edge cases handled:**
_TODO — e.g. permission denied for Bluetooth/Nearby during collaboration, no network (not applicable — app is offline-first by design), configuration changes during an active whiteboard collaboration session, fragment detached during an async Nearby Connections callback (see Incident 3 in 4.2)._

### 5.3 AI's Role in Android-Specific Code

_TODO based on 4.2, but drafted here as a starting point:_ AI assistance was generally accurate on high-level API selection (Nearby Connections for P2P, WorkManager for background jobs, `androidx.biometric` for locking) but required correction on Android lifecycle edge cases in practice — e.g. calling `requireActivity()` from a fragment inside an async Nearby Connections callback after the fragment had already been detached (Incident 5, section 4.2), and an initial widget bug-fix that addressed only one of two independent root causes rather than the actual crash (Incident 4). No clearly deprecated-API hallucinations were identified in the material reviewed; the more consistent pattern was AI fixes solving the reported symptom without ruling out a second, unrelated cause.

---

## 6. Lessons Learned

- **Technical insights:** _TODO_ — e.g. a hand-rolled `SQLiteOpenHelper` layer with a single-threaded executor kept the threading model predictable at the cost of manual repository boilerplate compared to Room.
- **Architectural insights:** _TODO_ — e.g. keeping `:study` as a plain Java library with no Android dependency paid off by guaranteeing identical scheduling logic on phone and watch.
- **UX insights:** _TODO_ — e.g. the PIP scope negotiation (section 2.3) — accepting a platform constraint rather than over-engineering around it.
- **AI-related insights:** When an AI fix doesn't resolve a reported crash, that's a strong signal there may be a second, independent root cause rather than a failed first attempt (Incident 4, section 4.2); and stopping after 2-3 failed attempts at the same mechanism (Incident 1) is cheaper than iterating further.
- **What would you do again or differently:** _TODO_

## 7. Future Work

- **Possible refactoring:** _TODO_ — e.g. migrating the hand-rolled SQLite layer to Room now that the schema has stabilized.
- **Possible extensions:** _TODO_ — e.g. multi-device continuity for the whiteboard beyond the current P2P-only session model.
