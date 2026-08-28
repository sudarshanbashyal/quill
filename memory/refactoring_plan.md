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

## R6 — No abstraction boundary between UI and data `steps 1-3 DONE 2026-08-26, step 4 OPEN`

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

**What landed (steps 1–3).** `NoteStore` and `FlashcardStore` hold exactly the methods with
callers outside `data/` — 13 and 7 — and the `Sync` variants are off both, as specified.
`Repositories.notes(context)` / `.flashcards(context)` replaced `new` in eight files; ten
UI holders now name the interface.

Details worth keeping:

- The callback types (`OnNoteLoaded`, `OnPinResult`, `OnNoteSaved`, `QaCandidate`, …) moved
  onto the interfaces, since an interface should own its own vocabulary. Nothing broke at
  the call sites even before they were repointed: **an implementing class inherits an
  interface's nested types**, so `NoteRepository.OnPinResult` still resolves. Useful to
  know; it makes this kind of move far cheaper than it looks.
- Two static helpers, `NoteRepository.newNoteId` and `FlashcardRepository.reviewableQa`,
  are now static methods on the interfaces delegating to the class. Both are genuinely part
  of the vocabulary — you need an id *before* there is a note, and `reviewableQa` is a pure
  function of segments — and leaving them behind would have kept `import
  ...FlashcardRepository` in the editor for one call.
- `CollectionDetailFragment` deliberately keeps the concrete `NoteRepository`, because it
  exports a collection and needs `loadForBundleSync`. Its field carries a comment saying so.
  That is the interface doing its job: holding the concrete type is now a visible signal
  that a screen is doing something screens normally should not.

**Honest limit.** This does not yet buy the JVM test the item is named for. `Repositories`
is a static factory and fragments call it internally, so a fake still cannot be substituted
into a fragment; what exists today is the documented boundary and the `Sync` methods being
off it. Wiring a seam for substitution belongs with whoever actually writes the first such
test, alongside step 4 — doing it now would be exactly the speculation step 4 warns against.

**Step 4 (ViewModels) not started, on purpose.** The plan says to consider it only for
screens where a config change visibly re-loads, and only after 1–3. Nothing was measured;
nothing was done.

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

## R8 — Minor items `DONE 2026-08-26`

Not worth their own section; fix opportunistically when the file is open anyway.

- **`getWritableDatabase()` for reads** — 16 times in `NoteRepository` alone, and in every
  other repository. Should be `getReadableDatabase()` on read paths. In practice SQLite
  hands back the same connection, so this is legibility rather than performance: a reader
  cannot tell a read path from a write path at a glance. `DONE` — 28 sites converted, of
  68 total. Classified per *method*, not per line, and then checked transitively: a method
  qualifies only if neither it nor any helper it hands the handle to writes. That check
  earned its keep. `CollectionLockRepository.lock`/`unlock` and three `QuizRepository`
  loaders contain no write call of their own and would have been converted by the obvious
  regex — they delegate to `writeNotes`, `relinkWhiteboards`,
  `NoteRepository.convertNoteMediaSync` and `abandonStaleSync`, which do `execSQL`.
- **`CollectionRepository.isLocked:61`** fires its callback *synchronously, on the calling
  thread* for a null id, and asynchronously on main otherwise. A caller cannot know which,
  which is exactly the kind of thing that produces a re-entrancy bug once. Make it always
  post to `mainThread`. It is the only instance of this in the tree — keep it that way:
  **a callback-taking method must never invoke its callback before it returns.** `DONE` —
  the rule is now written into the method's own comment, where the next person to edit it
  will see it. Both callers only set a field, so nothing depended on the synchronous form.
- **`MainActivity` (764 lines, 833 by the time this was done)** carries intent routing, the
  app-lock gate, the now-playing bar, insets, swipe nav and bottom nav. Most is genuinely
  Activity-shaped work, but `handleReminderIntent` / `handleWidgetIntent` /
  `handleViewIntent` are an extractable `DeepLinkRouter`. `DONE` — 833 → 586 lines, router
  335. It took more than the three handlers: they are inseparable from `pendingImportUri`,
  `viewIntentConsumed`, `pendingJoinToken`, the saved-instance-state for all three,
  `runWhenNavHostReady`, `joinSessionWhenUnlocked` and the two Home-delivery helpers. That
  state is exactly why it wanted to be a class rather than three static methods. The six
  `EXTRA_OPEN_*` constants moved with it — they are the protocol the router speaks — and
  seven references in `widget/` and `reminders/` were repointed. The activity now drives it
  through five calls: `onCreate`, `route`, `onNewIntent`, `onSaveInstanceState`,
  `onUnlocked`.
- **`strokeDao` / `textDao` field names** in `WhiteboardFragment` outlived the DAO →
  Repository rename. *(Fixed in R4's pass.)*

*(The 40 `getWritableDatabase()` calls on genuine write paths are left as they are, which is
correct — they write.)*

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

---

# Second sweep — 2026-08-26

Written after R1–R8 landed, from a fresh read of `app/src/main/java/mse/quill` (36.9k LOC).
Same format: **cause → solution → steps**, ordered by leverage. `OPEN` unless marked.

The first sweep was about *layering* — who may import whom. This one is mostly about
**duplication**: the same flow written two or three times, drifting apart. Two of the
copies have already drifted into real defects (R10, R12), which is what makes this round
worth doing rather than tidying.

---

## R9 — Three entry points to "join a collaboration session", each with its own copy `DONE 2026-08-28`

**Cause.** Joining a session can start from three places, and all three implement it
separately:

| From | Code | Creates a board row first? | Permission ladder | Scanner errors |
|---|---|---|---|---|
| The whiteboard's Collaborate button | `WhiteboardCollabController.startJoinByScan` | no — reuses this board | `Host.requestCollabPermissions` | `showError`, offers "scan again" |
| Home's FAB | `HomeFragment.requestJoinPermissions` + `scanAndJoin` | **yes** — `createWhiteboard(null, …)` | its own `joinPermissionLauncher` | its own dialog, no retry |
| A `quill://` link | `DeepLinkRouter.joinSessionWhenUnlocked` | no — "the screen mints one" | none at all | none |

`HomeFragment.scanAndJoin` is a near-verbatim copy of `WhiteboardCollabController.scanForSession`
down to the two string resources, and its error dialog is `showCollabError` minus the retry
button. The three also disagree about who creates the `whiteboards` row: Home creates one
and passes its id; the deep link passes no id and lets the fragment mint one. Both work,
which is worse than one of them being wrong — nobody will notice they diverged.

**Solution.** One `CollabEntry` that owns "get permission, scan, hand back a token", used
by all three. It does not own what happens next — that genuinely differs — so it returns
the token and stops.

**Steps.**

1. `collab/CollabEntry.java`: `static void scanForToken(Fragment, OnToken)`, wrapping the
   `CollabPermissions.missing` ladder and `SessionScanner`, and raising the one error
   dialog. The permission launcher has to be registered by the fragment, so it takes a
   `Host` the way `WhiteboardCollabController` does — or simply takes the already-resolved
   `Runnable` and leaves the ladder to the caller. Prefer the latter; the ladder is three
   lines and the launcher genuinely belongs to the fragment.
2. Point all three at it. `WhiteboardCollabController.scanForSession` and
   `HomeFragment.scanAndJoin` both go.
3. **Decide the board-row question explicitly** and write the answer down: does a joiner
   arrive on a board Home created, or one the fragment mints? Pick one and make the deep
   link and Home agree. This is the part with actual value — the extraction is the excuse.

**What landed.** `collab/CollabEntry` owns the ladder, the scan and the error dialog, and
stops at the token — what happens next genuinely differs, so handing one back and getting out
of the way is the whole contract. Built the way `export.StoragePermission` is, and for the
same reason: it registers a launcher, so it has to be constructed before STARTED.

`SessionScanner` now has exactly one caller in the tree.

**The board-row question, answered: the fragment mints it.** Home used to create the row,
wait for the callback, then navigate with its id; the `quill://` path passed no id and let the
screen mint one. Same destination, and the fragment's minting path has to exist regardless —
so Home's is the one that goes. That removes an async hop before a navigation and Home's
reason to know about whiteboard background preferences.

**One behaviour change, deliberate.** Home's scan-failure dialog now offers "scan again",
which the whiteboard's always had and Home's never did. That was not a decision anyone made —
it is one copy that never got the improvement, which is the shape this kind of duplication
rots into. Unifying them means picking the better one.

`HomeFragment` also lost `joinPermissionLauncher` and `requestJoinPermissions`;
`WhiteboardFragment` lost `collabPermissionLauncher`, `pendingCollabAction` and
`requestCollabPermissions`, and `Host` lost that method from its interface.

---

## R10 — Export and share are one feature split across two packages, three fragments, and a bug `DONE 2026-08-26`

**Cause.** Writing something out of Quill is spread over:

- `util/`: `PdfExporter` (393), `MarkdownExporter` (81), `ImageExporter` (89),
  `NoteExportStore` (168) — 731 lines;
- `share/`: nine files, 1140 lines, the `.quill`/`.quillboard`/`.quillpack` bundle formats;
- and the fragments, where `NoteEditorFragment.writeExport` calls `util.PdfExporter`,
  `util.MarkdownExporter`, `util.NoteExportStore` **and** `share.QuillBundle` in one method.

The `ACTION_SEND` + `FileProvider` + `createChooser` block is written out three times:
`NoteEditorFragment:701`, `CollectionDetailFragment:267`, `WhiteboardFragment:783` (that
one with `android.content.Intent` fully qualified inline).

**And the third copy has drifted into a real defect.** `WhiteboardFragment.exportWhiteboard`
(`:799`) reimplements `ImageExporter.saveToPictures` and gets three things wrong that
`ImageExporter` gets right:

- it sets `MediaStore.Images.Media.RELATIVE_PATH` **unconditionally**, and that column is
  API 29+. `minSdk` is 26.
- it never checks `WRITE_EXTERNAL_STORAGE`, which API 26–28 requires — the manifest
  declares it with `maxSdkVersion="28"` *for exactly this*, and `NoteEditorFragment` gates
  on `ImageExporter.requiresStoragePermission()` before its own save.
- it omits `IS_PENDING`, so a gallery scanning mid-write can show a half-written PNG.
  `ImageExporter` sets it and says why.

So whiteboard PNG export is very likely broken on API 26–28 and racy everywhere.

**Solution.** One `export/` package holding both halves, and one way to hand a file to
another app.

**Steps.**

1. `git mv` the four exporters from `util/` into `share/`, and rename the package
   `export/` — it covers bundles *and* PDF/Markdown/PNG, and "share" is only one of the
   things it does. One commit, imports only.
2. Add `ShareIntents.sendFile(Fragment, Uri, String mime, int chooserTitleRes)` and replace
   the three `ACTION_SEND` blocks.
3. **Delete `WhiteboardFragment.exportWhiteboard`'s MediaStore code and call
   `ImageExporter`**, extending it to take a `Bitmap` and a PNG mime type rather than only
   copying an existing file. This is the fix, not the tidy-up — verify on an API 26–28
   emulator, which is the only way to see it.
4. While there: the four hardcoded English strings in that method
   (`"Export failed"` ×2, `"Saved to Pictures/Quill/" + filename`) go to `strings.xml`.

**What landed — and step 1 was wrong.** The plan said to rename `share/` to `export/` and
move the four exporters in. Opening `share/` showed why that would have been a mistake: all
nine files are the `.quill`/`.quillboard`/`.quillpack` **format**, and three of them are
*readers*, which serve import, not export. Calling that package `export` would have been a
worse lie than `share`.

So it split three ways instead, by what each thing is:

- **`bundle/`** (renamed from `share/`) — the file format, read and write. Used by export
  *and* by `data/`'s three importers.
- **`export/`** (new) — `PdfExporter`, `MarkdownExporter`, `ImageExporter`,
  `NoteExportStore`, moved out of `util/`, plus `ShareIntents`. Producing a file and getting
  it to the user.
- **`data/*Importer`** — unchanged. Writing a parsed bundle into the database.

`NoteEditorFragment.writeExport` still touches `export.PdfExporter` and
`bundle.QuillBundle` in one method, and that is now correct rather than a smell: exporting a
`.quill` *is* the export path using the bundle format.

`ShareIntents.sendFile`/`view` replaced the three `ACTION_SEND` copies and the one
`ACTION_VIEW`. It takes a `Context` and returns whether a chooser opened rather than being a
Fragment helper, so each screen keeps reporting failure the way it already does — a Snackbar
in the note editor, a Toast in the other two.

Steps 3 and 4 (the API 26–28 bug and the hardcoded strings) landed first, in their own
commit — they were defects, not tidying, and did not deserve to wait behind a package move.

---

## R11 — `NoteEditorFragment` is the god class now `step 2 DONE 2026-08-26, steps 3-4 OPEN`

**Cause.** 1298 lines, ~60 methods — bigger than `WhiteboardFragment` was before R1, and
carrying at least seven unrelated jobs: export (14 methods, `:511–750`, 239 lines),
read-aloud and voice selection, audio recording UI, whiteboard attachment, autosave and
lock-retry, keyboard/inset choreography, and tags.

Symptoms of the sprawl are already visible in the file: `private WhiteboardRepository
whiteboardRepository` is declared at `:441`, in the middle of the methods, and there are
two `autoSave` overloads 20 lines apart (`:1185`, `:1206`).

**Solution.** The same seam R1 used. Export is the obvious first cut — it is the largest
block, it is self-contained, and R10 is moving its collaborators anyway.

**Steps.**

1. Do **R10 first**. Extracting export from the fragment while its dependencies are still
   split across `util/` and `share/` just moves the mess.
2. `NoteExportController` (or `ui/notes/NoteExportFlow`) taking the 14 export methods and
   the export dialog state. Target: fragment under 1000.
3. Read-aloud is the natural second cut (`toggleReadAloud`, `buildReadPlaylist`,
   `stopReadingIfNothingLeft`, `showVoicePickerDialog`, `describeVoice`), and it pairs with
   R16's note about `ReadAloud`'s static state.
4. Stop there. Keyboard choreography and autosave are genuinely editor-shaped work.

**What landed (step 2).** `NoteExportController` (330 lines) took all of it: the format menu,
the three writers, the completion dialog and its animation, open/share, and the
one-picture-to-the-gallery path. Fragment 1298 → **1062**, short of the "under 1000" target
but the line count is the weaker measure here. The stronger one: the fragment dropped
**thirteen** imports, among them every `export.*` and every `bundle.*` one, and now contains
zero references to `PdfExporter`, `MarkdownExporter`, `QuillBundle`, `BundleWriter`,
`NoteExportStore`, `ShareIntents` or `ImageExporter`. It no longer knows file formats exist.

`Host` is all pull, never push — `segmentsForExport`, `titleForExport`, `tagsForExport`,
`createdAtForExport`, `isCollectionLocked`, `requestStoragePermission`. The controller asks
at the moment of export, so there is no second copy of the note's state here to go stale,
which is the same reason the original read segments on the main thread.

Two small things improved on the way, neither planned:

- The permission callback now carries **both** outcomes rather than one action plus a
  hardcoded `abandonExport()` on refusal. Exporting a note and saving a picture want
  different things when refused, and the old shape only expressed one of them — it worked
  because the note-export path left the pending result null, which is an accident rather
  than a design.
- `exportMedia`'s pending path and result were **fragment fields**, so two picture exports
  requested before the permission resolved would clobber each other. They are closure
  captures now, and independent.

**Steps 3 and 4 are untouched.** Read-aloud is still in the fragment; it pairs with R16's
note on `ReadAloud`'s nine mutable statics and should be done with it, not before.

---

## R12 — `DataWipe`'s hand-maintained list of preference files has already rotted `DONE 2026-08-26`

**Cause.** There are six `SharedPreferences` files:

`home_prefs`, `whiteboard_prefs`, `profile_prefs`, `note_reader_prefs`, `security_prefs`,
`onboarding_prefs`.

`DataWipe.wipeEverything` clears **four** of them — profile, app lock, whiteboard,
onboarding. `home_prefs` and `note_reader_prefs` survive "delete everything".

`home_prefs` holds `pinned_count`, which Home reads on cold start to draw *placeholder*
pinned cards before the real query returns (`showPinnedPlaceholders`). So after a wipe,
Home briefly renders N ghost cards for notes that no longer exist.

The irony is that `DataWipe`'s own comment, two lines above, explains why it clears
`filesDir` wholesale rather than by name: *"doesn't need a list of subdirectory names that
would quietly rot as features are added."* It then keeps exactly such a list for prefs, and
the list has rotted.

**Solution.** Stop maintaining the list. Either enumerate `shared_prefs/` on disk, or make
registration the only way to get a prefs file.

**Steps.**

1. Prefer enumeration: `new File(appContext.getApplicationInfo().dataDir, "shared_prefs")`,
   clear every `*.xml`. It cannot rot, and it matches what the directory deletion above it
   already does.
2. If enumeration feels too broad, the alternative is `Preferences.named("home")` as the
   single factory, with `DataWipe` iterating what it handed out — but that only works if
   nothing calls `getSharedPreferences` directly, which needs a lint rule to hold.
3. Either way, add `home_prefs` and `note_reader_prefs` to the wipe **first**, as a
   one-line fix, before doing the structural part. The bug is worth closing on its own.
4. Decide deliberately whether the TTS voice preference is "data" — an argument exists for
   keeping a device-capability setting across a wipe. Write the answer down; do not leave
   it as an accident.

**What landed.** Step 1, enumeration, straight away — the one-line fix in step 3 would have
left the rot in place, and the point of the item is that the list cannot be trusted. Names
come off `shared_prefs/`, but each file is emptied through the `SharedPreferences` API
rather than deleted: the framework caches a live instance per name, and an instance still
held in-process would write its in-memory copy back over a deleted file.

The payoff was bigger than the bug. `WhiteboardPreferences.prefsName()`,
`ProfilePreferences.prefsName()`, `AppLock.prefsName()` and `Onboarding.prefsName()`
existed **only** so `DataWipe` could name their files — every one of their doc comments said
so — and all four are now gone. Four classes stopped publishing their storage filename to
the whole app.

On step 4: the TTS voice preference is wiped, along with everything else. It is a choice the
user made, not a device capability — the engine's voice list is re-read on every launch —
so "delete everything" should take it. The sweep also picks up libraries' preference files,
WorkManager's included; that is intended and noted in the method, since the pending reminder
is cancelled by this wipe anyway (the profile preference that arms it is one of the files
cleared) and `StudyReminders.sync` re-arms from scratch on the next launch.

---

## R13 — `:study` is two modules wearing one name `OPEN`

**Cause.** `:study` is the only module both `:app` and `:wear` depend on, so everything
shared has been put there whether or not it is about studying. Of its 16 files, **seven are
the phone↔watch wire protocol**: `AnswerEventKeys`, `AudioCaptureKeys`, `DueProjectionKeys`,
`NoteListKeys`, `ReadControlKeys`, `ReadRequestKeys`, `ReadStateKeys` — all in package
`mse.quill.data`, in a module called `study`, describing neither storage nor studying.

R7a ran straight into this: moving `:app`'s `Wear*` services into `data/wear/` cost them
package-private access to those Keys and needed explicit imports. That was the first real
bill this arrangement has presented.

Audio capture and read-aloud control have nothing to do with SM-2.

**Solution.** Split the shared surface by what it is, not by who needs it. Either a small
`:wearprotocol` module for the seven Keys classes, or rename `:study` to `:shared` and give
it honest packages inside.

**Steps.**

1. Cheapest honest move first, and it is only a package rename inside `:study`: the seven
   Keys classes go from `mse.quill.data` to `mse.quill.sync`. That alone stops the module
   claiming they are storage, and it is imports-only.
2. Then decide on the module. A `:wearprotocol` module is cleaner and makes `:wear`'s
   dependency on `:study` optional; renaming `:study` to `:shared` is one line in
   `settings.gradle.kts` plus two `implementation(project(...))` lines. Prefer the rename —
   a third module for seven constant-holder files is not obviously worth its build cost.
3. R7b's original items (`mse.quill.ui.quiz.QuizGenerator` and
   `mse.quill.data.FlashcardScheduler` being pure logic in packages named `ui` and `data`)
   fold into step 2 — do them in the same commit, since it is the same rename.

---

## R14 — `util/` is four packages in a trench coat, and `dimen()` is declared seven times `(b) DONE 2026-08-27, (a) partly done`

**Cause (a).** `util/` holds 19 files, 1901 lines, in four unrelated groups:

- **export** — `PdfExporter`, `MarkdownExporter`, `ImageExporter`, `NoteExportStore` (R10);
- **UI behaviour** — `SwipeToDelete`, `Reveal`, `UndoDelete`, `MaxHeightScrollView`,
  `WindowInsetsUtils`, `Haptics`, `CardStyles`, `TextFieldUtils`. These are not utilities;
  two hold **static mutable UI state** (`WindowInsetsUtils.chromeOwnsTopInset`,
  `SwipeToDelete.activeSwipes`), which is a thing a package called `util` should never own;
- **a data operation** — `DataWipe`, which destroys the database and every file the app owns;
- **genuine helpers** — `BitmapUtils`, `ColorUtils`, `RelativeTime`, `TimeStamps`,
  `NoteDisplayUtils`, plus the `PipAware` interface.

**Cause (b), the picky one.** `dimen(Context, int)` — a two-line wrapper over
`getDimensionPixelSize` — is declared **seven times**:

- `util/CardStyles.dimen` — public, the real one;
- `ui/home/NoteRowView.dimen` — a pass-through that just calls `CardStyles.dimen`, and the
  one four classes in `ui/home` actually use, so they reach *through a View class* to get to
  a utility;
- verbatim private copies in `PinnedNoteCardView`, `WhiteboardPickerDialog`, `TagChipView`,
  `SearchFilterDialog`, `NoteQaPickerDialog`.

**Solution.** Move by group; delete the six duplicate `dimen`s.

**Steps.**

1. `dimen` first — it is ten minutes. Everything calls `CardStyles.dimen`; the delegate in
   `NoteRowView` and the five copies go.
2. `DataWipe` → `data/`. It is a data-layer operation and `data/` is where someone looks
   for it.
3. UI behaviour → `ui/common/` (or `ui/behaviour/`). `CardStyles` goes with them.
4. Export → R10's `export/` package.
5. What is left in `util/` is five genuine helpers and one interface, which is a `util/`
   worth having.

**What landed.** (b) in full: one `dimen`, in `CardStyles`, called by name everywhere. The
five verbatim copies are gone, and so is the pass-through in `NoteRowView` — with it, the
three `ui/home` classes that were reaching through a *View class* to get at a utility now
name the utility.

(a) is partly done by accident: R10 took the four exporters into `export/`, so `util/` is 19
files down to **15**. Still three groups — UI behaviour (`SwipeToDelete`, `Reveal`,
`UndoDelete`, `MaxHeightScrollView`, `WindowInsetsUtils`, `Haptics`, `CardStyles`,
`TextFieldUtils`), one data operation (`DataWipe`), and the genuine helpers. Steps 2 and 3
stand.

---

## R15 — `AppDatabase` is a schema, a migration history, and a helper class in one file `DONE 2026-08-28`

**Cause.** 748 lines: the singleton and its lifecycle, `onCreate` with ~170 lines of
`execSQL` defining every table, and ~400 lines of migration — `onUpgrade`, `onDowngrade`,
`migrateLegacyNotesToMarkdown`, `reshapeLegacySegmentTable`, `ensureAdditiveSchema`,
`ensureNotesFts`, `backfillNotesFts`, `backfillWhiteboardLinks`.

The migration half only grows, and it is the half nobody should edit casually. Sitting in
the same file as the schema makes "what does a fresh install create?" and "what happened to
installs from March?" the same question to read.

**Solution.** Three files, no behaviour change: `AppDatabase` (singleton + `onCreate`
delegating out), `Schema` (the `CREATE TABLE` statements), `Migrations` (everything
`onUpgrade` reaches).

**Steps.**

1. `data/Schema.java` — package-private, `static void createAll(SQLiteDatabase)`. Pure move.
2. `data/Migrations.java` — the seven migration methods, package-private statics.
3. `AppDatabase` keeps `getInstance`/`openForTest`/`destroy`/`onConfigure`/`hasAnyContentSync`
   and two delegating overrides. Target: under 150 lines.
4. Do **not** renumber versions or touch `ensureAdditiveSchema`'s column-checking — see
   "deliberately not on this list" in the first sweep.

**What landed.** `AppDatabase` 748 → **133** lines: the singleton, `openForTest`, `destroy`,
`hasAnyContentSync`, `onConfigure`, and four delegating lines. `Schema` is 271, `Migrations`
387. Ten dead imports went with it — the helper no longer imports `NoteDocument` or any
segment type, because it no longer converts anything.

One join needed a decision rather than a cut: `ensureNotesFts` **creates** the FTS5 table
(schema) and then **fills it from existing rows** (migration). Split along that line —
`Schema.ensureNotesFts` creates, then calls `Migrations.backfillNotesFts`. On a database
being created from nothing the backfill finds no rows, which is the same no-op by a
different route, and that is now said where someone will read it.

Step 4 honoured: no version renumbering, and `ensureAdditiveSchema` moved character for
character. The reason it checks columns rather than trusting version numbers is now in
`Migrations`' class comment rather than only in this plan.

---

## R17 — The whiteboard exports inline while the note has a controller `DONE 2026-08-27`

**Cause.** Created by this sweep's own work, and worth recording as such. R11 pulled export out
of `NoteEditorFragment` into `NoteExportController`; `WhiteboardFragment` still does the same
job inline — `showExportMenu`, `shareWhiteboard`, `exportWhiteboard`,
`needsStoragePermissionFor` (`:744–858`, 114 lines) plus a `storagePermissionLauncher` and its
`pendingStorageAction` field. Two screens, one job, two shapes.

It also made `WhiteboardFragment` the largest file in the tree again at **1146 lines** — larger
than the 1124 it came out of R1 at, because R10's permission-ladder fix added to it. R1's own
closing note said export and share were "the next extractable seam if this file is opened
again". It has been opened twice since.

**Solution.** `WhiteboardExportController`, mirroring `NoteExportController` — or, better,
notice that the two now differ only in *what* they write and generalise one controller over a
`Host` that supplies the bytes. Check that before writing a second class: the completion
dialog, the open/share split and the permission ladder are the bulk of `NoteExportController`,
and none of it is note-specific.

**Steps.**

1. Compare the two flows honestly first. If the shared part is as large as it looks, lift
   `NoteExportController` to `ui/common/` (R14's package) with a `Host` that returns a
   `NoteExportStore.Saved`, and let each screen supply its own formats and menu.
2. If they genuinely differ, a second controller is still better than the status quo — but say
   in both class comments why there are two.
3. Either way the whiteboard gains the export-complete dialog it currently lacks, which is a
   real inconsistency the user can see: exporting a note confirms and offers to open it;
   exporting a board raises a Toast and leaves you to find the file.

**What landed — and step 1's answer was "two, not one".** Comparing them honestly, as the
step asks, the shared part is smaller than it looks. What genuinely overlaps is the
permission ladder and the share-sheet call; the formats and where the content comes from do
not. A note's export reads segments off the editor and writes PDF, Markdown or a bundle to
Downloads; a board's reads three tables, renders a bitmap, and writes a PNG to Pictures or a
bundle to Downloads. Generalising over that would have been a `Host` with a method per
difference, which is a worse class than two honest ones.

So: `WhiteboardExportController` (177 lines) mirrors `NoteExportController`, and the one
piece of real duplication came out as `export/StoragePermission` (71 lines).

**`StoragePermission` is the part worth keeping in mind.** The ladder was copied verbatim
into both fragments — each with a launcher field, a pending-action field and the same fifteen
lines — because `registerForActivityResult` must be called before STARTED and so cannot be
done lazily where it is needed. Constructing the helper in a field initialiser registers the
launcher at the right moment and keeps all three together, which is what made a class work
where a static helper could not. Only API 26–28 ever climbs it, which is exactly why
forgetting it is easy and why the whiteboard shipped without it (R10).

`WhiteboardFragment` 1146 → **1042**, `NoteEditorFragment` 1062 → **1025**, and neither now
references any exporter, bundle format, `NoteExportStore` or `ShareIntents`.

**Step 3 deliberately not taken.** Giving the board an export-complete dialog is a product
decision, not a refactor — and for *share* specifically, going straight to the sheet is
arguably the better flow, not an oversight. The asymmetry is real and stays recorded here;
closing it should be someone's deliberate choice, not a side effect of moving code.

---

## R16 — Small things `OPEN`

Fix opportunistically; none is worth its own trip.

- **33 fully-qualified `mse.quill.*` references inline**, plus **52 fully-qualified
  `android.content/provider/net/os/graphics/widget/view` ones**, instead of imports. R3
  called this "the tell that it was bolted on rather than designed" and it is still the best
  single smell detector in the tree — `WhiteboardFragment:783` writes
  `android.content.Intent` three times in one statement. Worth a one-pass cleanup with a
  formatter rather than by hand.
- **Seven hardcoded user-facing strings in Java**, six of them in `WhiteboardFragment`
  (`"Clear Whiteboard"`, `"This will erase everything on this whiteboard. Continue?"`,
  `"Clear"`, `"Cancel"`, `"Export failed"` ×2) and one in `NoteEditorFragment`
  (`"Insert image"`). `note.md` claims zero hardcoded strings — true of the 27 layouts, not
  of the Java.
- **19 unused resources** per lint, including `ic_stop.png`, `circle_indicator.xml`,
  `R.color.black`, and six unused strings. Delete them; they are dead weight in a project
  that advertises a minimal footprint.
- **Static mutable state has no convention.** Five things outlive a fragment and each
  invented its own shape: `CollabSessionHolder` (documented, attach/detach, the good one),
  `ReadAloud` (**nine** mutable statics — reader, clipReader, appContext, noteId, title,
  index, weightBefore, active, paused), `AppLock`, `CollectionLock`,
  `WindowInsetsUtils.chromeOwnsTopInset`. `ReadAloud` is the one to fix: it is a playback
  *session* modelled as process globals, and it pairs with R11 step 3.
- **PIP coupling is asymmetric.** The activity reaches the fragment through the `PipAware`
  interface; the fragment reaches back with `((mse.quill.MainActivity) requireActivity())
  .enterWhiteboardPip(w, h)` (`WhiteboardFragment:851`) — a concrete cast, fully qualified
  inline. Give it the other half of the interface.
- **`recyclerView.setVisibility(empty ? GONE : VISIBLE); emptyView.setVisibility(empty ?
  VISIBLE : GONE)`** appears verbatim in `FlashcardDecksFragment`, `QuizzesFragment`,
  `QuizDetailFragment` and `CollectionDetailFragment`. One `EmptyState.apply(recycler,
  empty, isEmpty)` helper, in `ui/common/` from R14.
- **`WhiteboardThumbnails` sits in `ui/whiteboard/`** but reads the database, writes
  `widget/WidgetThumbnailCache`, and calls `WidgetUpdater` — it is neither a screen nor a
  view. After R3 it is also the only place outside `data/` still pushing widget updates by
  hand.

---

## Not on this list, deliberately

- **Silent `catch` blocks.** There are five, every one named `ignored` and every one
  carrying a comment saying why. That is the correct pattern, not a finding.
- **Fragment↔fragment coupling.** There is none: no `getParentFragment`, no
  `findFragmentByTag`, one cast to `MainActivity` (see R16). Navigation goes through the
  nav graph everywhere. Leave it alone.
- **Six RecyclerView adapters with no shared base class.** Checked; they genuinely differ
  (multi-view-type sections vs. flat lists). A common base would be inheritance for its own
  sake.
