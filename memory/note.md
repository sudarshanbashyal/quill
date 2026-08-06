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
hand-written schema and raw SQL everywhere. `DATABASE_VERSION = 4`. `onUpgrade` is still
**destructive** (drops every table and recreates) for every step *except* v3 → v4, which
migrates in place with `ALTER TABLE` because that change was purely additive (whiteboards
gained title/timestamps). The rest still needs real migrations before this ships with user
data worth keeping.

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
- `notes(id, collection_id, title, content_blob, created_at, updated_at, deleted_at,
  pinned_at, …)` — soft-deleted via `deleted_at` (never hard-deleted by app code).
  `content_blob` holds the note's whole body as one UTF-8 Markdown document; see "Note
  content model" below. Also carries `location_lat/lng/name` columns that nothing in the
  app populates yet.
- `note_segments(id, note_id, type, file_path, transcript, duration_ms, width,
  created_at)` — **media asset registry only** since schema v3. No `position` (order
  lives in the Markdown document) and no `text_content` (text *is* the document).
- `whiteboards(id, note_id, title, created_at, updated_at)` — `note_id` is **nullable**: a board
  created from Home stands on its own, one opened from a note belongs to it. `strokes(id,
  whiteboard_id, author_id, tool, color, width, points_blob, created_at)`.
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
- `notes_fts` FTS5 virtual table — now a **standalone** `fts5(note_id UNINDEXED, title,
  body)`, maintained by `NoteRepository` on every save and delete. (It was previously
  declared `content='notes'` with a `body` column that doesn't exist on `notes`, so it
  could never have been populated at all.) Still wrapped in try/catch since some
  emulator SQLite builds lack FTS5 — writes are guarded the same way. **Nothing queries
  it yet**: search in `HomeFragment` is still plain in-memory filtering over the loaded
  note list. The index is ready; the query path is the remaining work.

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
- `QaSegment` — two `Spannable`s, question and answer (see "Q&A blocks" below)

`NoteEditorView` (a `LinearLayout`) renders one `BaseSegmentView` child per segment
(`TextSegmentView` / `ImageSegmentView` / `AudioSegmentView` / `QASegmentView`) and stitches them
together to *feel* like one continuous document: typing Enter, inserting an image/audio
mid-paragraph, or backspacing at position 0 of a segment all route through
`BaseSegmentView.SegmentCallback` (`onRequestSplitAt` / `onRequestDelete` /
`onRequestMergeWithPrevious`) so `NoteEditorView` can split/merge/delete segments and
refocus the right one. This is why inserting an image "in the middle of typing" works —
the focused `TextSegmentView` is split into a before/after pair around the new
image/audio segment.

**Segments are a view concept, not a storage one.** They're what the note's Markdown
document parses into. Persistence (since schema v3):

- The whole body is one Markdown document in `notes.content_blob`.
- `note_segments` survives only as a media asset registry — one row per image/audio,
  holding what a Markdown link has nowhere to put (file path, display width, duration,
  transcript). Rows are referenced from the document by id, e.g.
  `![](quill://image/3f2a…)`, so moving media on disk can never invalidate a document.
- `NoteRepository.replaceMediaAssetsSync` deletes-then-reinserts a note's asset rows on
  every save — no diffing. Orphaned files (referenced before, not now) are deleted from
  disk *after* the transaction commits.

**Serialization** (`data/serialization/`):
- `MarkdownSerializer` — one text segment's `Spannable` ↔ Markdown.
- `NoteDocument` — the segment list ↔ the whole document, plus plain-text/preview
  projections used for the note list and the FTS body.
- `HeadingMarker` — shared so the data layer doesn't have to import a `View`.

Three things about the format that are non-obvious:
1. **Italic is `_`, not `*`.** Markdown requires properly nested markers, so a format
   ending inside another forces a close-then-reopen at the boundary; with `*` for italic
   that emits ambiguous `****`/`*****` runs no reader can split back correctly. Mixing
   the two marker characters keeps every such run unambiguous. Cost: literal underscores
   get escaped (`snake\_case`), which still renders as a plain underscore anywhere.
2. **The decoder coalesces abutting same-format spans.** Without it, that same
   close-then-reopen hands the editor two touching bold spans where it had one, and the
   fragmentation compounds on every save.
3. **Headings still use the zero-width-space marker in memory** (`HeadingMarker`), even
   though they're stored as a real `#` prefix. The marker survives the editor moving
   line content around — splitting a segment to insert an embed, merging on backspace,
   copying into a builder for export — whereas a size span has to be re-derived and can
   be clobbered by every such move. `RelativeSizeSpan` + bold are always re-derived from
   the marker, never trusted as the source of truth. Bullets need no marker: the
   serializer reads `BulletSpan` directly.

An embed whose asset row is missing is dropped on parse, and the text either side closes
up into one segment — preserving the invariant that two text segments are never
adjacent, which is what makes splitting the document back into segments unambiguous.

**Autosave**: `NoteEditorFragment` debounces saves 500ms after any content/title change
(`scheduleAutoSave`), and force-flushes on `onPause`. A note with `note_id == null` is
not created in the DB until it actually has content (empty title *and* empty segments
→ no-op); a previously-saved note whose content is fully cleared out gets **deleted**
(soft-delete) rather than left as an empty row. Note creation guards against duplicate
concurrent creation with an `AtomicBoolean` (`isCreatingNote`).

**Segment identity is stable across saves** (fixed 2026-07-28; it previously minted a
fresh UUID on every autosave). `BaseSegmentView` now owns the id, and
`NoteEditorView.exportSegments()` copies it onto the exported model, so a media
segment's `quill://` reference keeps pointing at the same asset row for the life of the
note. The Markdown migration forced this — without stable ids the document's embed
references would break on every save — and it also removes the prerequisite that was
blocking flashcard↔segment linking (see "Planned" below).

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

(The old "Export failed" toast that fired unconditionally after the try/catch — success path
included — was fixed on 2026-08-03.)

**Whiteboards are first-class, not note-attached** (2026-08-03). Home lists them in their own
section between Collections and Notes, and `whiteboards.note_id` is nullable so a board created
from the FAB has no parent note. Two decisions behind that section:

- **The card shows a glyph and a stroke count, not a thumbnail.** A real preview would mean
  loading every board's strokes just to draw Home, and there's no cover image to cache instead.
- **Deleting a board is a hard delete**, against the app's soft-delete convention, because there
  is no whiteboard trash surface — a soft-deleted board would just be unreachable rows. Strokes
  go first in the same transaction; they carry a foreign key onto the board.

`WhiteboardRepository` is the Home-side entry point and follows the normal `AppExecutors` +
callback pattern. `WhiteboardFragment` still uses `WhiteboardDao`/`StrokeDao` on ad hoc threads
(Epic A), with one deliberate exception: the initial `whiteboards` insert stays **synchronous**,
because `strokes` has a foreign key onto that row and the stroke writes run on their own unordered
threads. `updated_at` is bumped on draw/undo/clear so the Home section sorts by real recency.

## Markdown note format — implemented; flashcards still planned

**Storage: done (2026-07-28).** The "Full" option below was chosen: a note is one
Markdown document in `notes.content_blob`, `note_segments` demoted to a media asset
registry. See "Note content model" above for how it actually works, and
[conversation.md](conversation.md) for the decision and what was traded away.

**Format mapping as built:**

| Content | Markdown form |
|---|---|
| Heading 1 / 2 | `# text` / `## text` |
| Bold / italic | `**text**` / `_text_` (underscore — see "Note content model") |
| Underline | `<u>text</u>` — raw inline HTML, valid per CommonMark |
| Bullet list | `- item` |
| Image | `![](quill://image/<asset-id>)` |
| Audio | `![audio](quill://audio/<asset-id>)` |
| Q&A block | fenced ` ```quill-qa `, question, `---`, answer, ` ``` ` |

Note this differs from the original sketch: embeds reference an **asset id**, not a file
path or an HTML tag, because the metadata (width, duration, transcript) has to live on a
row anyway and a path in the document breaks the moment media moves. Exporting to
portable Markdown becomes a matter of rewriting `quill://` URIs to relative paths.
Markwon was **not** adopted — it renders, but doesn't help with editing, and the
round-trip is hand-written in `MarkdownSerializer`.

**Still to design/build** (Epic D): the whiteboard embed, reserved as
`![whiteboard](quill://whiteboard/<id>)`.

**Flashcard generation & sync**: "Create/Sync Flashcards" on the note screen turns every
`TYPE_QA` segment into a row in the (already-existing, currently-unused) `flashcards`
table. Flashcards link back via a new `flashcards.source_segment_id`; the segment-
identity fix this depended on has since landed (see "Note content model"), so this is no
longer blocked. Two sync modes, user-selectable **per note** (default: Manual):
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
- **A drawable XML namespace typo fails completely silently.** `bg_home_header.xml` declared
  `xmlns:android="http://schemas.android.com/res/android"` — missing the `apk/`. aapt reports
  no error and the build is clean, but every `android:` attribute lands in an unrecognized
  namespace, so the shape inflates with no shape type, no colour and no gradient and paints
  *nothing*. The home header gradient had therefore never rendered. Worth grepping for if any
  other drawable ever "just doesn't show up":
  `grep -rl 'schemas.android.com/res/android' app/src/main/res/` (should return nothing).

**Decision: the color-swatch picker stays custom.** Material 3 has no color-picker component,
so `TagPickerDialog`'s grid of `GradientDrawable` circles would gain nothing but indirection
from being wrapped in a Material widget. This is the one deliberate exception to "use MDC
widgets" and it's documented in the class comment too.

**Design-fidelity fixes done alongside the migration**:
- **Home header gradient now actually renders** — see the namespace bug above; that was the
  real reason it looked flat, not the colour values. Colours were also re-sampled from the
  Figma (`#CAC3FA` at the top fading to `#EFECFD`, via `header_gradient_end`/`_start` — note
  `angle=90` makes `_start` the *bottom* stop).
- **The rounded edge belongs to the content sheet, not the header.** The header gradient is a
  plain full-bleed rectangle (`bg_home_header`, no `<corners>`) that runs behind everything;
  the content below sits on `bg_content_sheet`, which has rounded *top* corners and is pulled
  up over the gradient's bottom edge by a negative `content_sheet_overlap` margin. That's how
  the Figma layers it (gradient block 177dp tall, sheet starting at 120dp), and it curves the
  right way round — giving the header itself rounded bottom corners curves the edge upwards,
  which is wrong.
- **Home greeting uses Playfair Display**, the display serif from the Figma — bundled under
  `res/font/` (OFL, licence copy in `/licenses/`). Upstream ships only variable fonts, so
  `font/playfair_display.xml` selects real weights with `fontVariationSettings` instead of
  letting Android synthesise a fake bold off the 400 default. Needs API 26, which is minSdk.
- **`app_background` is now white** (`#FFFFFF`, was `#F8F7FC`), matching the Figma's white
  page. The old off-white sat almost on top of `surface_container` (`#F5F6FA`), so note rows
  and collection cards barely separated from the background; on white they read as intended.
- **Search fields** in `fragment_home.xml` and `fragment_collection_detail.xml` → outlined
  `TextInputLayout` with `app:hintEnabled="false"` so the hint stays static placeholder text
  inside the box (as the Figma draws it) rather than animating into a floating label. The
  inner `TextInputEditText` kept the id `search_input`, so `HomeFragment` and
  `CollectionDetailFragment`'s `findViewById` needed no change. In
  `fragment_collection_detail.xml` the ConstraintLayout references had to move to the new
  wrapper id `search_field` — ConstraintLayout can only constrain its own direct children.
  `bg_search_field.xml` is now unused and deleted.

**Remaining / not verified**:
- Visual QA covered home (incl. the header and search field), collection detail, note editor,
  the FAB + its expanding menu, and the tag picker dialog. **Not** eyeballed: the
  create/rename-collection dialogs (including the new `CollectionDialogs.inset()` wrapper),
  `AddExistingNotesDialog`, `RecordingDialog`, the whiteboard dialogs, and the image/audio
  source pickers. All build clean and share the now-fixed `TextFieldUtils` path, but they
  haven't been seen running.

## Camera capture (FileProvider)

`ImageEmbedder.openCamera()` writes the capture target into `getFilesDir()/images` and hands
the camera app a `content://` URI for it via `FileProvider`. That needs three things wired
together, and **the app had none of them** — tapping "Take photo" crashed outright:

- `<provider android:name="androidx.core.content.FileProvider">` in `AndroidManifest.xml`,
  authority `${applicationId}.fileprovider`. It must match what the Java builds
  (`getPackageName() + ".fileprovider"`) or `FileProvider.getUriForFile` throws.
- `res/xml/file_paths.xml` with a `<files-path>` covering `images/`. If the path isn't
  covered, `getUriForFile` throws "Failed to find configured root that contains …".
- `FLAG_GRANT_WRITE_URI_PERMISSION` on the intent, so the camera process can actually write
  to the URI (without it the capture silently returns `RESULT_CANCELED`).
  `FLAG_GRANT_READ_URI_PERMISSION` is also set — the read grant is implicit today but Android
  logs that implicit grants for `ACTION_IMAGE_CAPTURE` end in Android 18.

**Why it crashed rather than failing softly**: both `getUriForFile` failure modes throw
`IllegalArgumentException`, but `openCamera()` only caught `IOException`, so it propagated to
the UI thread. The catch now also handles `ActivityNotFoundException` (no camera app, common
on bare emulator images) and routes it to `onImageFailed()` like every other failure.

## Image pipeline

`util/BitmapUtils` is the single place images are decoded, and both sources (camera and
gallery) funnel through `ImageEmbedder.deliver()`.

**Orientation is normalised once, on ingest.** Cameras don't rotate pixels — they record
how the phone was held in an EXIF tag and leave the sensor data as-is, and
`BitmapFactory` ignores that tag, so portrait photos decoded sideways. Gallery picks had
the identical bug, because importing copies the file byte-for-byte, tag included.
`normaliseStoredImage()` rotates and rewrites the file upright (bounded to 2048px on the
long edge) so nothing downstream — inline segment, viewer, export — needs to know EXIF
exists. Honouring the tag at draw time instead would have to be repeated correctly in
every consumer, including ones added later. An already-upright, already-small image is
left byte-identical rather than needlessly re-encoded.

**Everything decodes sampled.** `ImageSegmentView` previously called
`BitmapFactory.decodeFile` at full resolution — tens of megabytes of heap for something
drawn a few hundred pixels wide, and several images in one note was an OOM waiting to
happen. Inline images are also capped at `note_image_max_height` (280dp) with
`adjustViewBounds`, and centred via `FIT_CENTER` **plus** container gravity (with
`adjustViewBounds` the ImageView shrinks to the scaled image's width, so centring the
content inside the view isn't enough on its own).

**Viewer & export.** Tapping an inline image opens `ImageViewerDialog` — a bare `Dialog`
over a `#E6000000` scrim, deliberately not a MaterialAlertDialog, whose inset card and
surface colour fight an edge-to-edge image. Neither action closes it: save reports back
through `showMessage()`, and delete closes only once the confirmation is accepted.
Feedback has to be a Snackbar on the *dialog's own* root — one on the editor's root view
sits behind this window and is never seen.

`util/ImageExporter` copies into `Pictures/Quill` via MediaStore under a
`Quill_<timestamp>.jpg` display name (not the internal `img_<uuid>.jpg`, which is what
the user would otherwise see in their gallery). Below API 29 that needs
`WRITE_EXTERNAL_STORAGE` — declared with `maxSdkVersion="28"` and requested by
`NoteEditorFragment`, since a segment view can't ask for a runtime permission. That's
why the request routes up through `SegmentCallback.onRequestExport`, with the outcome
handed back down so the view can report it where the user is actually looking.

## Keyboard handling in the note editor

`targetSdk` is 35+, so **the system enforces edge-to-edge and the window never resizes
for the IME** — `adjustResize` cannot work here regardless of the manifest, and the
editor has to reserve the keyboard's space itself.

`NoteEditorFragment.reserveKeyboardSpace()` does that with **bottom padding on the
fragment root**. The distinction from the earlier `translationY` approach matters: a
transform moves pixels but not layout bounds, so the `ScrollView` (constrained above the
formatting toolbar) kept its full-height bounds and its viewport nominally extended
behind the keyboard. Android's own "reveal the focused view" pass then found a tapped
segment already inside those bounds and correctly concluded no scrolling was needed —
which is exactly why segments near the end of a note were never revealed. Padding is a
layout change, so the toolbar lands above the keyboard by its existing constraint and
the viewport becomes truthful.

`revealFocusedInput()` then re-runs the reveal after the resize, using
`getFocusedRect()` (the caret line, not the whole field — revealing the whole field
overshoots and pushes the title off screen). It has to be explicit because the tap that
focused the input happened while the keyboard was down, and with no window resize
`ViewRootImpl`'s own keep-focus-visible pass never runs.

**Do not reintroduce custom scroll arithmetic here.** A long earlier attempt at that
(≈10 rounds, all reverted) is recorded in [conversation.md](conversation.md).

## Formatting toolbar

`FormattingToolbarController` builds nine `FormattingButtonView` items into the
`formatting_toolbar` LinearLayout with equal weights, so they divide the bar's full width
and the row can never overflow (no scroll container needed).

Each item is an icon plus a small primary-coloured dot that appears when the format is
active. The dot carries the state rather than M3's usual tonal/filled selected button:
the bar sits directly against the keyboard, and a row of filled pills there reads as a
second keyboard rather than as part of the app. That's also why the item is a composite
view rather than a bare `MaterialButton` — the button has no way to stack an indicator
under its icon. The whole weighted slot is the touch target, so the glyph can be small.

**Toolbar state comes from three different sources**, which is easy to get wrong, and is
why it's carried in a `FormattingState` value object rather than a positional argument
list:

1. bold/italic/underline — a *pending typing mode*;
2. heading and bullet — properties of **the line the caret is in**;
3. what's offered at all (`headingsAllowed`, `embedsAllowed`) — a property of **the field
   the caret is in**. A Q&A field refuses headings and embeds, so those controls grey out
   (`FormattingButtonView.setAvailable`, which also clears the marker so a heading dot
   can't be left lit where headings don't exist).

(2) and (3) both go stale when the caret moves, so `RichTextField` reports **both**
`onSelectionChanged` *and* `onFocusChanged`. Focus is not redundant: moving between fields
often lands the caret at an offset it already had, and Android fires no selection callback
when the value doesn't change — which is exactly how stepping out of a Q&A block used to
leave headings and embeds greyed out. Both route up as
`NoteEditorView.SelectionChangeListener`, deliberately separate from
`ContentChangeListener`, since a caret move is not an edit and must not schedule an
autosave.

## Q&A blocks

Design source: the MSE Figma file's **QA** frame. A tonal rounded card (`surface_container`,
the same fill as note rows) holding a muted question line above an answer indented behind a
green vertical rule (`#30B488`, sampled from the frame's separator asset).

**`RichTextField` is the reusable piece.** All the formatting behaviour — inline styles,
bullets and continuation, heading markers, active-format typing, caret/focus reporting —
used to live inside `TextSegmentView`. A Q&A needs the same thing in *two* fields, so it was
extracted: `TextSegmentView` now wraps one `RichTextField`, `QASegmentView` wraps two. The
rules are subtle enough (derived heading spans, the identity-tracked restyle) that a second
copy would have drifted.

**Capabilities belong to the field.** `RichTextField.setHeadingsAllowed(false)` is how a Q&A
refuses headings; the toolbar greys controls by asking the focused field and never learns
what a Q&A is. Refusal is real, not cosmetic — `applyHeading` is a no-op on such a field, so
a stray call can't sneak a heading in. Embeds and the Q&A button itself are gated on the
same flag, which also stops a Q&A nesting inside another.

**Why no blocks inside:** a Q&A is one atomic question and one atomic answer destined to
become a flashcard, not a place to nest document structure.

**Deletion is long-press only.** Backspacing at the start of the line below a block used to
delete it — one keypress, no confirmation, no undo, and it applied to photos and typed-out
answers alike. That's gone; `onRequestMergeWithPrevious` now does nothing when the previous
segment is a block. **Known rough edge**: on a Q&A the long-press target is the card's
chrome (padding, and the ~27dp gutter left of the answer), because a long-press inside
either field has to remain text selection — that's how you select part of an answer to bold
it. Image and audio don't have this problem, having no editable children.

**Hints**: only the note's *last* text segment shows "Write something…". The empty segments
a block insert leaves behind mid-note are structural gaps, not invitations; repeating the
prompt down the page read as clutter. `NoteEditorView.updateHints()` re-points it from
`insertSegment`/`removeSegment`, the two places the list can change shape.

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
