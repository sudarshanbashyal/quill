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

- [x] **App-wide lock (done 2026-08-13)** — a gate, not encryption, and labelled as such in the UI
  - [x] Profile screen (4th bottom-nav tab): display name, notifications placeholder, security,
        danger zone
  - [x] Optional app lock via `BiometricPrompt` with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`,
        re-prompting after a configurable grace period (default 1 min)
  - [x] Gate raised in `onPause` too, so the recents thumbnail can't leak the open note
  - [x] Delete-all-data with typed confirmation (`DataWipe`)
  - Decision: **no Quill-specific PIN, at either level.** A custom passcode needs a recovery path
    that either weakens the lock or loses the notes, and it can't gate a Keystore key — which is
    what the per-collection work below actually needs. One device credential everywhere.
- [x] **Lock/unlock UX (done 2026-08-13)**
  - [x] "Lock collection" / "Remove lock" in the collection long-press manage dialog
  - [x] `BiometricPrompt` gate before a locked collection's notes are opened/decrypted
  - [x] Non-biometric device fallback (device PIN/pattern via
        `BiometricManager.Authenticators.DEVICE_CREDENTIAL`)
  - [x] Unlock lasts the session; `CollectionLock.relockAll()` on `MainActivity.onStop`
- [x] **Actual cryptographic protection, not just an access gate**
  - [x] Per-collection AES-256-GCM key in Android Keystore, `setUserAuthenticationRequired(true)`
        with a 5-minute validity window (`setUserAuthenticationParameters` on API 30+,
        `setUserAuthenticationValidityDurationSeconds` below). Time-bound rather than per-use
        `CryptoObject`, or reading a collection would cost one prompt per note
  - [x] `notes.title` (Base64'd, TEXT column) and `notes.content_blob` (raw) encrypted at rest
  - [ ] **Media files (`note_segments.file_path`) are still plaintext on disk** — see the
        deferral note below. This is the one part of the epic not delivered
  - [x] Key invalidation handled: `KeyGoneException` surfaces a dialog offering to keep or
        delete the unreadable notes, rather than silently eating the collection
- [x] **Migration cases**
  - [x] Locking encrypts existing notes in place, in one transaction with the flag flip
  - [x] Unlocking decrypts and re-stores in plaintext, behind an explicit confirmation
  - [x] Moving a note in or out of a locked collection converts it (`assignCollection`) —
        without this the row's bytes and its `collection_id` would disagree about the format
- [x] **Leak surfaces closed while a collection is shut**: Home's note list, the pinned band,
      search, flashcard decks and the quiz list all exclude it; `notes_fts` rows are deleted
      on lock (the index stores the body as plain text); flashcards for those notes are
      deleted on lock for the same reason (`front`/`back` are plaintext columns), which costs
      the SM-2 schedule and is stated in the confirmation dialog

> **Deferred — media encryption.** Images and recordings referenced by `note_segments.file_path`
> are unreachable through the UI while a collection is shut, but the files themselves are still
> unencrypted in `filesDir`. Doing this properly means a decrypt-on-demand path through all four
> decode sites (`BitmapUtils`, `PdfExporter`, `AudioPlayback`, `WaveformCache`); audio in
> particular needs a real seekable file, so it can't be done in memory the way images can. A
> half-version that writes decrypted temp files and forgets to clean them up would be worse than
> the current state, which is why it is called out rather than rushed.

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

- [x] **Note sharing (no session, no transport of our own)** — built 2026-08-08.
  - [x] `.quill` bundle: a zip of `note.md` + `media/` + manifest. Lossless, unlike the
        Markdown export, which reduces images/audio to placeholders. `share/QuillBundle`
        (format), `share/BundleWriter`, `share/BundleReader`.
        *Deviation*: always a zip, never a bare `.md` for a media-free note. The
        original was a "may"; one container means one import path, and the graceful
        degradation it bought is already covered by the Markdown export.
  - [x] Share via `ACTION_SEND` + FileProvider → the system sheet (Quick Share,
        Bluetooth, mail). No integration work: Quick Share is a share *target*, not an API.
        Options → Export → **Share to another Quill**; the file also lands in
        `Downloads/Quill` like the other two formats, and the confirmation dialog's
        positive button becomes **Share** instead of **Open**.
  - [x] **Import**: `ACTION_OPEN_DOCUMENT` → picker → unpack. Home's FAB → **Import
        Note**. Filter is `*/*`, not `application/zip` — a bundle that came over Quick
        Share is typed `application/octet-stream`, so a narrow filter would grey out
        exactly the files this exists to open.
  - [x] Import semantics: mint a new note id, re-id media into private storage and
        rewrite `quill://` URIs (`NoteDocument.rewriteEmbedIds`), match tags by name
        (create if missing). `data/NoteImporter`.
  - [x] **Extended to whiteboards and collections** (2026-08-08, not in the original plan):
        `.quillboard` (JSON, `share/WhiteboardBundle`) shares one board losslessly via a new
        "Share whiteboard" option beside the existing flat-image export; `.quillpack`
        (`share/CollectionBundle`) shares a whole collection as a zip of each note's own
        `.quill`. Both reuse the note bundle's import machinery rather than duplicating it —
        `NoteImporter.insertBundle` takes an optional collection id for this reason. Home's
        importer tries note → whiteboard → collection in sequence against the same `*/*` pick,
        since each format's manifest rejects the other two.
  - [x] *(2026-08-09)* `ACTION_VIEW` intent filter on `MainActivity` (now `exported="true"`,
        `singleTask`) so a received `.quill`/`.quillboard`/`.quillpack` opens straight into
        Quill instead of needing Home's manual Import. Matched on `mimeType` only
        (`application/zip`, `application/json`, `application/octet-stream`) — a
        `content://` Uri from Quick Share has no usable path for `pathPattern` to match, as
        flagged here originally. The real check is still each bundle reader's, tried in
        sequence by `HomeFragment.handleSharedFile` after the file is opened. `ACTION_SEND`
        not added — nothing sends a file *to* Quill as an attachment today.

- [x] **Session join (the token seam)** — built 2026-08-11, QR carrier only (see below).
      `collab/CollabSession` wraps Nearby Connections: host mints a random token and
      `startAdvertising(endpointName = token, P2P_STAR)`; joiner `startDiscovery`, matches
      the token against `DiscoveredEndpointInfo.getEndpointName()`, `requestConnection`.
      `onConnectionInitiated` accepts unconditionally on both sides — reaching that
      callback already proves the other device knew the token, so there's no separate
      accept dialog, exactly as planned.
  - [x] Host generates a session token; joiner discovers, matches, connects — as above.
  - [x] **QR carrier** — `collab/QrCodes` (zxing) renders the token; the whiteboard's new
        "Collaborate" toolbar button shows it while hosting. Joining scans it via
        `GmsBarcodeScanning`'s own scanner UI, so Quill never holds `CAMERA`.
  - [ ] **NFC carrier** — deferred, not built this pass. QR alone is enough to test and
        ship; the `HostApduService` + NDEF Type 4 tag design in the paragraph below is
        unchanged and still the plan if NFC gets picked up later.
  - [x] Treat NFC and QR as interchangeable carriers of the same token — true by
        construction: `CollabSession.join` only ever needs the token string, not how it
        arrived, so an NFC carrier would just be a second way to obtain that string.

- [x] **Live whiteboard session** — built 2026-08-11 (`collab/CollabMessage`,
      `collab/CollabSession`, wired into `WhiteboardFragment`), **verified on two physical
      devices 2026-08-12**: connect via QR, live stroke sync, undo (own item only), and
      host-only clear (disabled for the joiner; wipes both boards from the host) all
      confirmed working. One real bug was caught and fixed in the process — see note.md's
      "Live collaboration: joiner crash on snapshot" — a received stroke/text item kept
      the sender's `whiteboardId` instead of being re-homed onto the receiving device's
      own board row, which violated the `strokes` foreign key and crashed the joiner's
      process the moment a snapshot arrived.
  - [x] Three messages, plus `clear`: `snapshot` (the host's full board, sent once on
        join), `stroke`/`text` (one completed item), `retract` (an id). `clear` was added
        beyond the original three because host-only destructive clear needs to travel
        as a message too, not just a local action.
  - [x] Idempotent apply-on-receive: `WhiteboardView.addStroke`/`addText` dedupe by id, so
        a replay is harmless.
  - [x] **Undo/clear are the only non-append-only operations.** Undo retracts only the
        author's own last item because received strokes/text are never pushed onto the
        local `undoStack` — only things *this device* drew are, so popping it can never
        reach into someone else's ink. Clear is host-only: `btnClear` is disabled outright
        for a joiner during a live session (`applyCollabRoleToUi`), and the host's clear
        travels as a `CLEAR` message rather than each side clearing independently.
  - [ ] Payload sizing: Nearby's `BYTES` caps near 32 KB; an unusually long single stroke
        or a snapshot of a very large board could exceed it. Not hit in testing, not
        guarded against — chunking or a `FILE` payload is the fix if it comes up.
  - [ ] "Tap to send a note" (the `.quill` bundle as a `FILE` payload) — not built; the
        transport exists now, this is next to fall out of it near-free, as planned.

- [x] **Boundary with Epic B**: decided 2026-08-08 — **a locked collection's notes are not
      shareable**, rather than unlock-on-both-ends. A bundle is plaintext, so sharing one
      would be the lock's only hole. `CollectionRepository.isLocked` exists and the editor
      consults it; nothing sets `biometric_locked` yet, so it always answers false today.
      The guard is in place for when Epic B starts writing the column.

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
- [x] **Reminders (background infrastructure, reusable beyond flashcards) — done 2026-08-13**
  - [x] Notification channel (`quill_study_reminders`, IMPORTANCE_DEFAULT, VISIBILITY_PRIVATE)
  - [x] WorkManager job to notify when cards are due (`reminders/StudyReminderWorker`)
  - [x] User-configurable time of day, via `MaterialTimePicker` on the Profile screen
  - [x] Tapping the notification lands on the Flashcards tab, with the tab selected
  - Design: **one-time work that re-arms itself**, not `PeriodicWorkRequest`. A periodic
    request's period runs from enqueue and the system slides each run within a flex window, so
    "remind me at 20:00" drifts into the afternoon within a week. Each run computes the delay to
    the next occurrence from the calendar, which also absorbs DST and timezone changes. The
    re-arm is in a `finally`, so a run that throws still schedules tomorrow's.
  - Nothing is sent when nothing is due — a daily "0 cards due" is how a reminder teaches
    someone to ignore it.
  - Locked collections are excluded, and in a background worker that means *all* of them
    (nothing is unlocked). Intended: a lock-screen notification naming a collection the user
    deliberately encrypted would leak both its existence and their neglect of it.
  - **Reusable beyond flashcards, as the epic asks**: `StudyReminders.sync()` is the whole
    scheduling contract, so a second reminder type needs a worker and a preference, not new
    infrastructure. Epic J's watch tile and Epic I's home-screen widget can read
    `FlashcardRepository.countDueSync` directly — that's the "projected count" they were
    blocked on.

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

## Epic J — Wear OS Companion (P3, depends on Epic D)

**Why here**: scoped 2026-08-08 from a design discussion (see [conversation.md](conversation.md)).
A watch is good at *capture* and *micro-review*, and bad at *authoring* — and Quill's phone app is
mostly authoring. So this epic deliberately ports **two** features rather than shrinking the app,
and the "Out of scope" list below is as much a part of the design as the checkboxes above it.

What makes it affordable is a side effect of Epic A: the study logic was kept Android-free so it
could be JVM-tested, and the same property lets a watch module reuse SM-2 verbatim instead of
reimplementing it. What makes it *sequenced after D* is that a tile showing a stale due count is
worse than no tile, so D's reminders infrastructure is a real prerequisite, not filler — and as of
`fbc25a2` it is built, which is what turned this epic from blocked into next.

**Build order, decided 2026-08-13** — phased by feature, not by language (see the stack decision
below). **Phase 0**: the `:study` extraction, alone, proven by its own tests. **Phase 1**: the
projection and the two native surfaces — publish the `DataItem` from the phone, receive and cache
it on the watch, tile + complication, tap through to the phone. **Phase 2**: the Compose review
screen and the `MessageClient` return path. Nothing in phase 1 is rewritten by phase 2: the Kotlin
plugin is already there and adding Compose to a module that compiles Kotlin is purely additive.

- [x] **Extract the study logic into a shared module** — **done 2026-08-13.** *Do this first;
      everything else depends on it.* `FlashcardScheduler`, `ReviewSession`, `QuizSession`,
      `QuizGenerator`, `QuizRules`
      and the `Flashcard` model import nothing but `java.util` today (verified 2026-08-08). Move
      them to a plain-JVM `:study` module that both `:app` and `:wear` depend on, so SM-2 cannot
      drift between the two. The existing JVM tests move with it and keep running without a device.
  - [x] **The list above was one class short**: `QuizQuestion` moved too — `QuizGenerator` builds
        them and `QuizSession` holds them, and its constructor is package-private, so leaving it
        behind would have meant widening visibility for no reason. Seven classes, not six.
  - [x] **The files move; the package names don't.** Re-verified 2026-08-13 — still Android-free.
        `FlashcardScheduler` sits in `mse.quill.data` next to `AppDatabase` and `NoteCrypto`, and
        `ReviewSession` under `ui.flashcards`, so both packages lie about what they hold and the
        tidy fix is to rename them. Don't: split packages across Gradle modules are legal here (no
        JPMS), so keeping the names makes the move import-invisible, and the diff reads "files
        changed module, zero imports touched" — reviewable at a glance, which a sixty-file rename
        carrying the same zero behaviour change is not. Rename later if it ever earns itself.

- [ ] **Sync architecture — a projection, not a replica** — *built 2026-08-13, round trip unverified*
  - [x] The watch holds today's due cards and nothing else, pushed as a `DataItem` over the Wear
        Data Layer. **No Room/SQLite copy of Quill on the watch.** `DataItem`s cap near 100 KB,
        which conveniently forbids the wrong design anyway — no media, no asset registry, no
        `content_blob`.
  - [x] **A locked collection's cards never reach the watch — in either lock state.** Added
        2026-08-13; the epic was scoped on 08-08, before the lock existed, and had no answer for
        it. A `DataItem` carries note text out of the encrypted store onto a device with no
        biometric gate, no `FLAG_SECURE` and a Data Layer store that persists until overwritten,
        so the projection must exclude hidden collections *by construction* — built next to
        `FlashcardRepository.countDueSync` and reusing the same `NoteCrypto.hiddenCollectionIds`
        / `hiddenClause` path, not by a filter a later caller can forget. Unconditional rather
        than lock-state-dependent for the same reason the reminder is: the publish runs in the
        background where nothing is unlocked, so a state-dependent rule would only produce a
        projection that flickers as the gate opens and closes.
  - [x] **The horizon is end-of-day, not `isDue(now)`.** The watch holds a snapshot and cards come
        due continuously, so a projection filtered at publish time says "all caught up" at 09:00
        for a card that came due at 09:05. Ship everything due through end-of-day and let the
        watch re-filter against its own clock.
  - [ ] Q&A halves reach the watch as `NoteDocument`'s **plain-text projection**. Rich text,
        bullets and `RichTextField` do not get ported.
  - [ ] Reviews travel back as append-only events (`card id, grade, timestamp`) via
        `MessageClient`, replayed through the *phone's* `FlashcardScheduler`. SM-2 state is never
        computed on the watch and copied over — same reasoning as Epic C's append-only strokes:
        it turns the merge into a dedupe.
  - [ ] **`recordReview` has to learn to honour the event's timestamp.** It currently calls
        `applyReview(card, correct, System.currentTimeMillis())`, which is right for a review
        answered on the phone and wrong for one replayed off the queue: a session done on a plane
        and drained at 22:00 would have every interval anchored to 22:00. Needs a
        `recordReview(card, correct, long now)` overload — small, and the failure is silent
        interval corruption rather than anything that announces itself.
  - [ ] `CapabilityClient` for phone discovery; queue events while untethered and drain on
        reconnect.
  - [ ] **Tethered, not standalone** — Quill is offline-first with no cloud, so a watch with no
        phone has no way to obtain content. Declare it as such rather than leaving it ambiguous.
  - [x] **Decided 2026-08-13: `:wear` is Kotlin from the first commit.** The app is 145 Java files
        and no Kotlin, so this is the project's first Kotlin, and the temptation was to defer it —
        build the tile in Java, add Compose later only for the review screen. That plan does not
        survive contact with the tile libraries, which split by language and not evenly:
        `protolayout-material` (Material **2.5**) has Java builders, and `protolayout-material3`
        (M3 Expressive, `MaterialScope`, `Material3TileService`) is **Kotlin-only, with no Java
        builders at all**. A Java tile is therefore a Material 2.5 tile — a *second* divergence
        from Epic H's Material 3 standard, bought to avoid a language boundary the review screen
        forces anyway. So the boundary goes at the module edge instead: `:app` and `:study` stay
        Java, `:wear` is Kotlin + Compose, and the phasing below is by feature, not by language.
  - [x] **Correction to the note this replaces**: Wear's view-based widgets are *not* deprecated.
        `androidx.wear:wear` ships; individual pieces are retired (`AmbientModeSupport` →
        `AmbientLifecycleObserver`), and Compose is merely "the recommended approach". The reason
        to skip the view path is the M2.5/M3 split above, not deprecation — worth keeping straight
        so the decision isn't defended later on a claim that isn't true.
  - [ ] The `:wear` module needs its own `minSdk` (30+) against the app's 26.

- [ ] **Flashcard review on the wrist** — the feature that justifies the epic
  - [ ] Front → tap to flip → right/wrong. That is already `ReviewSession`'s whole API surface;
        the watch screen is a thin view over it.
  - [ ] Due-first with the same "all caught up" state as `FlashcardsFragment`, minus
        "review anyway" (a wrist session is a queue, not a browser).

- [ ] **Tile + complication** — the genuinely watch-native surfaces, and cheap
  - [x] Tile (`androidx.wear.tiles` / ProtoLayout): due count. Built on `Material3TileService`,
        whose `tileResponse` is an **extension function on `MaterialScope`**, not a method taking
        one — worth knowing, since `this` inside it is the scope and not the service, and the
        scope carries a `Context` of its own that an unqualified `getString` will silently pick
  - [ ] "Review N" straight into a session — waiting on the phase-2 review screen to send it to
  - [x] Watch-face complication (`ComplicationDataSourceService`): the due count alone
  - [ ] Both read the same projected count. **No longer blocked** (2026-08-13): Epic D's reminder
        infrastructure shipped in `fbc25a2`, and `StudyReminderWorker` is exactly the scheduled
        refresh this was waiting for — it already runs daily and already computes the count, so a
        `getUpdater().requestUpdate()` beside its `notify()` keeps the tile fresh for free
  - [ ] **Phase 1's tile taps through to the phone**, via `RemoteActivityHelper`, before the
        on-watch review screen exists. That is a real feature and not a placeholder — "12 due,
        tap to open Quill" — and phase 2 re-points the click without touching anything else.
        **Not built yet, and deliberately not stubbed**: `RemoteActivityHelper` needs an
        `ACTION_VIEW` intent with a data URI, so it needs a deep link on `:app`'s `MainActivity`
        first. The dependency is declared and the tile is shipping without an edge button rather
        than with a dead one

- [ ] **Voice capture → note** — the one authoring act a watch does better than a pocketed phone
  - [ ] `RecognizerIntent` on-watch → text appended to an "Inbox" note (or a new note)
  - [ ] Optional: record audio on the watch and ship the file down when tethered — the receiving
        end already exists (`AudioRecorder`, audio segments, waveform)

- [ ] **Read-aloud media controls** — near-free, take it
  - [ ] `AudioPlaybackService` already runs a real `MediaSession` with a `PlaybackState` and a
        `Notification.MediaStyle`, so the watch's media card can drive note playback with no new
        playback code. The actual work is notification bridging config (don't mark it local-only).

**Out of scope, deliberately** (each of these is a reason, not an omission):

- **Whiteboards.** The canvas is ten screens each way with two-finger pan. There is no version of
  that on a watch, and "watch as remote for a live session" solves nothing.
- **Quizzes as they exist.** Four MCQ options, a 15s/question budget and an answer sheet fillable
  in any order is a phone screen. **But** Epic E's unbuilt **True/False fallback is exactly the
  watch-shaped quiz** — two buttons, one question, no scrolling. If quizzes go to the watch, build
  T/F watch-first rather than squeezing the MCQ session down.
- **Browsing or editing notes.** If the watch app is in range, so is the phone. Read-only pinned
  notes is the most defensible version and is still marginal.
- **Sensor gimmicks.** A watch makes Epic G tempting, but heart-rate-during-review is a demo, not
  a feature. The honest version, if Epic G is to be retired here, is using on-body/idle state to
  *time* the review nudge.

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
- Epic J turns two "nice to have" items into dependencies: Epic D's **reminder
  infrastructure** (a tile with a stale due count misleads) and Epic E's **True/False
  fallback** (the only quiz shape that fits a watch). If Epic J is in the plan, promote
  those two rather than treating them as leftovers. The first is done as of `fbc25a2`;
  the True/False fallback is still outstanding and is still the gate on quizzes reaching
  the watch at all.
- Epic J's projection inherits the app lock, which was built after the epic was scoped.
  Any surface that leaves the phone — the watch today, Epic I's widget tomorrow — has to
  go through `NoteCrypto.hiddenCollectionIds` the way `countDueSync` does. Worth stating
  once here rather than rediscovering it per surface.
- Epic J's `:study` module extraction is also the cheapest way to keep Epic A's promise
  that the study logic stays Android-free — today that's a convention nothing enforces,
  and a module boundary makes the compiler enforce it.
