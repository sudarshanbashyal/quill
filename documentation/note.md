# Quill — Architecture & Design Notes

Working notes on how Quill is built and *why* certain non-obvious choices were made.
Written to be read cold by a future contributor (human or Claude) — treat it as the
starting point for onboarding, not a replacement for reading the code.

## What Quill is

An offline, single-device Android note-taking app (Java, `minSdk 26`, `compileSdk 36`).
Notes can contain rich text, embedded images, embedded voice memos, and can be organized
into collections and tagged. Notes can also carry a hand-drawn whiteboard. Single
`AppCompatActivity` + Navigation Component fragments; no DI framework, no Room, no
Kotlin — plain Java throughout, minimal dependency footprint (AppCompat, Material,
ConstraintLayout, Navigation, RecyclerView only — see `app/build.gradle.kts`).

Theming is pinned to light mode unconditionally (`AppCompatDelegate.MODE_NIGHT_NO` in
`MainActivity`) — Quill does not follow the system dark-mode setting by design.

## Navigation / screens

Single Activity (`MainActivity`) hosting a `NavHostFragment` (`nav_graph.xml`):

- `HomeFragment` (start destination) — collections grid + notes list/search + up to
  `NoteRepository.MAX_PINNED_NOTES` (3) pinned-note cards.
- `NoteEditorFragment` (args: `note_id` nullable, `collection_id` nullable) — the rich
  text editor. Null `note_id` means "new note, not yet persisted."
- `CollectionDetailFragment` (args: `collection_id`, `collection_name`)
- `WhiteboardFragment` (args: `note_id` required, `whiteboard_id` nullable) — drawing
  canvas, one whiteboard per note.

## Persistence layer

**No Room.** `AppDatabase extends SQLiteOpenHelper` directly (`data/AppDatabase.java`),
hand-written schema and raw SQL everywhere. `DATABASE_VERSION = 2`; `onUpgrade` is
currently **destructive** (drops every table and recreates) — fine for pre-release
development, but will need real migrations before this ships with user data worth
keeping.

Access pattern: `XRepository` classes (`NoteRepository`, `CollectionRepository`,
`TagRepository`) wrap `AppDatabase` + raw `Cursor`/`ContentValues` calls, exposing
async callback-based APIs (`OnNoteLoaded`, `OnNotesLoaded`, …) rather than
LiveData/Flow. `WhiteboardDao` / `StrokeDao` are lower-level DAOs (no repository layer
on top) used directly by `WhiteboardFragment`.

**Threading**: `AppExecutors` is a hand-rolled singleton with one single-thread
`ExecutorService` for all disk I/O plus a main-thread `Handler` for callback delivery.
Deliberately single-threaded so concurrent repositories never fight over SQLite's
single writer lock. `NoteRepository`/`CollectionRepository`/`TagRepository` all go
through it consistently.
**Inconsistency to know about**: `WhiteboardFragment` and `StrokeDao` do *not* use
`AppExecutors` — they spin up ad hoc `new Thread(() -> …)` for every DB call instead.
Functionally fine today (SQLite serializes writes regardless), but it's a second,
uncoordinated threading pattern living next to the "shared executor" one — worth
unifying if the whiteboard code gets touched again.

### Schema (`AppDatabase.onCreate`)

Core, actively used:
- `collections(id, name, color, created_at, biometric_locked)` — `biometric_locked`
  column exists but no lock/auth flow reads or writes it yet.
- `notes(id, collection_id, title, created_at, updated_at, deleted_at, pinned_at, …)`
  — soft-deleted via `deleted_at` (never hard-deleted by app code). Also carries
  `location_lat/lng/name` columns that nothing in the app populates yet.
- `note_segments(id, note_id, position, type, text_content, file_path, transcript,
  duration_ms, width, created_at)` — see "Note content model" below.
- `whiteboards(id, note_id)`, `strokes(id, whiteboard_id, author_id, tool, color,
  width, points_blob, created_at)`.
- `tags(id, name, color, created_at)`, `note_tags(note_id, tag_id)` join table.

Present in schema, **not wired to any repository/DAO/UI yet** (forward-looking
scaffolding, not dead code to delete casually — check before assuming it's unused):
- `flashcards` (front/back + SM-2-style spaced-repetition columns: `interval`,
  `repetitions`, `easiness`, `next_review`)
- `voice_memos` (superseded in practice by `note_segments` TYPE_AUDIO — audio embeds
  in the actual editor go through segments, not this table)
- `outbox` (`type`, `payload_blob`, `target_device_id`) — looks like a staged sync/
  multi-device outbox pattern
- `notes.author_device_id`, `notes.vector_clock` — sync/conflict-resolution columns
  with no writer yet
- `notes_fts` FTS5 virtual table — created (wrapped in try/catch since some emulator
  SQLite builds lack FTS5) but nothing queries it; search in `HomeFragment` today is
  plain in-memory filtering over the loaded note list, not SQL full-text search.

Takeaway: the schema was designed ahead of the current feature set (multi-device sync,
flashcards, geotagged notes, FTS search). Don't be surprised these tables/columns are
empty — it's intentional runway, not abandoned work. `WhiteboardFragment`'s class
comment literally says "SINGLE-DEVICE VERSION — no networking" and describes how
`author_id`/sync would plug back in later via a `WiFiDirectManager`.

## Note content model — segments, not a single Spannable

A note's body is a **list of `NoteSegment`** (`ui/notes/editor/model/`), not one big
rich-text blob:

- `TextSegment` — wraps a `Spannable` (bold/italic/underline/heading/bullet spans)
- `ImageSegment` — file path + optional display width
- `AudioSegment` — file path + duration

`NoteEditorView` (a `LinearLayout`) renders one `BaseSegmentView` child per segment
(`TextSegmentView` / `ImageSegmentView` / `AudioSegmentView`) and stitches them
together to *feel* like one continuous document: typing Enter, inserting an image/audio
mid-paragraph, or backspacing at position 0 of a segment all route through
`BaseSegmentView.SegmentCallback` (`onRequestSplitAt` / `onRequestDelete` /
`onRequestMergeWithPrevious`) so `NoteEditorView` can split/merge/delete segments and
refocus the right one. This is why inserting an image "in the middle of typing" works —
the focused `TextSegmentView` is split into a before/after pair around the new
image/audio segment.

Persistence: `note_segments` rows, ordered by `position`, fully replaced on every save
(`NoteRepository.replaceSegmentsSync` deletes-then-reinserts all segments for a note in
one transaction — no diffing). Orphaned image/audio files (referenced by the old
segment set but not the new one) are deleted from disk *after* the transaction commits.

**Rich text serialization** (`data/serialization/SpanSerializer.java`): a `Spannable`
is round-tripped to bytes via Android's built-in `Html.toHtml`/`Html.fromHtml` rather
than a custom binary/JSON span format. Two non-obvious workarounds layered on top:
1. `Html.toHtml` collapses runs of consecutive newlines into one paragraph boundary, so
   before encoding, runs of 2+ `\n` are collapsed to one real `\n` plus private marker
   characters (`EXTRA_LINE_MARKER`) that Html carries through as literal text instead
   of interpreting as more paragraph breaks; decoding reverses both steps.
2. Headings have **no** native HTML span equivalent that `Html.toHtml` preserves
   (`RelativeSizeSpan` isn't serialized), so `TextSegmentView` marks heading lines with
   an invisible zero-width-space prefix (`HEADING_1_PREFIX`/`HEADING_2_PREFIX`, H2's
   marker starts with H1's so length-order matters when detecting) instead of relying
   on the span surviving serialization. The `RelativeSizeSpan` + bold `StyleSpan` are
   always *re-derived* from the marker after load/edit, never trusted as the source of
   truth themselves. Bullets, by contrast, use plain `BulletSpan` and round-trip
   natively through `Html`, no marker needed.

**Autosave**: `NoteEditorFragment` debounces saves 500ms after any content/title change
(`scheduleAutoSave`), and force-flushes on `onPause`. A note with `note_id == null` is
not created in the DB until it actually has content (empty title *and* empty segments
→ no-op); a previously-saved note whose content is fully cleared out gets **deleted**
(soft-delete) rather than left as an empty row. Note creation guards against duplicate
concurrent creation with an `AtomicBoolean` (`isCreatingNote`).

## Whiteboard

Custom-drawn `View` (`WhiteboardView`), not a canvas library — touch events become
`Stroke` objects (`List<PointF>`), quadratic Bezier (`Path.quadTo`) smoothing between
points for a natural line, `MotionEvent` historical points consumed on `ACTION_MOVE`
for smoothness during fast strokes. `tool` is an int enum (0=pen, 1=eraser=draws white,
2=highlighter=4x width + alpha 80) rather than a Java enum — chosen so it round-trips
directly through the `strokes.tool` INTEGER column with no extra mapping layer.

Points are packed as raw little-endian float pairs into a BLOB (`StrokeDao.
serializePoints`/`deserializePoints`) — no JSON, minimal per-point overhead.

Undo is a `Deque<String>` of stroke IDs in `WhiteboardFragment`, rebuilt from
`created_at ASC` order on load so reopening a whiteboard preserves undo order across
sessions; undo pops the most recent stroke ID, removes it from the view and deletes it
from `strokes`. "Clear" wipes both the view and all rows for that `whiteboard_id`.
Export renders the view into a `Bitmap` and writes it via `MediaStore` into
`Pictures/Quill/`.

**Known bug**: `WhiteboardFragment.exportWhiteboard()` shows a "Export failed" Toast
unconditionally after the try/catch block, even on the success path — cosmetic (real
success also shows its own "Saved to…" toast first) but worth fixing if that code is
touched again.

## Conventions worth following

- New DB-backed features: add a `data/XRepository.java` following the existing
  callback-based async pattern (`OnXLoaded` functional interfaces), route all I/O
  through `AppExecutors.diskIO()` — don't introduce a second ad hoc threading pattern
  the way the whiteboard code did.
- Model classes (`data/model/*`) are plain field-holder POJOs, no builders/getters —
  keep new ones consistent with that.
- Soft-delete via a `deleted_at`/similar timestamp column, not hard `DELETE`, for
  anything user-facing (matches `notes.deleted_at`).
- IDs are `UUID.randomUUID().toString()` throughout, not autoincrement — consistent
  with the schema anticipating multi-device sync later.
