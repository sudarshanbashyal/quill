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

## R1 — `WhiteboardFragment` is a god class `OPEN`

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

---

## R2 — The domain model lives inside the UI package `OPEN`

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

---

## R3 — The data layer calls upward into the widget layer `OPEN`

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

## R5 — Inconsistent repository construction `OPEN`

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

## R7 — `data/` is a grab bag, and package names lie across modules `OPEN`

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
