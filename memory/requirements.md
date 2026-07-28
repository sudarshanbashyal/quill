# Quill — Requirements & Delivery Checklist

Derived from `one-pager.pdf` (project vision) plus a code-level audit of what's already
implemented vs. what the schema/comments anticipate but nothing builds yet (see
[note.md](note.md) for the architecture detail behind these gaps). Requirements below
mix **feature** work and **architecture/quality** work — the one-pager treats "secure
and efficient" and platform-capability depth as first-class goals, not an afterthought.

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

- [ ] **Safe schema migrations**
  - [ ] Replace `AppDatabase.onUpgrade`'s drop-and-recreate with real `ALTER TABLE` /
        versioned migration steps
  - [ ] Add a migration test: seed a v1-shaped DB with data, run the upgrade, assert
        rows survive
- [ ] **Unify background DB access**
  - [ ] Port `StrokeDao` / `WhiteboardDao` / `WhiteboardFragment` off ad hoc
        `new Thread(...)` calls onto the shared `AppExecutors.diskIO()` used by the
        other repositories
  - [ ] Audit all fragments for any accidental main-thread `SQLiteDatabase` access
- [ ] **Automated test coverage**
  - [ ] Replace the placeholder `ExampleUnitTest` / `ExampleInstrumentedTest` with real
        tests: Note/Collection/Tag repository CRUD, segment replace + orphaned-media
        cleanup, `SpanSerializer` round-trip (headings, bullets, multi-blank-lines)
  - [ ] Instrumentation test for `NoteEditorView` segment split/merge/delete behavior
        (image/audio insertion mid-text, backspace-merge at segment boundary)
  - [ ] Wire up CI (e.g. GitHub Actions) to run unit tests + lint on every PR
- [ ] **Fix bugs found during architecture review**
  - [ ] `WhiteboardFragment.exportWhiteboard()` shows a "Export failed" toast
        unconditionally even after a successful export

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
  - [ ] Encrypt locked collections' note content (`notes.title`, segment
        `text_content`/`file_path` payloads) at rest; decrypt only after biometric auth
  - [ ] Handle key invalidation gracefully (e.g. user re-enrolls a fingerprint) instead
        of silently locking the user out of their own data
- [ ] **Migration cases**
  - [ ] Locking a previously-unlocked collection: encrypt existing notes in place
  - [ ] Unlocking: decrypt and re-store in plaintext, with explicit user confirmation

---

## Epic C — Peer-to-Peer Collaboration: NFC + Wi-Fi Direct (P1)

**Why here**: the one-pager calls this "central" to the app and the biggest reason to
be offline-first. It's also the largest architectural lift, and the schema already
anticipates it (`notes.author_device_id`, `notes.vector_clock`, `outbox` table,
`strokes.author_id`) — `WhiteboardFragment`'s own comments name the intended
re-entry point (`WiFiDirectManager`, `server.broadcast()` / `client.sendStroke()`).
Sequence strictly: **discovery → transport → protocol → applications** — each layer
needs the one below it working first, and skipping straight to "live whiteboard
collab" without a tested transport/protocol underneath is how this epic turns into an
unshippable mess.

- [ ] **Device discovery & pairing**
  - [ ] NFC tap-to-pair handshake to exchange device id + connection info
  - [ ] Wi-Fi Direct peer discovery and group formation (`WifiP2pManager`)
  - [ ] Paired/trusted-device list UI
- [ ] **Transport layer**
  - [ ] Framed socket message channel over the established Wi-Fi Direct group
  - [ ] Reconnect/backoff handling when a P2P link drops mid-transfer
- [ ] **Sync protocol** (built on the existing `outbox` / `vector_clock` columns)
  - [ ] Outbox writer: local note/stroke changes enqueue a row in `outbox`
  - [ ] Outbox drainer: flush queued messages once a peer connection is live
  - [ ] Vector-clock conflict resolution for the same note/whiteboard edited on two
        devices while disconnected
  - [ ] Idempotent apply-on-receive (dedupe by id; a replayed message must not
        double-apply)
- [ ] **Applications built on the protocol**
  - [ ] One-shot note sharing (full note incl. segments + referenced media files) via
        NFC/Wi-Fi Direct
  - [ ] Live collaborative whiteboard — multiple `author_id` values drawing on the same
        `whiteboard_id` in real time (the `Stroke.authorId` field already models this)
  - [ ] Enforce Epic B at the boundary: a locked/encrypted collection must not be
        shareable (or requires unlock-on-both-ends first) — needs an explicit product
        decision on which

---

## Epic D — Standardized Markdown Note Format, Q&A Segments & Flashcards (P2)

**Why here**: independent of P2P — safe to build in parallel with Epic B/C by a second
contributor. The `flashcards` table already has SM-2 columns (`interval`,
`repetitions`, `easiness`, `next_review`) sitting unused. Full design rationale lives in
[note.md](note.md)'s "Planned: standardized Markdown note format, Q&A segments &
flashcards" section — read that alongside this checklist, it explains the *why* behind
each item below.

- [ ] **Decide storage scope** *(open — pending further discussion before starting)*
  - [ ] **Minimal**: re-encode only `TextSegment` content as Markdown (swap
        `SpanSerializer`'s HTML internals), keep the `note_segments` row-per-segment
        model as-is
  - [ ] **Full**: collapse each note into one Markdown document stored in the
        currently-unused `notes.content_blob` column; every segment type becomes an
        inline block; `note_segments`/`position` bookkeeping retired
- [ ] **Markdown format & tooling**
  - [ ] Adopt Markwon (`io.noties.markwon`) for parsing/rendering; use its plugin
        system for the custom block types below instead of forking a parser
  - [ ] Text formatting: `#`/`##` headings, `**bold**`, `*italic*`, `<u>underline</u>`
        (raw HTML span — valid CommonMark), `- ` bullets (+ numbered lists as a bonus)
  - [ ] Retire `SpanSerializer`'s zero-width heading markers and newline-collapsing
        HTML workaround — the Markdown equivalents don't have HTML's ambiguity
  - [ ] Image embed: native `![alt](path)`
  - [ ] Audio embed: `<audio src="..." data-duration="...">`
  - [ ] Whiteboard embed (also feeds Epic C's whiteboard-linking goal):
        `<img src="thumb.png" data-quill-embed="whiteboard" data-quill-id="...">` —
        degrades to a static picture outside Quill, tappable live preview inside it
- [ ] **Segment identity — prerequisite fix, found during design review**
  - [ ] `NoteEditorView.exportSegments()` never sets `NoteSegment.id`, so
        `NoteRepository.replaceSegmentsSync` mints a fresh UUID for every segment on
        every save (autosave fires every ~500ms). Anything that needs to reference "this
        exact segment" across edits — the flashcard link below — breaks without this
  - [ ] Give each `BaseSegmentView` a stable id, assigned once and round-tripped through
        export/import instead of discarded on every save
- [ ] **Q&A segment**
  - [ ] New `NoteSegment.TYPE_QA` + `QASegmentView`: bordered two-field card, plain-text
        Question / plain-text Answer (no rich text in v1) — not an inline cloze marker;
        Q&A content should be visibly structured in the note, not hidden in prose
  - [ ] Markdown form once the storage-scope decision lands: fenced
        `` ```quill-qa:<flashcard-id> `` block
- [ ] **Flashcard generation & sync**
  - [ ] `FlashcardRepository` (CRUD), following the existing callback-based async
        pattern used by `NoteRepository`/`TagRepository`
  - [ ] `flashcards.source_segment_id` (nullable) linking a generated card back to its
        source Q&A segment — depends on the segment-identity fix above
  - [ ] Per-note sync mode, user-selectable, default **Manual**: "Sync Flashcards"
        button (manual) vs. "Sync Automatically" (syncs on every note save)
  - [ ] Sync logic: unlinked Q&A segments → create a flashcard; linked segments →
        update `front`/`back` only, never touch SM-2 scheduling state; flashcards whose
        source segment disappeared → flagged as orphaned, not silently deleted
  - [ ] Automatic mode must not disrupt an in-progress review session — a session
        snapshots its due-card queue at start; auto-sync writes to the table but never
        mutates a card the user is actively looking at mid-session
  - [ ] "Create/Sync Flashcards" entry point on the note screen, hidden when the note
        has no Q&A segments, with concrete feedback ("3 created, 1 updated — View
        flashcards") rather than a bare toast
- [ ] **Review & study surfaces**
  - [ ] Per-note flashcard list (manage/inspect what a specific note produced)
  - [ ] Global review session screen — today's due cards across *all* notes (this is
        what actually exercises SM-2 scheduling, not the per-note list)
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

- [ ] **Data layer**
  - [ ] `quiz_attempts(id, scope_type, scope_id, score, total, taken_at)` — new table,
        not yet in the schema
  - [ ] Optional `quiz_attempt_answers(attempt_id, flashcard_id, was_correct)` if
        per-question review after a quiz is wanted
- [ ] **Question generation** (all local, no AI, no free-text matching)
  - [ ] MCQ via cross-card distractors: sample 3 wrong options from other flashcards'
        `back` text in the same scope (same note; fall back to the same collection if
        there aren't enough sibling cards)
  - [ ] True/False fallback for scopes too small for a good MCQ (needs only 2 cards)
  - [ ] Matching mode (later/optional): N questions + N shuffled answers to pair up
- [ ] **Quiz-taking UX**
  - [ ] Scope picker: quiz a single note, a whole collection, or a tag
  - [ ] Quiz session UI + scoring, written to `quiz_attempts` on completion
  - [ ] Quiz history screen (past attempts/scores per scope)

---

## Epic F — Search, Trash & Location (P2, independent, low risk)

**Why here**: three small, independent gaps between what the schema already stores
and what the UI surfaces. None blocks or is blocked by another epic; good filler work
or a second-contributor track alongside Epic D.

- [ ] **Wire up full-text search**
  - [ ] Add INSERT/UPDATE/DELETE triggers to keep `notes_fts` in sync with `notes`
        (the FTS5 table is created today but nothing ever populates it)
  - [ ] Replace `HomeFragment`'s in-memory list filter with an FTS5 `MATCH` query
        (matters once note counts grow beyond trivial)
- [ ] **Trash / recover UI**
  - [ ] "Recently deleted" screen listing notes with `deleted_at IS NOT NULL`
  - [ ] Restore and permanently-delete actions
  - [ ] Auto-purge policy after N days (needs a product decision on N)
- [ ] **Geotagged notes**
  - [ ] Capture `location_lat`/`location_lng`/`location_name` on note creation,
        permission-gated
  - [ ] Display/filter notes by location

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
  topically-related flashcards to draw plausible wrong answers from — very small or
  very mixed-topic scopes may only ever qualify for True/False, not MCQ.
