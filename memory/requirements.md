# Quill — Requirements & Delivery Checklist

Derived from `one-pager.pdf` (project vision) plus a code-level audit of what's already
implemented vs. what the schema/comments anticipate but nothing builds yet (see
[note.md](note.md) for the architecture detail behind these gaps). Requirements below
mix **feature** work and **architecture/quality** work — the one-pager treats "secure
and efficient" and platform-capability depth as first-class goals, not an afterthought.
[conversation.md](conversation.md)
Each epic states *why it's sequenced where it is* — read that before reordering.
Checkboxes are for tracking; nesting = task → subtask.

## How to read the priority tiers

| Tier | Meaning |
|------|---------|
| **P0** | Do first. Everything after this is riskier or messier to build without it. |
| **P1** | Core product differentiators per the one-pager, or a hard dependency for a P1/P2 item. |
| **P2** | High product value, but independent — safe to parallelize across contributors. |
| **P3** | Depends on a P1/P2 epic being done first, or needs a product decision before it can be scoped. |

Suggested sequencing for a 2-person team:

1. **Both**: Epic A (foundations) — small, shared, de-risks everything below.
2. **Split**: one person on Epic B+C (security → P2P, sequential — C should follow B
   since sharing a locked collection needs B's crypto to exist first), the other on
   Epic D+F (flashcards, search/trash — independent of P2P work).
3. **Converge**: Epic E (quizzes) once D is stable; Epic G opportunistically/last.

---

## Epic A — Engineering Foundations & Data Safety (P0)

**Why first**: the app is about to grow encryption, P2P sync, and background jobs on
top of it. All three are hard to debug on an SQLite layer with a destructive
`onUpgrade`, two different threading patterns, and no automated tests. Fixing this now
costs little (small codebase); fixing it after Epic C lands costs a lot (sync bugs
masquerading as migration bugs).

- [ ] **Safe schema migrations** — *more urgent since 2026-07-28: the schema moved to v3
      for the Markdown migration and the destructive `onUpgrade` duly wiped every
      existing note on next launch. That was accepted as dev-stage policy at the time,
      but it will not be acceptable once anyone else installs this.*
  - [ ] Replace `AppDatabase.onUpgrade`'s drop-and-recreate with real `ALTER TABLE` /
        versioned migration steps — **v3 → v4 done 2026-08-03** (whiteboards gained
        title/created_at/updated_at; additive, so it migrates in place and keeps user data).
        Every other version step still takes the destructive branch
  - [ ] Add a migration test: seed an older-shaped DB with data, run the upgrade, assert
        rows survive
- [ ] **Unify background DB access**
  - [ ] Port `StrokeRepository` / `WhiteboardRepository`'s Sync methods / `WhiteboardFragment`
        off ad hoc `new Thread(...)` calls onto the shared `AppExecutors.diskIO()` used by the
        other repositories. *(2026-08-07: the DAOs were renamed to repositories and
        `WhiteboardDao` folded into `WhiteboardRepository`, with its synchronous methods carrying a
        `Sync` suffix so a UI-thread call reads as wrong. The naming is consistent now; the
        threading is not.)*
  - [ ] Audit all fragments for any accidental main-thread `SQLiteDatabase` access
- [ ] **Automated test coverage** *(70 instrumented tests under `app/src/androidTest` as of
      2026-07-29, run with `./gradlew :app:connectedDebugAndroidTest`; plus 30 JVM unit
      tests under `app/src/test` as of 2026-08-01, run with
      `./gradlew :app:testDebugUnitTest` — the study logic is deliberately Android-free so
      it can be tested without a device)*
  - [x] Markdown round-trip (`MarkdownSerializerTest`): headings, bullets, blank-line
        runs, overlapping spans, and escaping of text that looks like syntax
  - [x] Document ↔ segments (`NoteDocumentTest`): embed ordering, asset rejoining,
        missing-asset handling, preview/plain-text projections
  - [x] Repository round-trip (`NoteRepositoryMarkdownTest`): save/reload with embeds,
        stable asset ids, orphaned-media cleanup, preview
  - [x] Editor formatting (`TextSegmentViewFormattingTest`): active formatting carried
        across a newline, heading styling vs. user styling
  - [x] Image orientation (`BitmapUtilsTest`): EXIF rotation per tag, bounding, no
        needless re-encode
  - [x] Q&A round-trip (`NoteDocumentTest`): fenced block, multi-line halves, formatting,
        scaffolding-lookalike escaping, unterminated fence
  - [x] Q&A field capabilities (`QaFieldCapabilitiesTest`): headings refused for real,
        inline formatting and bullets still available
  - [x] Review session and SM-2 (`ReviewSessionTest`, `FlashcardSchedulerTest`) — JVM
  - [x] Quiz generation (`QuizGeneratorTest`): one question per block, the floor at
        `MIN_QA_BLOCKS`, distractors only ever other blocks' answers, no repeated option,
        the correct answer not always in the same slot, shuffled question order — JVM
  - [x] Quiz scoring (`QuizSessionTest`): one pass with no requeue, a timeout counted as
        wrong rather than skipped, an abandoned run scoring what it reached — JVM
  - [ ] Collection/Tag repository CRUD — still uncovered
  - [ ] `NoteEditorView` segment split/merge/delete (image/audio insertion mid-text,
        backspace-merge at segment boundary) — still uncovered
  - [ ] Wire up CI (e.g. GitHub Actions) to run tests + lint on every PR
- [ ] **Fix bugs found during architecture review**
  - [x] `WhiteboardFragment.exportWhiteboard()` shows a "Export failed" toast
        unconditionally even after a successful export *(fixed 2026-08-03)*
  - [x] Opening a note or whiteboard and leaving it reported "Updated now" — both save-on-pause
        paths wrote `updated_at` even when nothing changed *(fixed 2026-08-07)*

---

## Epic B — Security: Biometric-Locked, Encrypted Collections (P1)

**Why here**: `collections.biometric_locked` is already a column that the repository
reads and writes — but nothing in the app enforces it (verified: no `BiometricPrompt`
usage anywhere in the codebase today). The one-pager specifically says "biometric
**cryptography**," not just a lock screen, so this is a real encryption epic. It must
land before Epic C lets a locked collection be shared to another device — otherwise
there's a window where "locked" content leaves the device unencrypted.

- [ ] **Lock/unlock UX**
  - [ ] "Lock this collection" toggle in collection management UI
  - [ ] `BiometricPrompt` gate before a locked collection's notes are opened/decrypted
  - [ ] Non-biometric device fallback (device PIN/pattern via
        `BiometricManager.Authenticators.DEVICE_CREDENTIAL`)
- [ ] **Actual cryptographic protection, not just an access gate**
  - [ ] Per-collection key in Android Keystore, generated with
        `setUserAuthenticationRequired(true)`
  - [ ] Encrypt locked collections' note content (`notes.title`, `notes.content_blob`, and
        the media files referenced by `note_segments.file_path`) at rest; decrypt only
        after biometric auth. Note this changed shape with the Markdown migration — the
        body is now one blob per note, not per-segment `text_content` rows
  - [ ] Handle key invalidation gracefully (e.g. user re-enrolls a fingerprint) instead
        of silently locking the user out of their own data
- [ ] **Migration cases**
  - [ ] Locking a previously-unlocked collection: encrypt existing notes in place
  - [ ] Unlocking: decrypt and re-store in plaintext, with explicit user confirmation

---

## Epic C — Offline Sharing & Whiteboard Collaboration (P1)

**Why here**: the one-pager calls this "central" to the app and the biggest reason to
be offline-first. Re-scoped on 2026-08-06 after a design review (see
[note.md](note.md) → "Sharing and collaboration"); the original NFC + Wi-Fi Direct
plan is superseded. Three decisions drive everything below:

1. **Notes are shared, not co-edited.** Live note collaboration is dropped. A shared
   note is a *copy* with a new id, which removes the conflict domain entirely — and
   with it the vector-clock/outbox machinery for notes. This also retires the cost
   accepted in the Markdown storage decision, which was a coarser merge domain.
2. **Whiteboards are collaborated on**, because strokes are append-only, immutable and
   already carry `author_id`. The merge is "dedupe by id" — no vector clocks.
3. **The transport is Nearby Connections, not hand-rolled Wi-Fi Direct.** It is still
   pure peer-to-peer and fully offline; it picks BLE/Bluetooth/Wi-Fi Direct/hotspot
   itself and supplies discovery, encryption, framed payloads and reconnect — the two
   layers the old plan was going to write by hand.

Sequence so that each step is testable on its own, and note that **the P2P steps need
two physical devices** — none of it runs on the emulator.

- [ ] **Note sharing (no session, no transport of our own)**
  - [ ] `.quill` bundle: a zip of `note.md` + `media/` + manifest. Lossless, unlike the
        Markdown export, which reduces images/audio to placeholders. A note with no
        media may ship as a bare `.md` so it still opens in any text editor.
  - [ ] Share via `ACTION_SEND` + FileProvider → the system sheet (Quick Share,
        Bluetooth, mail). No integration work: Quick Share is a share *target*, not an API.
  - [ ] **Import**: `ACTION_OPEN_DOCUMENT` → picker → unpack. Build this first; it is
        the only receive path that works across every transport.
  - [ ] Import semantics: mint a new note id, re-id media into private storage and
        rewrite `quill://` URIs, match tags by name (create if missing).
  - [ ] *Polish, expect flakiness*: `ACTION_VIEW`/`ACTION_SEND` intent filter so a
        received file opens straight into Quill. Files arriving over Quick Share are
        typed `application/octet-stream` with no usable path, so `pathPattern` matching
        is unreliable — sniff content after opening. **`MainActivity` is currently
        `exported="false"`; receiving anything requires an exported entry point.**

- [ ] **Session join (the token seam)**
  - [ ] Host generates a session token; `startAdvertising(endpointName = token,
        P2P_STAR)`. Joiner discovers, matches the token, `requestConnection`; host
        accepts only that token. The token both disambiguates a room full of
        advertisers and authorises, so no accept-dialog is needed.
  - [ ] **QR carrier first** — the token as a QR code. ~30 lines, no NFC APIs, works on
        phones without NFC, joinable across a table, and testable without two NFC devices.
  - [ ] **NFC carrier second** — the tap. Note the original plan's flaw: Android Beam
        (NDEF push) is dead, so phone-to-phone means the host runs `HostApduService`
        and the joiner reader mode. Emulating an **NDEF Type 4 tag holding an App Link**
        is the version worth building: the joiner's stock NFC stack launches Quill, so
        their app need not already be open.
  - [ ] Treat NFC and QR as interchangeable carriers of the same token — the join code
        below them is identical.

- [ ] **Live whiteboard session**
  - [ ] Three messages only: `snapshot` (current strokes, on join), `stroke` (one
        completed stroke — the re-entry point `WhiteboardFragment` already names in
        `onStrokeComplete()`), `retract` (a stroke id).
  - [ ] Idempotent apply-on-receive: dedupe by stroke id, so a replay is harmless.
  - [ ] **Undo/clear are the only non-append-only operations.** Undo must retract only
        the author's own last stroke and travel as `retract`, not a local delete. Clear
        is destructive to everyone — make it host-only. (Eraser needs nothing: it is
        `tool=1`, a stroke, so it is already append-only.)
  - [ ] Payload sizing: Nearby's `BYTES` caps near 32 KB; chunk or use `FILE` for an
        unusually long stroke.
  - [ ] Once the transport exists, "tap to send a note" is nearly free — the same
        `.quill` bundle as a `FILE` payload into the same import code.

- [ ] **Boundary with Epic B**: a locked/encrypted collection must not be shareable, or
      requires unlock-on-both-ends — still needs an explicit product decision on which.

**Dropped from the original plan** (do not resurrect without re-reading the above):
per-note vector-clock conflict resolution, the `outbox` writer/drainer for notes, and
hand-rolled `WifiP2pManager` discovery/group formation. `notes.vector_clock`,
`notes.author_device_id` and the `outbox` table stay in the schema as inert scaffolding.

---

## Epic D — Standardized Markdown Note Format, Q&A Segments & Flashcards (P2)

**Why here**: independent of P2P — safe to build in parallel with Epic B/C by a second
contributor. The `flashcards` table already has SM-2 columns (`interval`,
`repetitions`, `easiness`, `next_review`) sitting unused. Full design rationale lives in
[note.md](note.md)'s "Markdown note format" section — read that alongside this
checklist, it explains the *why* behind each item below.

**Storage landed 2026-07-28.** The Markdown format and segment-identity work below is
done; Q&A segments, flashcards and the whiteboard embed are still outstanding.

- [x] **Decide storage scope** — chose **Full**: each note is one Markdown document in
      `notes.content_blob`; `note_segments` demoted to a media asset registry (no
      `position`, no `text_content`). Accepted trade-off: a coarser conflict domain for
      the sync Epic C anticipates — the plan is to recover granularity by diffing the
      Markdown at block level, *not* by reverting to per-segment rows. See
      [conversation.md](conversation.md), 2026-07-28.
- [x] **Markdown format & tooling**
  - [x] Round-trip written by hand (`MarkdownSerializer`, `NoteDocument`) — **Markwon
        was not adopted**: it renders but doesn't help with editing, which is the half
        that actually mattered
  - [x] Text formatting: `#`/`##` headings, `**bold**`, `_italic_`, `<u>underline</u>`,
        `- ` bullets. Italic is `_` not `*` — with `*`, a format ending inside another
        emits ambiguous `****` runs (numbered lists still outstanding)
  - [x] Retired `SpanSerializer` outright, along with both its workarounds
  - [x] Image embed: `![](quill://image/<asset-id>)` — by **id, not path**, so moving
        media on disk can't invalidate a document; width/duration/transcript live on the
        asset row, which Markdown link syntax has nowhere to put
  - [x] Audio embed: `![audio](quill://audio/<asset-id>)`
  - [x] Whiteboard embed *(2026-08-07)* — `![whiteboard](quill://whiteboard/<id>)` as reserved.
        Attach from the note toolbar (new or imported via a searchable picker), preview in the
        note, tap through to the board, long-press to detach. Resolves without the media registry,
        since the id names a whiteboard row rather than an asset
  - [ ] Export to portable `.md`: rewrite `quill://` URIs to relative file paths
- [x] **Segment identity — prerequisite fix, found during design review**
  - [x] `BaseSegmentView` now owns a stable id, round-tripped through export/import.
        Forced by the migration itself (embed references would break on every save) and
        no longer blocking the flashcard link below
- [x] **Q&A segment** *(done 2026-07-29)*
  - [x] `NoteSegment.TYPE_QA` + `QaSegment` + `QASegmentView`, styled from the MSE Figma's
        **QA** frame: tonal card, muted question above an answer behind a green
        (`#30B488`) rule. Not an inline cloze marker — Q&A is visibly structured in the note
  - [x] **Rich text in both halves after all**, not the plain-text v1 sketched here.
        Bold/italic/underline/bullets work in question and answer; headings, images and
        audio are refused. Delivered by extracting `RichTextField` out of `TextSegmentView`
        so both segment types share one implementation — see [note.md](note.md)'s
        "Q&A blocks"
  - [x] Markdown form: fenced ` ```quill-qa:<block-id> `, question, `---`, answer, ` ``` `.
        The divider (rather than per-line `Q:`/`A:` prefixes) is what keeps both halves
        ordinary multi-line Markdown. The reserved info-string slot is now **used**, and
        carries the *block's* id rather than a flashcard id — see below
  - [ ] Numbered lists inside a Q&A (bullets only today, same as body text)
- [x] **Flashcard generation & sync** *(done 2026-07-30)*
  - [x] `FlashcardRepository` (sync + record-review), following the existing
        callback-based async pattern used by `NoteRepository`/`TagRepository`
  - [x] `flashcards.source_segment_id` linking a generated card back to its source Q&A
        block (schema v4, additive migration from v3 — no data wipe). The link only holds
        because the **block's id is now persisted in the fence's info string**: segment
        ids are minted by the view, so before this a reload gave every block a new id and
        a card's history would have survived exactly one session
  - [x] Sync logic: unlinked Q&A blocks → create a flashcard; linked ones → update
        `front`/`back` only, never touch SM-2 scheduling state; a card whose source block
        disappeared is left alone (it just stops appearing in the note's deck)
  - [x] Only Q&A blocks with **both** halves non-blank become cards — a question with no
        answer has nothing to turn over, and half-written is a normal editing state
  - [ ] Per-note sync mode: not built. Sync is unconditional and runs when the review
        screen opens, which is the Manual default in all but name — there is no automatic
        on-save sync, so the in-progress-session hazard below hasn't arisen yet
  - [ ] Automatic mode must not disrupt an in-progress review session — a session
        snapshots its due-card queue at start; auto-sync writes to the table but never
        mutates a card the user is actively looking at mid-session
  - [x] Entry point: **"Turn into flashcards"** in the note screen's new options menu
        (⋮), becoming **"Review flashcards"** once the note has cards. Shown always rather
        than hidden — a note with Q&A but no complete pair explains itself in a Snackbar,
        where a missing menu item would leave the user looking for a feature they'd been
        told existed
  - [x] Deleting a note's cards, from the decks list and from the review screen. A hard
        delete (the cards are derived data; a tombstone would either be resurrected by the
        next sync or block that note from making cards again), so the confirmation says
        review progress is what's lost
- [ ] **Review & study surfaces**
  - [x] Per-note review session (`FlashcardsFragment`): flip card, right/wrong grading,
        SM-2 scheduling, due-first with an "all caught up / review anyway" state, and
        missed cards re-queued within the sitting
  - [x] Decks list (`FlashcardDecksFragment`), a top-level destination alongside Home in a
        new bottom navigation bar: one row per note that has cards, with the due count,
        totals and next-review time
  - [ ] Per-card management (inspect/edit/delete an individual card, rather than a note's
        whole deck)
  - [ ] Global review session screen — today's due cards across *all* notes
- [ ] **Reminders (background infrastructure, reusable beyond flashcards)**
  - [ ] Notification channel setup
  - [ ] WorkManager/AlarmManager job to notify when cards are due
  - [ ] User-configurable reminder schedule (time of day / frequency)

---

## Epic E — Quizzes (P3, depends on Epic D)

**Why here**: quizzes need a content source, and flashcards are the natural one per
the one-pager's own framing ("flashcards... and built-in quizzes"). Sequencing this
after D avoids building a second, parallel content model for quiz questions.
Deliberately avoids free-text answer grading and AI-generated content — see
[note.md](note.md)'s "Planned: Quizzes" section for the reasoning; quizzes are built
entirely from auto-gradable question types generated locally from the flashcard pool.

**Per-note MCQ quizzes landed 2026-08-01.** The scope picker, True/False fallback and
matching mode below are what's left. One design change against the plan: quizzes read the
note's Q&A blocks directly rather than the `flashcards` table — see [note.md](note.md)'s
"Quizzes" section for why sharing the *rule* beat sharing the *rows*.

- [x] **Data layer** *(schema v5, additive from v4 — no data wipe)*
  - [x] `quizzes(id, note_id, created_at)` — a quiz is a marker that a note is one; the
        unique index on `note_id` is what makes "Make quiz" idempotent ("Open quiz" after)
  - [x] `quiz_attempts(id, quiz_id, score, answered, total, status, started_at,
        finished_at)`. No `scope_type`/`scope_id` until a second scope exists; `total` is
        per attempt because a note's block count moves; `answered` is what separates an
        abandoned attempt from a bad one
  - [x] Attempt opened at start, not on completion, so walking out is recorded rather
        than rewarded. A killed process leaves it in progress; a sweep on next load retires
        it, using the quiz's own time budget as the staleness rule
  - [ ] `quiz_attempt_answers(attempt_id, …)` — still not built, and not wanted until a
        screen reopens a past attempt's answers. The marked paper is shown from the live
        session at the end of a run
- [ ] **Question generation** (all local, no AI, no free-text matching)
  - [x] MCQ via cross-block distractors (`QuizGenerator`): the correct answer plus 3 real
        answers from *other* Q&A blocks in the same note, deduplicated case- and
        whitespace-insensitively so a question can't have two right answers. Question order
        and option order are both shuffled per attempt
  - [x] Minimum of 5 complete blocks (`QuizRules.MIN_QA_BLOCKS`) — four options need four
        blocks, and the fifth stops every question reusing the same three distractors.
        Enforced at the entry point with a Snackbar, and again on the detail screen
  - [ ] True/False fallback for notes too small for a good MCQ (needs only 2 blocks)
  - [ ] Matching mode (later/optional): N questions + N shuffled answers to pair up
- [ ] **Quiz-taking UX**
  - [ ] Scope picker: quiz a whole collection or a tag. Today the scope is always one note
  - [x] Entry point: **"Make quiz"** in the note's options menu (⋮), becoming **"Open
        quiz"** once the note has one — same pattern as the flashcard item beside it
  - [x] Quiz session (`QuizSessionFragment`): single-select options on an answer sheet that
        can be filled in any order — Previous/Next without answering, tappable
        answered/blank indicators across the top, answers changeable and clearable — marked
        all at once on submit so the run measures rather than teaches
  - [x] One clock for the whole run (`QuizRules.totalTimeMs` = 15s × questions), turning red
        with a warning under `WARNING_TIME_MS`, pausing behind dialogs, and completing the
        attempt (not abandoning it) when it expires
  - [x] Submitting with blanks, behind a confirmation that counts them — blanks are marked
        wrong rather than excluded from the score
  - [x] Quizzes tab (`QuizzesFragment`) as a third top-level destination, and per-quiz
        history (`QuizDetailFragment`): every attempt with its score, date and whether it
        was completed or abandoned, plus delete-with-confirmation
  - [ ] Reopening a past attempt to see its questions again (needs the answers table above)

---

## Epic F — Search, Trash & Location (P2, independent, low risk)

**Why here**: three small, independent gaps between what the schema already stores
and what the UI surfaces. None blocks or is blocked by another epic; good filler work
or a second-contributor track alongside Epic D.

- [ ] **Wire up full-text search**
  - [x] Keep `notes_fts` populated — done 2026-07-28, as a side effect of the Markdown
        migration giving the body a single source. The table was also **fixed**: it was
        declared `content='notes'` with a `body` column that doesn't exist on `notes`, so
        it could never have been populated by triggers or otherwise. Now standalone
        `fts5(note_id UNINDEXED, title, body)`, written by `NoteRepository` in the same
        transaction as the save (and cleared on delete), guarded for FTS5-less builds
  - [ ] Replace `HomeFragment`'s in-memory list filter with an FTS5 `MATCH` query — the
        index is ready and unused; this is the only remaining piece
- [ ] **Trash / recover UI**
  - [ ] "Recently deleted" screen listing notes with `deleted_at IS NOT NULL`
  - [ ] Restore and permanently-delete actions
  - [ ] Auto-purge policy after N days (needs a product decision on N)
- [ ] **Geotagged notes**
  - [ ] Capture `location_lat`/`location_lng`/`location_name` on note creation,
        permission-gated
  - [ ] Display/filter notes by location

---

## Epic I — Whiteboards as a First-Class Surface (P2, independent)

**Why here**: whiteboards existed in the schema and had a full drawing screen, but nothing in the
app could reach one — no list, no link from a note, and Home's "New Whiteboard" FAB navigated with
a Bundle that violated `nav_graph.xml`'s required `note_id`. The Home section below closed that
gap; what's left is the link back to notes, which overlaps Epic D's whiteboard embed.

- [x] **Home Whiteboards section** *(2026-08-03)* — own section between Collections and Notes,
      2-column grid, sorted by `updated_at`, searchable by title, long-press to rename/delete
- [x] **Whiteboards can stand alone** — `whiteboards.note_id` nullable; schema v4 added
      `title`/`created_at`/`updated_at` via a real in-place migration
- [x] **`WhiteboardRepository`** on the shared `AppExecutors` pattern (Home's entry point)
- [x] **A canvas bigger than the screen** *(2026-08-07)* — ten screens each way, opening in the
      middle, two-finger pan (plus a Move tool for one finger), and a Centre button that brings the
      window back to the ink. Strokes moved to canvas coordinates; export now covers the whole
      drawing rather than the window. No zoom, deliberately — see note.md for why that bounds it
- [x] **Whiteboard screen re-laid out** *(2026-08-07)* — heading alone at the top with back and a
      show/hide toggle, every tool in one content-sized floating rail down the left, and one
      selection idiom across tools, colours and widths
- [x] **Typed text on a board** *(2026-08-07)* — `whiteboard_texts` (schema v7), a text tool that
      places a label where you tap, immutable like a stroke so undo/clear/export all treat it the
      same. Editing means undo-and-retype; that is what keeps a board append-only for Epic C
- [x] **Paper styles** *(2026-08-07)* — plain/warm/dotted per board (`whiteboards.background`,
      schema v8). Forced the eraser to switch from painting white to real `PorterDuff.CLEAR`
      erasure, since a white stroke is only invisible on a white board
- [x] **Paper preference for new boards** *(2026-08-07)* — last choice becomes the default via
      `WhiteboardPreferences`; existing boards keep theirs
- [x] **Whiteboard previews on Home** *(2026-08-07)* — cards show the drawing, then name and date,
      per the Figma `HomePage_whiteboard` frame. Reverses the 08-03 "no thumbnail" decision; see
      note.md for what made it affordable
- [ ] **Make board text searchable** — Home matches whiteboards on title only, and text items are
      now the first real content a board has. Search-side change, not a whiteboard one
- [ ] **Visual QA** — nothing here has been seen running; the section, the create/rename dialogs
      and the FAB path are all build-unverified as of 2026-08-03
- [x] **Open a note's whiteboard from the note** *(2026-08-07)* — done with the Epic D embed, as
      intended: the embed's sheet navigates to the board
- [ ] **Port `WhiteboardFragment`/`StrokeDao` onto `AppExecutors`** (also listed under Epic A).
      Note the constraint found while doing the section: the `whiteboards` insert must still
      happen before any stroke write, since `strokes` has a foreign key onto it

---

## Epic G — Hardware Sensor Showcase (P3, needs scoping)

**Why last**: the one-pager mentions "hardware sensors" as a platform-depth goal
without naming a specific sensor or user benefit. Camera, microphone, and (via Epic F)
GPS are already spoken for by other epics — this needs a concrete decision before it's
estimable, not more building.

- [ ] Decide which additional sensor maps to a real feature (candidates: accelerometer
      for a "shake to undo" on the whiteboard; ambient light for auto-adjusting
      whiteboard contrast; proximity to pause read-aloud when the phone is pocketed)
- [ ] Implement the chosen integration(s)

---

## Epic H — Material 3 UI Migration (P2, independent, low risk)

**Why here**: independent of every other epic — pure UI/theming work, safe filler or a
parallel track. Full rationale, what's done, and the non-obvious MDC gotchas hit along the
way live in [note.md](note.md)'s "Material 3 UI migration" section; read that alongside
this checklist.

**Material 3 is now the project-wide standard for all UI** — see note.md's "Conventions
worth following". The items below are the migration itself; the convention outlives it.

- [x] **Theme + color roles** — `Theme.Quill` extends `Theme.Material3.Light.NoActionBar`;
      existing palette remapped onto M3 color roles
      (`colorPrimaryContainer`/`colorOnPrimaryContainer`/etc.)
- [x] **Chips** — `TagChipView`'s two pills build a real
      `com.google.android.material.chip.Chip` instead of `TextView` + `GradientDrawable`
- [x] **Cards** — `NoteRowView`, `CollectionCardView`, `PinnedNoteCardView` all →
      `MaterialCardView` via the shared `NoteRowView.applyFlatCardStyle`
- [x] **Dialogs** — all 13 sites → `MaterialAlertDialogBuilder` (most were on the
      framework `android.app.AlertDialog`, which ignored the app theme entirely — this is
      why dialogs still looked Material 2 after the theme switch)
- [x] **Dialog widgets** — `MaterialButton`, `MaterialCheckBox`, and outlined
      `TextInputLayout` via the new `util/TextFieldUtils`
- [x] **Color-swatch picker decision** — stays custom; M3 has no color-picker component
- [x] **FAB under the new theme** — confirmed on-device; picks up M3's rounded-square shape
- [x] **Visual QA** — home, collection detail, note editor, FAB + expanding menu, tag
      picker dialog. Caught a real runtime crash (`TextInputLayout` child needs
      `LinearLayout.LayoutParams`) that the build had not
- [ ] **Finish visual QA on the untouched-by-eye screens** — create/rename-collection
      dialogs (incl. the new `CollectionDialogs.inset()` wrapper), `AddExistingNotesDialog`,
      `RecordingDialog`, whiteboard dialogs, image/audio source pickers. All build clean and
      share the now-fixed `TextFieldUtils` path, but none have been seen running
- [x] **Home header gradient** — root cause was a silent XML namespace typo in
      `bg_home_header.xml` (`.../res/android`, missing `apk/`), which made the shape paint
      nothing at all; the gradient had never rendered. Fixed, colours re-sampled from the
      Figma, and `header_min_height` added. See note.md for the full gotcha
- [x] **Header/sheet layering** — the rounded edge now belongs to the content sheet
      (`bg_content_sheet`, rounded top corners, negative overlap margin) curving over a
      full-bleed gradient, instead of the header curving upwards
- [x] **Playfair Display** bundled in `res/font/` and applied to the home greeting, matching
      the Figma's display serif (variable font + `fontVariationSettings`, OFL licence in
      `/licenses/`)
- [x] **Search fields** — `fragment_home.xml` / `fragment_collection_detail.xml` now use
      outlined `TextInputLayout` (static hint, inner id kept as `search_input` so no Java
      changes); unused `bg_search_field.xml` deleted
- [x] **Formatting toolbar** (2026-07-29) — the last framework `Button`s in the editor,
      replaced by compact weighted icon items on one tonal surface flush against the
      keyboard. Active state is a small primary dot rather than M3's tonal/filled
      selected button: eight filled pills sitting on the keyboard read as a second
      keyboard. Recorded as a deliberate departure per the convention above

---

## Cross-cutting notes

- Epics B and C both touch `notes`/`note_segments` at rest — coordinate schema changes
  between them (e.g. an encryption-at-rest column shouldn't collide with a
  sync-metadata column added around the same time).
- Epic A's test suite should grow alongside B/C, not be written retroactively —
  encryption and sync bugs are exactly the kind that are painful to root-cause without
  tests that existed *before* the bug was introduced.
- Epic D's whiteboard-embed convention is written to also serve Epic C/note.md's
  "later I also want to embed/link whiteboards into notes" goal — one embed format,
  not two — so don't design a separate whiteboard-linking mechanism inside Epic C.
- Epic D's storage-scope decision (Minimal vs. Full) is intentionally left open in the
  checklist above — do not start that work until it's resolved in discussion.
- Epic E's MCQ distractor quality depends on a note/collection having enough
  topically-related Q&A blocks to draw plausible wrong answers from — very small or
  very mixed-topic scopes may only ever qualify for True/False, not MCQ. Handled for the
  note scope by refusing to build a quiz below `QuizRules.MIN_QA_BLOCKS`; the same
  question returns when a collection/tag scope is added, since a mixed collection can
  clear the count while still producing obviously-wrong options.
