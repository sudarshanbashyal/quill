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

Design source: the **MSE** Figma file — see [references.md](references.md).

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

**Segment identity is not currently stable across saves** — worth knowing before
building anything that needs to reference "this exact segment" over time (e.g. linking
a flashcard to the Q&A block that produced it; see "Planned" section below).
`NoteEditorView.exportSegments()` always constructs fresh `TextSegment`/`ImageSegment`/
`AudioSegment` instances without ever setting `NoteSegment.id`, so it's always `null`
by the time `NoteRepository.replaceSegmentsSync` runs — which then always takes the
`segment.id != null ? segment.id : UUID.randomUUID().toString()` fallback branch and
mints a brand-new id for every segment, on every single save (i.e. every ~500ms of
editing via autosave). Nothing today depends on a segment id surviving a save, so this
has been harmless so far — but any future feature that does will need this fixed first.

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

## Planned: standardized Markdown note format, Q&A segments & flashcards

**Status: design agreed, not yet implemented.** Tracked in detail as Epic D in
[requirements.md](requirements.md); this section is the "why/how" behind that epic.
Recorded here ahead of the code existing so the reasoning isn't lost between design and
build.

**Motivation**: `SpanSerializer`'s HTML-based encoding (see above) works but needed two
non-obvious workarounds (newline-collapsing, invisible heading markers) to get there.
Markdown expresses the same things natively, is human-readable, and is the closest
thing to a portable, cross-tool standard for notes (Obsidian, Joplin, Bear, GitHub, …).

**Format mapping** (via [Markwon](https://noties.io/Markwon/), an Android Markdown
library with a plugin/AST system suited to the custom block types below):

| Content | Markdown form |
|---|---|
| Heading 1 / 2 | `# text` / `## text` |
| Bold / italic | `**text**` / `*text*` |
| Underline | `<u>text</u>` — raw inline HTML, valid per CommonMark |
| Bullet / numbered list | `- item` / `1. item` |
| Image | `![alt](path)` — native |
| Audio | `<audio src="..." data-duration="...">` — real HTML5 tag, not invented syntax |
| Whiteboard embed | `<img src="thumb.png" data-quill-embed="whiteboard" data-quill-id="...">` — a flattened PNG (`WhiteboardView.exportToBitmap()` already exists) wrapped so it degrades to a plain picture outside Quill but resolves to a tappable live preview inside it |
| Q&A block | fenced block, e.g. `` ```quill-qa:fc_8f3a\nQ: ...\nA: ...\n``` `` — the `:fc_8f3a` suffix is the linked flashcard id once one exists |

This retires both `SpanSerializer` workarounds outright: headings become their own
literal, readable prefix instead of an invisible marker character, and Markdown's
blank-line paragraph rule doesn't have HTML's newline-collapsing ambiguity.

**Open decision** — how much of the storage layer this touches, still to be decided:
- *Minimal*: keep `note_segments` rows as they are; only change what `TextSegment`
  serializes to (Markdown bytes instead of HTML bytes). Smaller, lower-risk.
- *Full*: collapse a whole note into one Markdown document, stored in `notes.
  content_blob` — a column that **already exists in the schema and is completely
  unused today** (nothing reads or writes it). Every segment becomes an inline block;
  `note_segments.position` bookkeeping disappears since order falls out of the
  document's line order. Bigger lift, but this is the version that produces an
  actually-portable `.md` file per note.

**Q&A segment**: a new `NoteSegment.TYPE_QA` / `QASegmentView`, alongside Image/Audio —
a bordered two-field card (plain-text Question, plain-text Answer; no rich text in v1).
Deliberately *not* an Anki-style inline cloze marker (`{{c1::text}}`) wrapped around
arbitrary selected text — Q&A content is meant to be visibly structured in the note
itself, not hidden inside prose.

**Flashcard generation & sync**: "Create/Sync Flashcards" on the note screen turns every
`TYPE_QA` segment into a row in the (already-existing, currently-unused) `flashcards`
table. Requires the segment-identity fix noted above — flashcards link back via a new
`flashcards.source_segment_id`, and that link is meaningless if segment ids don't
survive a save. Two sync modes, user-selectable **per note** (default: Manual):
- **Manual** — nothing happens until the user taps "Sync Flashcards."
- **Automatic** — sync runs on note save, but must never touch a card the user is
  actively reviewing: a review session snapshots its due-card queue at session start,
  and auto-sync only writes to the table, never into an in-progress session's queue —
  otherwise editing a note mid-review could rewrite the card someone's looking at.

Re-syncing a note is an update, not a duplicate-generator: existing linked segments
overwrite only `front`/`back` text on their flashcard, never the SM-2 scheduling state
(`interval`/`repetitions`/`easiness`/`next_review`). A flashcard whose source segment
disappeared (block deleted from the note) is left alone rather than silently deleted —
someone's review progress shouldn't evaporate because a note got tidied up; surface it
as orphaned instead and let the user decide.

## Planned: Quizzes — scored, auto-graded, no free-text matching

**Status: design agreed, not yet implemented.** Tracked as Epic E in
[requirements.md](requirements.md).

Two tempting approaches were deliberately rejected up front:
- **Free-text typed answers graded by string match** — real answers vary too much
  ("Personal Computer" vs. "PC" vs. "pc") to grade reliably by comparing strings alone.
- **AI-generated distractors/questions** — quality is unpredictable and depends heavily
  on what a given user's cards actually look like, and calling out to an AI (especially
  a hosted one) undercuts the offline-first/privacy-conscious positioning the one-pager
  leads with.

Instead, quizzes are built entirely from **auto-gradable question types generated
locally from the flashcard pool**, so no string matching or AI is ever required:
- **MCQ via cross-card distractors** — for a card's question, sample 3 wrong-but-
  plausible options from *other* flashcards' `back` text in the same scope (same note,
  falling back to the same collection if there aren't enough sibling cards). Works well
  because a deck is usually topically coherent already — the same trick Quizlet's Test
  mode uses.
- **True/False fallback** — show a Q/A pair straight, or swap in another card's answer,
  ask correct/incorrect. Needs only 2 cards (vs. ~4 for a good MCQ), so it covers
  notes/collections too small for MCQ.
- **Matching mode** (later, optional) — N questions + N shuffled answers to pair up.

**Flashcards and quizzes share one data model, not two.** `flashcards` stays the single
source of truth for Q/A content (see the Epic D section above); two separate *modes*
sit on top of it:
- **Review** — spaced repetition, self-graded (Again/Hard/Good/Easy), private, drives
  SM-2 scheduling. No matching problem here at all — the human is always the judge.
- **Quiz** — user picks a scope (a note / a collection / a tag), the app auto-builds N
  MCQ/True-False questions from that scope's flashcards, and tracks a score.

New schema needed (none of this exists yet): something like
`quiz_attempts(id, scope_type, scope_id, score, total, taken_at)`, optionally
`quiz_attempt_answers(attempt_id, flashcard_id, was_correct)` if per-question review
after a quiz is wanted.

## Material 3 UI migration

**Status: largely done.** Theme, cards, chips, dialogs, buttons, checkboxes and text fields
are all on real Material 3 components. Tracked as Epic H in [requirements.md](requirements.md).

**Material 3 is now the standard for all UI in this app** — see "Conventions worth following"
below. Anything new that renders should be an MDC widget, not a hand-drawn `GradientDrawable`
and not a framework/AppCompat widget.

**Motivation**: the app's theme (`Theme.Quill` in `values/themes.xml`) already extended
`Theme.MaterialComponents.*` and used the `com.google.android.material` dependency
(already at `1.10.0`, which supports Material 3 — no version bump needed), but an
actual code audit found only **one** real Material widget in use anywhere
(`FloatingActionButton` in `fragment_home.xml`). Everything that visually looks like a
Material component — note-row cards, collection cards, pinned-note cards, tag chip
pills — is hand-rolled: a plain `View`/`TextView` plus a manually constructed
`GradientDrawable` for fill/corners, manual elevation, no ripple. This is downstream of
the inflater workaround below — those views are built in Java rather than XML, so
they never went through the usual "style + Material widget" path.

**What's done**:
- `themes.xml`: `Theme.Quill` extends `Theme.Material3.Light.NoActionBar`. The existing
  8-color palette (`colors.xml`) remapped onto M3 roles (`colorPrimaryContainer`/
  `colorOnPrimaryContainer` etc., using `brand_purple_light`/`text_primary` for the
  container roles that didn't exist as a concept in the old theme). M3 dropped
  `colorPrimaryVariant`, so the status bar color is a direct `@color/brand_purple_dark`
  reference instead of `?attr/colorPrimaryVariant`.
- `TagChipView` → `com.google.android.material.chip.Chip`, non-checkable with
  icons/checkmark hidden and `setEnsureMinTouchTargetSize(false)` so it doesn't balloon
  past the old pill's footprint. Pill shape comes from a `RelativeCornerSize(0.5f)` on the
  shape appearance model, *not* the deprecated `setChipCornerRadius` + a 999dp sentinel
  dimen (that dimen is gone).
- `NoteRowView`, `CollectionCardView`, `PinnedNoteCardView` → `MaterialCardView`, via the
  shared `NoteRowView.applyFlatCardStyle(card, cornerRadiusRes)`. Ripple now comes from the
  card being clickable, which replaced `?attr/selectableItemBackground` on note rows.
  `HomeAdapter`'s collection holder retints with `setCardBackgroundColor` instead of poking
  a retained `GradientDrawable`.
- **All 13 dialog sites** → `MaterialAlertDialogBuilder`. Most were using the *framework*
  `android.app.AlertDialog`, which ignores app theming outright — that's why dialogs stayed
  visibly Material 2 (square corners, ALL-CAPS buttons) even after the theme was switched.
  This was the single highest-impact change of the migration.
- Dialog widgets that are built in code: `Button` → `MaterialButton`, `CheckBox` →
  `MaterialCheckBox`, `EditText` → outlined `TextInputLayout` via the new
  `util/TextFieldUtils.outlinedField(context, hintRes)`.

**Non-obvious things learned doing it** — worth not rediscovering:
- The M3 theme's default `materialCardViewStyle` is the *outlined* one, so
  `applyFlatCardStyle` zeroes stroke and elevation explicitly rather than inheriting. The
  MSE Figma draws every card as a flat grey/pastel fill with no shadow, hence
  `surface_container` `#F5F6FA` (sampled from the design) and elevation 0 everywhere.
- `TextInputLayout` **is a `LinearLayout`** and casts its child's params in `addView()`.
  Giving the `TextInputEditText` plain `ViewGroup.LayoutParams` compiles fine and crashes at
  runtime with `ClassCastException`. It must get `LinearLayout.LayoutParams`. This actually
  shipped and crashed the app on the emulator before being caught — build-verification alone
  would have missed it.
- `TextInputLayout`'s no-arg constructor gives the *filled* (underlined, Material-2-looking)
  variant. The outlined one needs an explicit defStyleAttr:
  `new TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle)`.
- The child `TextInputEditText` must be constructed from `layout.getContext()`, not the outer
  context — TextInputLayout wraps its own themed context and the box styling is silently lost
  otherwise.
- Building Material widgets programmatically works fine, so the LayoutInflater bug that forces
  this codebase to build views in code (see `NoteRowView`) was *not* an obstacle to the
  migration.

**Decision: the color-swatch picker stays custom.** Material 3 has no color-picker component,
so `TagPickerDialog`'s grid of `GradientDrawable` circles would gain nothing but indirection
from being wrapped in a Material widget. This is the one deliberate exception to "use MDC
widgets" and it's documented in the class comment too.

**Remaining / not verified**:
- Visual QA covered home, collection detail, note editor, the FAB + its expanding menu, and
  the tag picker dialog. **Not** eyeballed: the create/rename-collection dialogs (including
  the new `CollectionDialogs.inset()` wrapper), `AddExistingNotesDialog`, `RecordingDialog`,
  the whiteboard dialogs, and the image/audio source pickers. All build clean and share the
  now-fixed `TextFieldUtils` path, but they haven't been seen running.
- Design-fidelity gaps that are *not* M3 issues and were deliberately left alone: the home
  header gradient is `#E8E3FC→#D2C9FB` and `wrap_content`, where the Figma has a stronger
  `#CCC6FA` filling ~21% of screen height; and the search fields in `fragment_home.xml` /
  `fragment_collection_detail.xml` are still plain XML views rather than `TextInputLayout`.

## Conventions worth following

- **All UI is Material 3 — no exceptions without a recorded reason.** Every new or edited
  view uses a `com.google.android.material.*` widget rather than a framework/AppCompat one
  or a hand-drawn `GradientDrawable`. Concretely:
  - cards/surfaces → `MaterialCardView` (via `NoteRowView.applyFlatCardStyle`)
  - dialogs → `MaterialAlertDialogBuilder`, never `android.app.AlertDialog` or
    `androidx.appcompat.app.AlertDialog` (the framework one ignores the app theme entirely)
  - text input → `TextFieldUtils.outlinedField(...)`
  - buttons → `MaterialButton`; checkboxes → `MaterialCheckBox`; pills/tags → `Chip`
  - colors come from the M3 role attrs (`?attr/colorPrimaryContainer`, `colorSurface`, …)
    or a named color in `colors.xml` — not a literal hex in Java
  The one standing exception is the color-swatch picker (no M3 equivalent exists); if
  another exception is needed, write down why next to it like that one is.
  The rich-text body in `TextSegmentView` also stays a plain `EditText` — it's an editing
  surface, not a form field, and boxing it would be wrong.
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
