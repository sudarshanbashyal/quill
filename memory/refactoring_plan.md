# Quill — structural refactoring plan

Written 2026-08-23, from an architecture sweep of `app/src/main/java/mse/quill` at
`feat/multiUserWhiteboard` (32k LOC across `:app`, `:study`, `:wear`).

Each item below is **cause → solution → steps**. They are ordered by leverage, not by
size. Nothing here is a bug hunt — these are structural findings: coupling, cohesion,
information hiding, interface consistency, and file/package layout. Behaviour-preserving
throughout; if a step changes what the app *does*, it has been mis-scoped.

**Status key**: `OPEN` = not started. `DONE` = landed, kept here for the record.

Related: [requirements.md](requirements.md) (what to build), [note.md](note.md)
(architecture as-built), [conversation.md](conversation.md) (session log).

---

## R1 — `WhiteboardFragment` is a god class `DONE 2026-08-26`

**Cause.** 1421 lines, ~90 methods, four unrelated responsibilities in one object:
drawing tools, board persistence (title/background/text/undo), export/share, and the
entire collaboration surface. Roughly `WhiteboardFragment.java:895–1420` is collab and
nothing else — hosting, QR scan, permission flow, roster chips, snapshot chunking,
message relay, error mapping.

Snapshot reassembly state in particular is *fragment fields* — `pendingSnapshotChunks`,
`pendingSnapshotStrokes`, `pendingSnapshotTexts`, `pendingSnapshotCount` — so a
half-received board snapshot is owned by a UI object that Android may destroy mid-transfer.

This is why collab bugs have been hard to localise: view lifecycle, canvas state and
network state are interleaved in one class, so any of the three can be the cause of a
symptom in either of the others.

**Solution.** Extract a `WhiteboardCollabController` that owns everything collab, and
leave the fragment owning the canvas. The controller talks to `CollabSessionHolder`
(already the session owner) and calls back into a narrow interface the fragment
implements — `applyStroke`, `applyText`, `applyClear`, `showRoster`, `showError` — so
the fragment never sees a `CollabMessage` again.

**Steps.**

1. Define `interface WhiteboardCanvas` in `ui/whiteboard/` with only what collab needs
   to do to the board: `applyStroke(Stroke)`, `applyText(WhiteboardText)`,
   `retract(String id)`, `clearAll()`, `replaceAll(List<Stroke>, List<WhiteboardText>)`.
   `WhiteboardFragment` implements it. This is the whole seam — get it right and the
   rest is mechanical.
2. Move snapshot reassembly (`pendingSnapshot*` fields + `applySnapshot`) into the new
   controller. Do this first, alone, and verify a 3-device join still converges: it is
   the one piece with real state, and moving it separately keeps the diff readable.
3. Move the roster: `collabListener`, `updateCollabRoster`, `collabRosterNames`,
   `showCollabRoster`, `collabChips`, `applyCollabRoleToUi`. The chip views stay in the
   fragment; the controller hands it a `List<String>` and nothing more.
4. Move entry/exit: `showCollabEntry`, `startHosting`, `showHostInvite`,
   `updateHostInviteStatus`, `startJoinByScan`, `saveCopyThenScan`, `scanForSession`,
   `joinWithToken`, `endCollabSession`, `clearCollabLocalState`, `attemptExit`.
5. Move error mapping: `messageFor(CollabSession.Failure)`, `showCollabError`.
6. Fragment keeps `onCreate`/`onViewCreated`/tool selection/undo/export and delegates
   the collab button to the controller. Target: fragment under 800 lines, controller
   ~500.

**Do not** attempt this in the same pass as R2 — both touch the same file and the
combined diff stops being reviewable.

**What landed.** `WhiteboardCanvas` (the five-method seam) and
`WhiteboardCollabController` (597 lines) as planned; the fragment implements
`WhiteboardCollabController.Host`, which is `WhiteboardCanvas` plus six things only a
Fragment can do — permission launcher, roster chip, role UI, navigation, board content,
board copy. Snapshot reassembly moved with it, so a half-received board is no longer
owned by a UI object. The fragment no longer imports `CollabMessage`, `CollabSession`,
`CollabSessionHolder`, `QrCodes`, `SessionCode` or `SessionScanner` at all.

The fragment came out at **1124 lines, not the 800 targeted**, and the gap is honest
rather than incomplete work: the 800 figure was measured against the 1421-line file, and
Picture-in-Picture (~120 lines) landed on this screen afterwards. What remains is the
canvas, tools, title, background, text entry, export/share, PIP, and the ~200-line `Host`
implementation — which is genuinely canvas-and-database work and belongs here. Export and
share are the next extractable seam if this file is opened again; they were not in R1's
scope and were left alone deliberately.

---

## R2 — The domain model lives inside the UI package `DONE 2026-08-26`

**Cause.** `NoteSegment`, `TextSegment`, `QaSegment`, `ImageSegment`, `AudioSegment`,
`WhiteboardSegment` and `HeadingMarker` sit in `ui/notes/editor/model/`, and are imported
by **eleven packages** — including `data/NoteRepository.java:29-31`,
`data/serialization/NoteDocument.java:11-15`, `data/serialization/MarkdownSerializer.java:15`
and `share/BundleWriter.java:30-32`.

So persistence, the Markdown format and the `.quill` export format all depend on a
package named `ui.notes.editor`. The dependency arrow points the wrong way: the storage
layer cannot be compiled, reasoned about, or tested without the editor's package coming
with it, and the *file format* is nominally owned by a screen.

**Solution.** Move the seven classes to `data/model/`, which already exists and already
holds `Note`, `Stroke`, `Whiteboard`, `Tag`, `Collection`. Pure move; no logic changes.

**Steps.**

1. `git mv app/src/main/java/mse/quill/ui/notes/editor/model/*.java
   app/src/main/java/mse/quill/data/model/`.
2. Rewrite the `package` line in each of the seven files.
3. Rewrite imports across the tree: `mse.quill.ui.notes.editor.model` →
   `mse.quill.data.model` (sed over `app/src/main`, `app/src/test`, `app/src/androidTest`).
4. Delete the now-empty `ui/notes/editor/model/` directory.
5. `./gradlew :app:compileDebugJavaWithJavac` — a clean compile is the whole proof.

`TextSegment` holds an `android.text.Spannable`, so `data/model` is not Android-free
after this and cannot move to `:study`. That is fine and expected — `data/model` already
imports Android elsewhere. Note it so nobody later mistakes this for a step toward a
pure-JVM domain module.

**What landed.** Exactly the five steps, across 37 files. `NoteSegment`'s class comment
was rewritten as part of it: it opened "Segments are a view-layer concept", which was the
belief the old package encoded and is not what the codebase does — three of the four
things that depend on segments are storage and file-format code. The Spannable caveat is
recorded there too, where someone reaching for `:study` will actually read it.

---

## R3 — The data layer calls upward into the widget layer `DONE 2026-08-26`

**Cause.** `NoteRepository` has eight inline calls to
`mse.quill.widget.WidgetUpdater.notify*Changed(appContext)` (`:88, :262-265, :360-361,
:454-455, :486, :497`), and `CollectionRepository`, `FlashcardRepository`,
`QuizRepository`, `CollectionLockRepository` and `WhiteboardRepository` do the same.
They are written **fully qualified and inline** rather than imported, which is the tell
that it was bolted on rather than designed.

Consequences: the data layer knows home-screen widgets exist; a repository unit test
drags the widget stack in; and the set of things that must be notified on a write is
scattered across six files, so a seventh repository added tomorrow will simply forget.

**Solution.** Invert it. Repositories announce *what changed*; whoever cares subscribes.

**Steps.**

1. New `data/DataChangeNotifier.java` — a tiny singleton with
   `void notifyChanged(Change what)` where `Change` is an enum
   (`COLLECTIONS`, `NOTES`, `WHITEBOARDS`, `FLASHCARDS`), plus
   `addListener` / `removeListener`.
2. Replace every `WidgetUpdater.notifyXChanged(appContext)` call in `data/` with
   `DataChangeNotifier.getInstance().notifyChanged(Change.X)`. Import it properly.
3. `WidgetUpdater` registers itself as a listener once, from `QuillApplication.onCreate`
   (or `MainActivity` if there is no Application subclass yet), and does exactly what it
   does today in response.
4. Grep `data/` for `mse.quill.widget` and `mse.quill.ui` — the only remaining hits
   should be R2's segment imports, which R2 removes.

After R2 and R3, `data/` should import nothing from `ui/` or `widget/` at all. That is
the acceptance test for both, and it is worth writing down as a rule: **`data/` may not
import `ui/` or `widget/`.**

**What landed.** `DataChangeNotifier` with `Change` = `NOTES`, `COLLECTIONS`,
`WHITEBOARDS`, `FLASHCARDS`, `EVERYTHING`. The last was not in the plan and is needed:
`CollectionLockRepository` had two `notifyAllChanged` calls, and locking a collection
genuinely is not one list's business. 24 call sites across five repositories, imported
properly.

Two things fell out of it that the plan did not anticipate:

- There *was* no `Application` subclass, so `QuillApplication` is new — one class, one
  manifest attribute. `MainActivity` was the fallback the plan allowed, but a widget's
  `RemoteViewsService` or a Wear message can write without the activity ever starting,
  and those writes would then leave the widgets stale.
- `WhiteboardRepository.appContext` is now dead and gone, along with its null-Context
  no-op. That is a small **behaviour change, deliberately kept**: create/rename/delete
  called on a database-constructed instance now refreshes the widget where it silently
  did not before. The old comment justified the gap by saying those callers "run far more
  often than a widget needs to refresh" — but they call `insertSync`/`getByIdSync`, not
  the three notifying methods, so in practice nothing fires more often than it used to.
  It also makes R5 simpler: that field was half the reason for the second constructor.

`data/` now imports `widget/` nowhere. The one remaining `ui/` import is
`QuizRepository → mse.quill.ui.quiz`, which is R7(b)'s package name lying about pure
logic in `:study`, not a layering violation.

---

## R4 — Two threading models for the same database `DONE 2026-08-23`

**Cause.** `AppExecutors` is deliberately a *single* background thread, documented as
existing "so concurrent repositories never contend for the SQLite write lock."
`WhiteboardFragment` bypassed it with 20 raw `new Thread(...)` calls, and
`CollabSessionHolder` with one more.

Not cosmetic. A thread was spawned per completed stroke (`:708`) and another to delete on
undo (`:744`), with nothing ordering them — a fast undo could issue its delete on a
thread that beat the insert. Every one of those threads also contended with the
executor's connection for the write lock the executor exists to serialize.

Compounding it, those threads called `requireActivity().runOnUiThread(...)` and
`requireContext()` *from the background thread*, which throws `IllegalStateException` if
the fragment detached first — the same crash class the collab plan's "crash fix" section
patched at the listener level, still present here.

**Solution / what landed.** All 21 call sites moved onto `AppExecutors`. Fire-and-forget
row writes became async repository methods; multi-step work uses `diskIO(...)` +
`mainThread(...)` directly, which also removes every background-thread `requireActivity()`.
See the conversation log for 2026-08-23.

---

## R5 — Inconsistent repository construction `DONE 2026-08-26`

**Cause.** Three constructor conventions for one role:

| Repository | Takes |
|---|---|
| `NoteRepository`, `CollectionRepository`, `FlashcardRepository`, `QuizRepository`, `TagRepository`, `CollectionLockRepository` | `Context` |
| `StrokeRepository:29`, `WhiteboardTextRepository:24` | `AppDatabase` |
| `WhiteboardRepository:36,:41` | **both** (two public constructors) |

A caller cannot guess which form a given repository wants, and the `AppDatabase`-taking
pair is — not coincidentally — the pair that also skipped `AppExecutors` until R4, because
taking a raw `AppDatabase` is what made "just spawn a thread" the path of least resistance.

**Solution.** One convention: `Repository(Context)`, resolving `AppDatabase` and
`AppExecutors` internally, exactly as the six majority repositories do.

**Steps.**

1. Change `StrokeRepository` and `WhiteboardTextRepository` to take `Context`; keep the
   `AppDatabase` constructor package-private *only* if a caller genuinely needs to share
   a handle (check `WhiteboardThumbnails` and `CollabSessionHolder` first — both may
   simply pass a context).
2. Drop `WhiteboardRepository(AppDatabase)`; migrate its callers to the `Context` form.
3. Grep for `new StrokeRepository(`, `new WhiteboardTextRepository(`,
   `new WhiteboardRepository(` and fix the ~6 call sites.

Small, and it is a prerequisite for R6 being worth doing.

**What landed.** All three now take only `Context`. The package-private escape hatch in
step 1 turned out not to be needed by anyone: every one of the twelve call sites already
had a `Context` in hand and was calling `AppDatabase.getInstance(...)` on the line above
purely to satisfy the constructor. `AppDatabase` is a singleton, so nothing was sharing a
handle in any meaningful sense — the second constructor bought nothing and cost the
confusion. Five files dropped their now-dead `AppDatabase` import with it.

Two constructors, in `WhiteboardRepository`, became one; the `appContext` field that made
them differ had already gone in R3.

---

## R6 — No abstraction boundary between UI and data `OPEN`

**Cause.** Ten fragments each `new` their concrete repositories directly;
`NoteEditorFragment` constructs six. There are no interfaces, and no ViewModels — `AppLock`
is the only file in `:app` that mentions `LiveData`.

The cost is already visible in the test layout: repository tests had to go in
`androidTest` and need a device, because there is no way to substitute the data layer.
Config changes also re-fetch everything from disk, since nothing outlives the fragment.

**Solution.** *Not* a full MVVM retrofit — that is a rewrite, and the callback style
works. Extract interfaces only where they buy a test.

**Steps.**

1. Pick the two repositories whose logic most deserves JVM tests. `NoteRepository`
   (Markdown round-trip, orphan cleanup, FTS index maintenance) and `FlashcardRepository`
   (orphan marking, due counts) are the candidates.
2. Extract `interface NoteStore` / `interface FlashcardStore` holding the methods the UI
   actually calls — not every public method, only the ones with callers outside `data/`.
   The interface *is* the information-hiding boundary; keep the `Sync` variants off it.
3. Have fragments depend on the interface, obtained from a one-line factory
   (`Repositories.notes(context)`) rather than `new`.
4. Only then consider a `ViewModel` per screen, and only for screens where a config
   change visibly re-loads. Do not do this speculatively.

---

## R7 — `data/` is a grab bag, and package names lie across modules `(a) DONE 2026-08-26, (b) OPEN`

**Cause (a).** `data/` holds 25 files mixing repositories, `AppDatabase`, importers,
crypto, **and eight Wear OS transport classes**: `WearAnswerListenerService`,
`WearAudioCaptureListenerService`, `WearNoteListPublisher`,
`WearNoteListRefreshListenerService`, `WearProjectionPublisher`,
`WearReadControlListenerService`, `WearReadListenerService`, `WearReadStatePublisher`.
Those are transport over the Wear Data Layer, not storage. A third of the package is
about a different device.

**Cause (b).** `:study` and `:app` both contribute classes to `mse.quill.data`,
`mse.quill.ui.quiz` and `mse.quill.ui.flashcards`. This was a deliberate, documented call
when `:study` was extracted (see requirements.md, Epic J) — it kept the extraction diff at
"files changed module, zero imports touched," which was the right trade *then*. The cost
has since accrued: `mse.quill.ui.quiz.QuizGenerator` is pure logic in a package named
`ui`, `mse.quill.data.FlashcardScheduler` is pure logic in a package named for SQLite, and
both names now misdescribe their contents across two Gradle modules.

**Solution.** (a) is cheap and worth doing now. (b) is a large rename with zero behaviour
change — do it only when something else forces the files open.

**Steps for (a).**

1. `git mv` the eight `Wear*` classes from `data/` to a new `data/wear/` package
   (keeping them under `data/` acknowledges that they read the database; a top-level
   `wear/` package in `:app` would collide confusingly with the `:wear` *module*).
2. Update the `package` lines, the imports, **and `AndroidManifest.xml`** — four of the
   eight are `<service>` entries declared by fully-qualified name. A missed manifest
   entry compiles fine and fails silently at runtime, which is the only real risk in this
   item.
3. Verify: install, then confirm the watch still receives a projection.

**Steps for (b), when it earns itself.** `mse.quill.study.scheduling` for
`FlashcardScheduler`/`DueProjection`, `mse.quill.study.review` for `ReviewSession`,
`mse.quill.study.quiz` for the quiz four. One commit, imports only.

**What landed for (a).** The eight classes are in `data/wear/`; `data/` is down from 26
files to 18. All five manifest `<service>` entries were rewritten and verified against the
*merged* manifest, not just the source one.

Two things the plan did not foresee, both consequences of Java giving a subpackage no
package-private access:

- The `*Keys` classes the services speak in (`ReadRequestKeys`, `ReadStateKeys`,
  `NoteListKeys`, `AudioCaptureKeys`) live in **`:study`**, in package `mse.quill.data` —
  R7(b)'s split package, met head-on. They needed explicit imports, which is the first
  concrete cost that split has imposed rather than merely threatened. Worth noting when
  (b) is finally weighed.
- `NoteCrypto` was package-private, and two publishers use it. Rather than making the
  whole class visible, the class and exactly three lock-state queries — `isLocked`,
  `lockedCollectionIds`, `excludeCollectionsClause` — are now public; everything that
  touches a key or a ciphertext stays package-private. Those three answer "which
  collections are shut", not "what does this say", which is what a publisher deciding
  whether a title may go to a watch actually needs. The reasoning is recorded in
  `NoteCrypto`'s class comment.

**Not verified:** step 3's "install, then confirm the watch still receives a projection".
No watch was paired this session. The APK builds and the merged manifest is right, which
covers the silent-failure risk the step exists for, but the Data Layer round trip itself
is unexercised.

---

## R8 — Minor items `OPEN`

Not worth their own section; fix opportunistically when the file is open anyway.

- **`getWritableDatabase()` for reads** — 16 times in `NoteRepository` alone, and in every
  other repository. Should be `getReadableDatabase()` on read paths. In practice SQLite
  hands back the same connection, so this is legibility rather than performance: a reader
  cannot tell a read path from a write path at a glance.
- **`CollectionRepository.isLocked:61`** fires its callback *synchronously, on the calling
  thread* for a null id, and asynchronously on main otherwise. A caller cannot know which,
  which is exactly the kind of thing that produces a re-entrancy bug once. Make it always
  post to `mainThread`. It is the only instance of this in the tree — keep it that way:
  **a callback-taking method must never invoke its callback before it returns.**
- **`MainActivity` (764 lines)** carries intent routing, the app-lock gate, the now-playing
  bar, insets, swipe nav and bottom nav. Most is genuinely Activity-shaped work, but
  `handleReminderIntent` / `handleWidgetIntent` / `handleViewIntent` are an extractable
  `DeepLinkRouter`.
- **`strokeDao` / `textDao` field names** in `WhiteboardFragment` outlived the DAO →
  Repository rename. *(Fixed in R4's pass.)*

---

## What is deliberately not on this list

Recorded so a later sweep does not "discover" them again:

- **`NoteCrypto` in `data/` rather than `security/`.** It is the notes-table side of
  `CollectionCrypto` and its own class comment says so. The split is intentional: `security/`
  holds the primitives, `data/` holds the table that uses them.
- **Zero hardcoded strings across 27 layouts**, against a 711-line `strings.xml`. Already
  right; do not let it slip.
- **Every fragment guards async callbacks** with `isAdded()` / null checks. Already right.
- **`:study` is Android-free and JVM-tested.** The best structural decision in the
  codebase — it is what makes SM-2 shareable with `:wear` instead of reimplemented.
- **`ensureAdditiveSchema` checks for columns rather than trusting version numbers.** Looks
  redundant; is not. Two branches independently shipped a "version 4" meaning different
  things.
