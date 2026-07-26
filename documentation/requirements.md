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

## Epic D — Flashcards & Spaced Repetition (P2)

**Why here**: independent of P2P — safe to build in parallel with Epic B/C by a second
contributor. The `flashcards` table already has SM-2 columns (`interval`,
`repetitions`, `easiness`, `next_review`) sitting unused; this epic is "just" wiring
UI and scheduling logic onto a schema that's already designed for it.

- [ ] **Data layer**
  - [ ] `FlashcardRepository` (CRUD), following the existing callback-based async
        pattern used by `NoteRepository`/`TagRepository`
- [ ] **Authoring**
  - [ ] Manual flashcard creation (front/back) from within a note
  - [ ] "Generate draft cards from this note" helper (e.g. heading + following text →
        a draft card the user edits before saving) — reduces manual authoring friction
- [ ] **Review flow**
  - [ ] SM-2 scheduling: update `interval`/`repetitions`/`easiness`/`next_review` per
        review answer
  - [ ] Review session UI: due-today queue, flip card, rate recall
- [ ] **Reminders (background infrastructure, reusable beyond flashcards)**
  - [ ] Notification channel setup
  - [ ] WorkManager/AlarmManager job to notify when cards are due
  - [ ] User-configurable reminder schedule (time of day / frequency)

---

## Epic E — Quizzes (P3, depends on Epic D)

**Why here**: quizzes need a content source, and flashcards are the natural one per
the one-pager's own framing ("flashcards... and built-in quizzes"). Sequencing this
after D avoids building a second, parallel content model for quiz questions.

- [ ] Quiz generation from a collection's/note's flashcards (multiple-choice using
      other cards' answers as distractors, or plain recall)
- [ ] Quiz-taking UI + scoring
- [ ] Quiz history / score tracking (needs a new table — not yet in the schema)

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

## Cross-cutting notes

- Epics B and C both touch `notes`/`note_segments` at rest — coordinate schema changes
  between them (e.g. an encryption-at-rest column shouldn't collide with a
  sync-metadata column added around the same time).
- Epic A's test suite should grow alongside B/C, not be written retroactively —
  encryption and sync bugs are exactly the kind that are painful to root-cause without
  tests that existed *before* the bug was introduced.
