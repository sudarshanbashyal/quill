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

Three of them are **top-level**, reachable from a `BottomNavigationView` in `activity_main`:
`homeFragment`, `flashcardDecksFragment` and `quizzesFragment`. The bar hides on every other
destination. The menu's item ids *are* the destination ids, which is what lets `NavigationUI`
own selection and the back stack.

- `HomeFragment` (start destination) — collections grid + notes list/search + up to
  `NoteRepository.MAX_PINNED_NOTES` (3) pinned-note cards.
- `FlashcardDecksFragment` — one row per note that has flashcards (see "Flashcards").
- `FlashcardsFragment` (args: `note_id` required) — the review session for one note's deck.
- `QuizzesFragment` — one row per note that has a quiz (see "Quizzes").
- `QuizDetailFragment` (args: `quiz_id` required) — attempt history and the Start button.
- `QuizSessionFragment` (args: `quiz_id` required) — the timed run and its marked paper.
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
`TagRepository`, `StrokeRepository`, `WhiteboardTextRepository`, …) wrap `AppDatabase` +
raw `Cursor`/`ContentValues` calls, exposing async callback-based APIs (`OnNoteLoaded`,
`OnNotesLoaded`, …) rather than LiveData/Flow. Every one of them is constructed the same
way, `new XRepository(context)`, and resolves `AppDatabase` and `AppExecutors` itself.

Notes and flashcards additionally have **interfaces** — `NoteStore` and `FlashcardStore`,
obtained from `Repositories.notes(context)` / `.flashcards(context)`. Those hold only the
methods a screen may call; the blocking `Sync` variants are deliberately off them, so a
caller holding the concrete `NoteRepository` is visibly doing something a screen normally
should not. No other repository has an interface yet, and that is on purpose — see R6 in
`refactoring_plan.md`.

**Threading**: `AppExecutors` is a hand-rolled singleton with one single-thread
`ExecutorService` for all disk I/O plus a main-thread `Handler` for callback delivery.
Deliberately single-threaded so concurrent repositories never fight over SQLite's
single writer lock. `NoteRepository`/`CollectionRepository`/`TagRepository` all go
through it consistently.
There is **one** threading pattern; the whiteboard's ad hoc `new Thread(...)` per database
call was the exception and is gone (R4, 2026-08-23).

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
- `flashcards(id, note_id, source_segment_id, front, back, …)` + SM-2 columns
  (`interval`, `repetitions`, `easiness`, `next_review`, `last_reviewed_at`) — live since
  schema v4, written by `FlashcardRepository`.
- `quizzes(id, note_id, created_at)` and `quiz_attempts(id, quiz_id, score, answered,
  total, status, started_at, finished_at)` — schema v5, written by `QuizRepository`. See
  "Quizzes" for why a quiz holds no questions.

Present in schema, **not wired to any repository/DAO/UI yet** (forward-looking
scaffolding, not dead code to delete casually — check before assuming it's unused):
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

A note's body is a **list of `NoteSegment`** (`data/model/`), not one big
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

**The canvas is ten screens each way, and points are canvas coordinates** (2026-08-07). One finger
draws, two fingers pan; the window is moved with the View's own `scrollTo`, so `onDraw` receives an
already-offset canvas and strokes are simply drawn where they live. Touch coordinates are converted
on the way in (`worldX`/`worldY` add the scroll offset) — before this they were stored raw, which
also meant a board drawn on one screen size opened misaligned on another.

Three consequences worth knowing:

- **The canvas is a fixed square measured from the display's shorter edge**, not from the view.
  Sizing it from the window (`getWidth() * CANVAS_SCREENS`, the first version) made every bound
  move on rotation: ink drawn mid-canvas in portrait sat below the bottom edge of the landscape
  canvas, out of reach of any scroll position, so the drawing vanished when the phone turned. The
  shorter display edge is the one dimension a rotation doesn't change.
- **Bounded, not infinite**, because there is no zoom — an endless canvas has nowhere to see the
  whole drawing from, so ink panned away from would be findable only by luck. `CANVAS_SCREENS = 10`
  is the one number to change.
- **A board opens in the middle of the canvas**, not at a corner, so there is room to work in every
  direction. An empty board and the Centre button with nothing drawn both go there.
- **Opening a board centres on its ink** (`loadStrokes` → `centreOnContent`, deferred to
  `onSizeChanged` when it runs before layout). Otherwise a board drawn far from the origin reopens
  on blank canvas. Boards drawn before this change are unaffected: their ink is a screen wide at
  the canvas origin, which just clamps the window to that corner.
- **Export covers the whole drawing, not the window** — the ink's bounding box plus padding, scaled
  down if its longest side exceeds `MAX_EXPORT_PX`. Exporting the viewport would silently crop.
  Eraser strokes are excluded from the bounds: white on white can't extend what's visible.

The second finger discards the stroke the first one started, so a two-finger pan leaves no stray
tick, and panning holds until every finger lifts. Covered by `WhiteboardViewPanTest` — instrumented
because the behaviour is multi-pointer `MotionEvent` handling and the emulator refuses injected
multi-touch (SELinux blocks `/dev/input` writes; `adb shell input` is single-pointer).

**Position indicators**: the framework's own scrollbars, down the right edge and along the bottom.
A custom `View` gets them for free by declaring `android:scrollbars` and reporting three numbers per
axis (`computeHorizontalScrollRange/Offset/Extent` — range is the canvas, extent the window), so
there is no hand-drawn indicator to keep in sync. They fade when the canvas stops moving;
`awakenScrollBars()` in `panBy`/`centreOnContent` is what brings them back, since `scrollTo` alone
doesn't. The overrides are public rather than protected so tests can read the geometry.

**Stylus** (2026-08-07): drawing with a pen always worked — Android delivers stylus input as
ordinary touch events — but nothing *distinguished* a pen, which is what was missing:

- **Palm rejection**: while a stylus is drawing, extra pointers are ignored. Before this, the hand
  resting on the screen read as a second finger, which cancelled the stroke and started a pan.
- **The eraser end erases**, whatever the rail has selected, via `TOOL_TYPE_ERASER` or a barrel
  button in `getButtonState()` — pens report it either way.
- **Pressure scales the stroke width**, clamped to 0.5–1.5× and neutral at the 1.0 a firm touch
  reports. Applied once at stroke start, not per point: a stroke carries a single width in the
  database and `points_blob` is x/y pairs only, so per-point pressure means changing the storage
  format. Finger pressure is deliberately ignored — it means something different.

**The Move tool** (`setPanTool`) hands single-finger drags to the canvas instead of the pen. It is
deliberately *not* a `TOOL_` constant: that value is written into every stroke row, and moving the
canvas produces no stroke. Two-finger panning still works whatever tool is selected, so Move is for
working one-handed rather than the only way around. A pan tracks the midpoint of two pointers but a
single pointer directly, which is why `oneFingerPan` exists — reading one finger as the midpoint
mid-gesture would jump the canvas.

**Paper styles** (2026-08-07): plain white, warm off-white, or dotted, stored per board on
`whiteboards.background` (schema v8, additive), with the last choice remembered as the default for
*new* boards in `WhiteboardPreferences` — existing boards keep their own, so changing the preference
never repapers old work. The helper is shared because boards are created from **two** places:
Home's FAB via `WhiteboardRepository.createWhiteboard` (the path actually used) and
`WhiteboardFragment` when it opens without an id. Teaching only the fragment about the preference
left FAB-made boards silently white. The colour is a `drawColor`; the dots are placed on
a grid in **canvas** coordinates so they stay under the drawing while you pan, and only the ones in
the visible rectangle are drawn, so the cost is a screenful however big the canvas is. Export
carries the paper too.

**This forced the eraser to become a real eraser.** It used to paint opaque *white strokes* — which
is invisible on a white board, and is why it survived this long. On any other paper it would be a
white smear. Ink is now drawn into its own layer (`canvas.saveLayer` in `drawStrokes`) and the
eraser uses `PorterDuffXfermode(CLEAR)`, so it clears back to whatever paper is underneath. The
paper is drawn *before* the layer, or CLEAR would punch through that too. Old eraser strokes, saved
as white, erase properly under the new rule — nothing needed migrating. Covered by
`WhiteboardBackgroundTest`, which asserts on exported pixels: no pure white may appear on a warm
board after erasing.

**`updated_at` is only bumped by real changes** (2026-08-07). Both the note editor and the
whiteboard save on pause whether or not anything was touched, and both save paths wrote
`updated_at` unconditionally — so *opening* a note or a board and backing out reported "Updated
now" and jumped it to the top of Home. `NoteRepository.saveNote` now compares the incoming title
and markdown against what is stored and returns early when they match (done there, not in the
editor, because the markdown is already built on that thread and it fixes every caller);
`WhiteboardFragment.saveTitle` keeps the loaded title and skips the write when it is unchanged.
The note check also verifies the row is indexed, because `createNote` writes no `notes_fts` row —
skipping the first save on an untouched new note would otherwise leave it unsearchable.

**Typed text** (2026-08-07) lives in its own table, `whiteboard_texts(id, whiteboard_id, author_id,
x, y, text, color, size, created_at)` — schema v7, added through `ensureAdditiveSchema`'s
CREATE-TABLE-IF-NOT-EXISTS path, so no existing board is touched. A text item is shaped like a
stroke on purpose: **placed, never edited**. To change the words you undo it and type again.

That constraint is the whole design, and it buys three things: the board stays append-only, so
Epic C's collaboration story (whiteboards are the live-collab surface *because* their contents are
immutable and id'd) survives intact; undo keeps working as one stack over everything added, sorted
by `created_at` on load so strokes and text interleave correctly; and there is no selection,
dragging or hit-testing to build, which is where an editable-object canvas gets expensive.

Mechanics worth knowing:

- `MODE_DRAW`/`MODE_MOVE`/`MODE_TEXT` on the view say what *one finger* does. Separate from the
  `TOOL_` constants, which are persisted per stroke — moving and typing produce no stroke.
- Placement is a **tap**, not a drag, and the view only reports the canvas point; the fragment owns
  the editor. `WhiteboardView` still knows nothing about `View`s on top of it.
- The editor is a plain `EditText` positioned at `canvas point − scroll`, and **does not follow the
  canvas**, because panning is suspended while it is open. That is what avoids the floating-editor
  sync problem that makes this feature expensive elsewhere.
- It is **single-line**: with `textMultiLine` the IME swaps its Done key for a newline and there is
  then no gesture that means "finished". Rendering already splits on `\n` if that changes.
- Text draws *under* the ink, so a highlighter over a label reads as highlighting it.
- Size follows the stroke-width picker (×4), so the rail keeps one meaning of "how big".

**Not done, deliberately**: board text is not searchable yet. Home still matches whiteboards on
title only, and text items are now the first real content a board has — that is the obvious next
win, and it is a search-side change, not a whiteboard one.

**Screen layout** (2026-08-07): the heading is alone in the top bar with back and a show/hide eye;
every tool, colour, width and action lives in one floating card rail down the left, sized to its
content and centred (`layout_constrainedHeight` so it shrinks and scrolls rather than being cut off
on a short screen). The eye can't live inside the rail it hides. The canvas runs the **full width**
with the rail floating over it — constraining it to the rail's end left a grey gutter where the old
full-height sidebar used to be, and made hiding the rail resize the canvas and shift the drawing
under your hand. The card carries a hairline stroke rather than elevation, because Material
composites an elevation overlay into *any* card background and 2dp was enough to read as grey. Tools, colours *and* widths all
show selection the same way — `tool_selector_bg` on `setSelected` — which replaced a separate
"current colour" swatch that read as a sixth colour you could pick. Note that framework
`Widget.ImageButton` defaults `scaleType` to **center**, so the 512px PNG icons render at full size
and get cropped unless each button sets `fitCenter` explicitly.

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

- **The card shows a preview of the drawing** *(2026-08-07, reversing the 08-03 decision below)*.
  The original objection — a preview means reading every board's strokes just to draw Home — is
  answered by `WhiteboardThumbnails`: only the cards actually on screen ask for one, and an
  `LruCache` keyed by `id@updatedAt@background` means each board renders once per change, with no
  invalidation to remember. The preview goes through `WhiteboardView.renderThumbnail`, which is the
  export path with a smaller size cap, so a card shows the real board — same paper, same erasures,
  same text — rather than a second drawing implementation that could drift.
  *(Superseded: the card used to show a glyph and a stroke count, because a preview would have
  meant loading every board's strokes and there was no cover image to cache instead.)*
- **Deleting a board is a hard delete**, against the app's soft-delete convention, because there
  is no whiteboard trash surface — a soft-deleted board would just be unreachable rows. Strokes
  go first in the same transaction; they carry a foreign key onto the board.

`WhiteboardRepository` is the Home-side entry point and follows the normal `AppExecutors` +
callback pattern, as does `WhiteboardFragment` since R4. The initial `whiteboards` insert no
longer needs to be synchronous to beat the first stroke: every write on that screen now queues on
the single disk thread in submission order, so queueing the board's row first is enough.
`updated_at` is bumped on draw/undo/clear so the Home section sorts by real recency.

## Markdown note format — implemented, flashcards included

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
| Q&A block | fenced ` ```quill-qa:<block-id> `, question, `---`, answer, ` ``` ` |

Note this differs from the original sketch: embeds reference an **asset id**, not a file
path or an HTML tag, because the metadata (width, duration, transcript) has to live on a
row anyway and a path in the document breaks the moment media moves. Exporting to
portable Markdown becomes a matter of rewriting `quill://` URIs to relative paths.
Markwon was **not** adopted — it renders, but doesn't help with editing, and the
round-trip is hand-written in `MarkdownSerializer`.

**Whiteboard embeds are built** (2026-08-07), in the shape Epic D reserved:
`![whiteboard](quill://whiteboard/<id>)`. Unlike image and audio embeds, a whiteboard resolves
**without the media registry** — the id in the link is the `whiteboards` row itself, so
`NoteDocument.fromMarkdown` builds a `WhiteboardSegment` straight from it and
`replaceMediaAssetsSync` never sees it (`isMedia()` is false: there is no file to own).

That is the whole design: the note points at a board, it doesn't contain one. Removing the embed
detaches, it doesn't delete — the board stays on Home, and it may be attached to more than one
note. The other side of that bargain is that a board deleted from Home leaves an embed pointing at
nothing, which `WhiteboardSegmentView` shows as "This whiteboard was deleted" rather than a blank.

Attaching goes through the toolbar's whiteboard button: **New** creates a board already attached to
the note and opens it (you asked for a board because you want to draw on it), **Import** opens
`WhiteboardPickerDialog` — a search field over rows carrying each board's preview, because boards
are often untitled and three "Untitled Whiteboard - Aug 7"s tell you nothing. Tapping an embed
opens the drawing with a way through to the board; a long press, or that sheet, detaches it.

**One trap worth remembering**: `hasRealContent` decides whether an untouched note is deleted on
exit, and it enumerates segment types. A note whose only content was an attached board counted as
empty and was deleted on the way out until `WhiteboardSegment` was added to it.

See "Flashcards" below for how the Q&A fence's info string ended up being used.

## Quizzes — scored, auto-graded, no free-text matching

**Status: built 2026-08-01** (per-note MCQ quizzes, timed, with attempt history; the
collection/tag scopes and True/False fallback below are still outstanding). Tracked as
Epic E in [requirements.md](requirements.md).

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

**What was built differs from the sketch above in one important way: quizzes read the
note's Q&A blocks directly, not the `flashcards` table.** The plan had both modes sitting
on one content model, and that's still true of the *content* — `FlashcardRepository
.reviewableQa` is the single definition of a usable block, shared by both — but a quiz
doesn't need a card to exist first. Requiring one would have meant "Make quiz" silently
generating flashcards as a side effect, and a note's quiz history depending on whether its
deck had since been deleted. What the two features actually share is the rule, not the rows.

**A quiz stores nothing but the fact that it exists.** `quizzes(id, note_id, created_at)`,
one row per note (a unique index on `note_id`, so "Make quiz" is idempotent and becomes
"Open quiz"). Questions are regenerated from the note at the start of every attempt, which
is what makes a quiz incapable of drifting out of step with an edited note — the whole
`source_segment_id` sync problem the flashcard side has to solve doesn't arise, because
nothing derived is kept. It also means the options land in a different order every time.

**Schema (v5, additive from v4).** `quiz_attempts(id, quiz_id, score, answered, total,
status, started_at, finished_at)`. Three departures from the sketched shape, each earned:
- No `scope_type`/`scope_id` — the scope is a note, and a column that can only hold one
  value is a column that lies about being flexible. Collection/tag scopes can add it.
- `total` is per attempt, not read off the quiz: a note gains and loses Q&A blocks, so
  "2 / 6" only means something next to the 6 that was true that day.
- `answered` distinguishes an abandoned attempt from a bad one. 2/12 having answered three
  questions and 2/12 having answered twelve are not the same afternoon.
- No `quiz_attempt_answers`. The marked paper is shown at the end from the in-memory
  session; nothing yet reopens a past attempt, and a table written for a screen that
  doesn't exist is a table that will be wrong when it does.

**The row is written when the attempt starts.** Otherwise walking out of a quiz leaves no
trace, which silently rewards giving up. Leaving marks it abandoned with what was answered;
a killed process leaves it in progress, and a sweep on the next load retires it — "too old
to still be running" is *computable* here rather than arbitrary, since a quiz is time-boxed
at `total × QUESTION_TIME_MS` plus a grace period.

**Everything tunable lives in `QuizRules`**: `MIN_QA_BLOCKS = 5`, `OPTIONS_PER_QUESTION =
4`, `QUESTION_TIME_MS = 15s`, `WARNING_TIME_MS = 10s`, `ABANDON_GRACE_MS`. The first two are
not independent — a question needs its own answer plus three from other blocks, so four
options need four blocks, and the fifth is what stops every question from reusing the same
three distractors. The option views *and* the indicator pips are built in code from those
constants rather than nailed into the layout, so changing either actually changes the quiz.

**One clock for the whole run, not one per question** (`QuizRules.totalTimeMs` = 15s ×
questions; 6 questions gives 1:30, shown on both the detail screen and the session header so
the two agree). A per-question timer forces the same pace onto a one-line recall and a
question worth thinking about. More importantly it's what makes the rest of the screen
possible: with nothing sealed when a question is left, questions can be answered in any
order, revisited and changed.

**The answer sheet, not a conveyor belt.** `QuizSession` holds selections for every question
and a cursor; Previous/Next move without requiring an answer, tapping an option again clears
it (on a revisitable paper, the alternative to undo is leaving a mis-tap wrong), and the pip
row across the top shows answered/blank/current — that row exists because "did I leave
anything blank?" would otherwise cost a page-through that the clock is charging for. Pips
are tappable, so it's navigation as well as status, and the row scrolls to keep the current
one visible rather than shrinking below readable.

**Running out of time completes the attempt, it doesn't abandon it.** Every question was
put; the blanks are answers the user didn't get to, and the paper is marked as it stands
under a "Time's up" heading. Under `WARNING_TIME_MS` the clock and its bar turn red and a
line spells out what running out costs — latched once rather than re-applied per tick, so it
gets attention instead of fighting for it. The clock pauses behind any dialog and resumes
from where it stopped: time spent answering the *app's* question isn't the user's to pay for.

**Handing in with blanks is allowed, but it asks first.** Free movement makes forgetting a
question easy, so `Submit quiz` counts the blanks and says how many will be marked wrong —
`Submit anyway` or `Keep answering`. Blanks are marked wrong rather than excluded: a score
out of "the ones I attempted" would flatter exactly the run that ran out of time.

**Marked at the end, never per question.** A quiz is a measurement; grading each answer as
it's given turns it into a study session, and the remaining answers get given by someone who
has just been coached. (It's also what lets an answer be changed at all — feedback would
make revisiting a question a free second guess.) The results list restates the correct
answer for wrong answers only: repeating it under a right one is noise, omitting it entirely
makes the list a scolding.

**Screens.** `QuizzesFragment` (third bottom-nav tab, `ic_stopwatch`) → `QuizDetailFragment`
(question count, attempt history, Start) → `QuizSessionFragment` (timer, single-select
options, Submit, then the marked paper in the same panel). The detail screen reads the
question count from the *note* on every resume, so it can say "only 3 complete Q&A blocks —
5 are needed" rather than letting Start fail. Deliberately shaped like the flashcards
screens next door: same row idiom, same delete-with-confirmation, same empty states.

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

## Sharing and collaboration

**Status: designed 2026-08-06. Note sharing built 2026-08-08; the P2P half is still design
only.** Supersedes the NFC + Wi-Fi Direct plan; the checklist lives in
[requirements.md](requirements.md) Epic C. Three decisions, each with a reason that is easy to
lose:

**Notes are copied, not co-edited.** Live note collaboration is dropped. Importing a shared
note mints a **new id**, so two people editing "the same" note hold two notes and there is no
conflict domain at all. That is what makes the coarse merge granularity accepted in the
Markdown storage decision a non-issue rather than a debt — there is nothing to merge.

**Whiteboards are collaborated on, because they are already a CRDT.** Strokes are immutable,
id'd and carry `author_id`; apply-on-receive dedupes by id, so replays are harmless and no
vector clocks are needed. The eraser needs no special handling either — it is `tool=1`, a
stroke, so erasing is append-only too. **Undo and clear are the only operations that aren't**:
undo must retract the author's own last stroke as a message rather than deleting locally, and
clear is destructive to everyone, so it should be host-only.

**The transport is Nearby Connections, not hand-rolled Wi-Fi Direct.** Still peer-to-peer,
still fully offline — it selects BLE/Bluetooth/Wi-Fi Direct/hotspot itself and supplies
discovery, encryption, framed payloads and reconnect. Those are exactly the two layers the old
plan intended to write by hand. Cost: a Play Services dependency, and `NEARBY_WIFI_DEVICES` on
Android 13+.

**The token is the interface; NFC and QR are interchangeable carriers.** This is the load-
bearing idea. A Nearby `endpointId` is assigned *locally by the discovering device*, so it
cannot be handed over out-of-band — what travels is a session token the host is advertising
under, which the joiner matches against discovery. Because the carrier is incidental, a QR code
does the same job in ~30 lines, works without NFC hardware, and is testable on one machine.
Build QR first, add the tap second.

**On NFC specifically:** the original plan's "tap-to-pair handshake" was Android Beam, which is
deprecated and non-functional on modern devices — phones cannot NDEF-push to each other. The
working shape is `HostApduService` on the host and reader mode on the joiner. Worth the extra
step: have the host emulate an **NDEF Type 4 tag containing an App Link**, and the joiner's
stock NFC stack launches Quill, so their app need not already be open.

**Sharing a note is a file, not a session.** Quick Share is a share *target*, not an API: build
the file, fire `ACTION_SEND` with a FileProvider uri, and the sheet offers Quick Share,
Bluetooth and mail for free. The container is a `.quill` zip (`note.md` + `media/` + manifest)
because the Markdown export is deliberately lossy — images and audio become placeholders, which
is fine for exporting to other tools and feels broken when sharing to another Quill.

**Receiving has three paths, all built now (2026-08-09 added the third):**
- *No Quill* → a `.md` opens in any text editor. Free graceful degradation.
- *Explicit Import* (`ACTION_OPEN_DOCUMENT` → picker, Home's FAB) → works over every transport.
- *Tap the received file* → `MainActivity` (now `exported="true"`, `singleTask`) carries an
  `ACTION_VIEW` filter matched on mime type only (`application/zip`/`json`/`octet-stream`) — a
  `pathPattern` was never worth adding, since a `content://` Uri from Quick Share has no usable
  path for one to match. The Uri is handed to `HomeFragment.handleSharedFile` once Home is the
  resumed fragment (a `FragmentLifecycleCallbacks` watch, since a cold start needs the nav host to
  inflate first), which is the same three-format cascade a manual pick already runs — sniffing the
  content after opening is still the real check, same as it always was for the picker's `*/*`.

### The `.quill` bundle, as built (2026-08-08)

`share/QuillBundle` is the format, `share/BundleWriter` packs, `share/BundleReader` unpacks, and
`data/NoteImporter` inserts. The reader/importer split is deliberate: the format is testable
without a database, and a malformed file never reaches a transaction.

**`note.md` inside the bundle is the stored document, not the Markdown export.** That is the
whole difference between the two. `MarkdownExporter` is lossy on purpose — images and audio become
italic placeholders, Q&A fences become bold paragraphs — because its destination is someone else's
editor. A bundle's destination is another Quill, where the same flattening reads as a note that
arrived broken. So the bundle carries `notes.content_blob` verbatim, `quill://` embeds intact, and
the manifest carries the two things that live *beside* the document: the title (a column on the
note's row) and per-asset metadata (width, duration).

**Ids in a bundle are the sender's, and nothing reuses them.** The importer mints a new note id,
new asset ids, new whiteboard/stroke/text ids and new files, then rewrites the document to match
via `NoteDocument.rewriteEmbedIds` — which lives on `NoteDocument` because the embed regex is that
class's and a second copy would drift. One rule there covers every kind of embed, since the rewrite
matches purely on the id inside a `quill://` line and never looks at which kind of embed it names:
**an embed whose id isn't in the map is dropped.** That's what makes an image whose file didn't
make it into the archive and a whiteboard whose row was already gone on the sender degrade the same
way — the embed line simply disappears rather than pointing at nothing.

**A note's attached whiteboard travels with it (fixed 2026-08-09; previously silently lost).**
`BundleWriter` originally only packed segments where `isMedia()` is true, which a `WhiteboardSegment`
deliberately isn't (see "Whiteboard embeds are built" above) — so the board never made the trip, and
because its id was never added to the rewrite map either, the embed line itself vanished on import
rather than degrading to "This whiteboard was deleted." Fixed by embedding each attached board under
`whiteboards/<id>.json` inside the `.quill` zip, reusing `WhiteboardBundleWriter`/
`WhiteboardBundleReader` (the standalone `.quillboard` format, see below) rather than a second
serialization. `NoteImporter` mints the board's new id into the *same* map media assets use — the
kind-agnostic rewrite is what makes that safe — then inserts the whiteboard, its strokes and its
text items inside the note's own transaction. A dangling embed (board deleted from Home before
export) is still simply not written, same as before.

**Tags match by name, case-insensitively, and an existing tag keeps its own colour.** A tag id is
local — "Lecture" on two phones is the same idea with different keys — so importing by id would
grow a second invisible "Lecture" per arrival. The colour stays because the user chose it.

**`created_at` is inherited, `updated_at` is now.** When the note was written is a fact about it
and the one thing a copy can honestly keep; arriving is this copy's most recent event, and it is
what puts the import at the top of Home where the user is looking. Imports land in no collection —
the sender's folders are theirs.

**Media moves before the transaction opens**, not inside it. Holding SQLite's write transaction
across file copies would block every other repository on the shared single disk thread for the
duration. The trade is orphaned files in private storage if the process dies in between — the
cheaper failure, and the same one an interrupted image embed already produces. The move itself is
`renameTo` first (cache and files are normally the same filesystem, so no bytes move) with a copy
behind it for devices that split them.

**The bundle is untrusted input**, because it arrived from another device over a transport with no
sender identity. Two guards: entry names are whitelisted to a plain file directly inside `media/`
(a check for `".."` is not enough — `media/../../databases/quill.db` is what a hostile archive
writes), and the 256 MB cap is counted **as bytes arrive**, never read from the entry's declared
size, which a zip bomb writes itself.

**The share sheet is the entire transport story for a note.** Quick Share, Bluetooth and mail are
share *targets*, not APIs — `ACTION_SEND` with a FileProvider uri and
`FLAG_GRANT_READ_URI_PERMISSION` gets all three for nothing. The import filter is `*/*` rather
than `application/zip` for the mirror-image reason: a bundle that came over Quick Share is typed
`application/octet-stream`, so a correct filter would grey out the files this feature exists to
open. Sniffing after opening is the only reliable check, and `BundleReader` is where it happens.

**Locked collections can't be shared** (the Epic B boundary, decided here). A bundle is plaintext,
so it would be the lock's only hole. `CollectionRepository.isLocked` answers, the editor reads it
when the note loads so the menu can decide synchronously, and the menu item stays *tappable* —
greyed out explains nothing and hidden reads as a feature Quill lacks, so the tap carries the
reason. Nothing writes `biometric_locked` yet, so it always answers false today.

**None of the P2P work is testable on the emulator** — NFC and Nearby both need two physical
devices. That is a real planning cost for a project that has otherwise been verified entirely
on `emulator-5554`, and another reason the QR and Import paths come first.

### Whiteboard and collection sharing (built 2026-08-08, same day as the note bundle)

Two more things travel the same share-sheet-and-picker path as a note, each with its own format:

- **`share/WhiteboardBundle`** (`.quillboard`) is plain JSON, not a zip — a board has no files to
  carry, just point lists and strings, and both are already JSON's native shape. `authorId` on a
  `Stroke`/`WhiteboardText` is dropped entirely rather than carried and ignored: it's a
  live-collaboration field the single-device bundle format has no use for.
  `WhiteboardFragment`'s export button is now a `PopupMenu` — the pre-existing flat-PNG export
  (lossy, a picture of the board) alongside the new **Share whiteboard** (lossless, another Quill
  can keep drawing on it). `data/WhiteboardImporter` inserts with fresh ids, same rule as a note.
- **`share/CollectionBundle`** (`.quillpack`) is a zip of zips: `manifest.json` plus one
  `notes/<n>.quill` entry per member note, each entry a complete, ordinary `.quill` that
  `BundleReader`/`NoteImporter` already know how to read. Nothing about a single note's format had
  to change — `data/CollectionImporter` makes a new collection, then calls
  `NoteImporter.insertBundle(contents, collectionId)` once per entry. That method (public now,
  was `private insert`) is what lets a collection import assign notes into the collection it made,
  while a lone `.quill` import still passes `null` and lands the note loose on Home as before. One
  corrupt member note is skipped, not fatal to the rest of the pack — reported back as "N of M".
- **The manifest tells the formats apart.** `CollectionBundle.KEY_NOTE_COUNT` is a key a `.quill`
  manifest never has, which is what lets an importer try note → whiteboard → collection in
  sequence against a file of unknown type (Home's picker is still `*/*`) without a false positive:
  each reader's `InvalidBundleException` on the wrong format is the signal to try the next one.
  `WhiteboardBundle` carries an explicit `"type":"quillboard"` for the same reason, since its
  format has no zip structure to fail on first.
- **Locked collections still can't be shared** — `CollectionDetailFragment`'s share button asks
  `CollectionRepository.isLocked` before packing, same guard as a single note's Export menu.
- `NoteRepository.loadForBundleSync` is a new public synchronous read (title, segments, tags,
  timestamps) added for `CollectionBundleWriter`'s caller, which is already looping over note ids
  on a disk thread — the existing async `loadNote` is shaped for one screen, not a batch.

**Dependencies and manifest permissions for session join were added ahead of the code (2026-08-08),
while the `.quill` bundle work was in flight** — `play-services-nearby`, `play-services-code-scanner`,
`zxing-core` in `libs.versions.toml`/`app/build.gradle.kts`, and the full Bluetooth/location/Wi-Fi
permission ladder in `AndroidManifest.xml` (see the comments there for the API-level split:
unversioned Bluetooth + location below 31, the `BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT` trio at 31,
`NEARBY_WIFI_DEVICES` at 33). **The code itself landed 2026-08-11** — see "Live whiteboard
collaboration" just below. NFC (`HostApduService`) is the one piece still not built; QR is.

## Live whiteboard collaboration

**Status: built 2026-08-11** (Epic C's session-join and live-session checklist items). Three new
classes under `collab/` plus one dialog helper under `ui/whiteboard/`:

- `collab/CollabMessage` — the wire format. A tagged union serialized to JSON
  (`org.json`, already on the classpath, no new dependency): `SNAPSHOT` (a board's full
  strokes+texts), `STROKE`, `TEXT`, `RETRACT` (an id plus which table it's in), `CLEAR`. `CLEAR`
  is a fourth message beyond the three requirements.md named, because a destructive,
  everyone-affecting action still needs to travel as a message — it just isn't append-only like
  the other three.
- `collab/CollabSession` — one class, host or joiner, wrapping Nearby's `ConnectionsClient`
  directly rather than through a service. `SERVICE_ID` is fixed and app-wide
  (`"mse.quill.whiteboard"`); what disambiguates one session from every other nearby Quill user is
  the **session token**, a short random string the host advertises under
  (`startAdvertising(endpointName = token, …)`) and the joiner matches against
  `DiscoveredEndpointInfo.getEndpointName()` before ever calling `requestConnection`. Both sides
  accept in `onConnectionInitiated` with no extra prompt — reaching that callback at all already
  proves the other device saw the token, so a second "accept this stranger?" dialog would be
  asking about someone who, by construction, already passed the real gate.
- `collab/QrCodes` — the token rendered to a `Bitmap` via zxing's `QRCodeWriter`. Pure Java, no
  Android type crosses into it except `Bitmap` on the way out.
- `ui/whiteboard/CollabDialogs` — entry ("Host" / "Join"), a host dialog (QR + status line), and a
  joining dialog (status line only), built the same way `WhiteboardDialogs` builds its dialogs —
  `MaterialAlertDialogBuilder` plus views assembled in code, since none of this has a
  `res/layout` counterpart.

**Scanning never asks for `CAMERA`.** Joining calls `GmsBarcodeScanning.getClient(...).startScan()`
— Play Services owns the scanner UI and its own process, and only the decoded string comes back to
Quill. The manifest's `<uses-feature android:name="android.hardware.camera" android:required="false">`
was already staged for exactly this.

**Wiring lives in `WhiteboardFragment`**, behind a new "Collaborate" toolbar button
(`ic_collab.xml`, a hand-drawn placeholder vector — no glyph for this existed in the supplied icon
set, matching how the Q&A toolbar icon was handled the same way in July). A `CollabSession.Listener`
field (`collabListener`) is the single place incoming events land:

- **`onPeerConnected`**: dismisses whichever status dialog is showing, and — only if this device is
  the host — reads the board's current strokes/texts straight off disk (not off the view, which
  could be stale relative to a save still in flight) and sends them as one `SNAPSHOT`. The snapshot
  is a **replace, not a merge**: the joiner wipes its own board and DB first, then loads the host's
  snapshot as ground truth. There's no vector-clock or CRDT reconciliation for two boards that
  diverged *before* a session started — only for what happens *during* one, which is exactly the
  scope requirements.md drew.
- **`onMessage`**: `STROKE`/`TEXT` go straight to `WhiteboardView.addStroke`/`addText` (both now
  dedupe by id, added alongside this feature — a replay after a reconnect is harmless) and to the
  matching repository's insert. `RETRACT` removes from the view and deletes from the DB. `CLEAR`
  wipes the board, but **only if this device isn't the host** — see below for why a host can't
  receive its own clear back.

**Undo/Clear are enforced by what already exists, not by an author check added for this feature:**
- `WhiteboardFragment.undoStack` only ever gets pushed to when *this device* creates a stroke or
  text item locally (`onStrokeComplete`, `commitText`). Anything applied from `onMessage` goes
  straight to the view/DB and is never pushed onto that stack. So "undo can only retract your own
  last item" cost nothing to add — the stack it pops from was already scoped that way, for a
  completely different reason (surviving app restarts via `created_at` order). Undo still sends a
  `RETRACT` so the peer's copy is removed too.
- Clear is gated at the UI, not the message handler: `applyCollabRoleToUi()` disables `btnClear`
  outright for a joiner for as long as a session is connected, so there's nothing to reject
  server-side and nothing for a joiner to discover the hard way by tapping it. The host's own clear
  sends `CLEAR` to the peer; a device only ever *applies* an incoming `CLEAR` when it isn't the
  host, which is what stops the host from wiping its own just-cleared board a second time if a
  message ever echoed back.

**Not done, deliberately, this pass:**
- **NFC carrier.** QR alone is enough to build and test with two devices; the `HostApduService` +
  NDEF Type 4 tag design is unchanged from the original write-up and still the plan if it's picked
  up later — nothing here forecloses it, since `CollabSession.join` only ever takes a token
  string, not an opinion on how it arrived.
- **Payload chunking.** Nearby's `BYTES` payload caps near 32 KB. An unusually long single stroke,
  or a `SNAPSHOT` of a very large board, could exceed it; not hit in testing, not guarded against.
  `Payload.fromFile` is the fix if it comes up.
- **`.quill` bundle over the wire** ("tap to send a note" as a `FILE` payload) — the transport this
  would ride on now exists, but it wasn't asked for this pass.

**Verified on two physical devices (2026-08-12).** Host/join over Nearby, the QR handshake, live
stroke sync, undo (own-item-only), and host-only clear (disabled on the joiner, wipes both boards
when the host triggers it) all confirmed working. One real bug was caught doing this, not by
reading the code — see "Live collaboration: joiner crash on snapshot" below.

**A joined board's content is a real local copy, not a session-scoped overlay.** The snapshot
writes the host's strokes/text into the joiner's own `strokes`/`whiteboard_texts` rows, so when the
session ends the joiner keeps everything that was drawn during it — confirmed intentional behaviour,
not a leftover to clean up. From that point the two boards are independent copies again (no
ongoing link), consistent with "notes are copied, not co-edited" applying here too: joining a
whiteboard session is a one-time copy-and-then-diverge, same shape as importing a shared note.

### Live collaboration: joiner crash on snapshot (bug found and fixed 2026-08-12)

First two-device run: the moment the peers connected, the joiner's process died
(`SQLiteConstraintException: FOREIGN KEY constraint failed`, thrown from
`StrokeRepository.insertStroke` inside `applySnapshot`) and silently dropped to the home screen —
which the host read as an ordinary disconnect and reported as "Collaboration session ended". Both
halves of that were real symptoms of one bug, not two.

**Root cause:** each device's whiteboard is its own row in its own `whiteboards` table — the host
and joiner never share one `whiteboards.id`. But `CollabMessage`'s `Stroke`/`WhiteboardText`
objects still carried the *host's* `whiteboardId` field, unchanged, all the way into
`applyIncoming`/`applySnapshot`'s `insertStroke`/`insert` calls. Inserting a stroke whose
`whiteboard_id` points at a row that doesn't exist on this device is exactly what
`strokes.whiteboard_id`'s foreign key exists to catch — and a plain, uncaught
`SQLiteConstraintException` on a background thread takes the whole process down with it, not just
the collaboration feature.

**Fix:** re-tag every received `Stroke`/`WhiteboardText` onto `whiteboardId` (this device's own
board id) before it touches the view or the database — one line at each of the three receive
sites (`TYPE_STROKE`, `TYPE_TEXT`, and the loop inside `applySnapshot`). The wire format itself
didn't need to change; `authorId` deliberately stays whatever the sender wrote (still "local-user"
today, since neither device tags it with a real per-device id yet), it's specifically
`whiteboardId` that must always mean "my local board," never "whichever board the sender drew it
on."

**Lesson worth keeping for the rest of Epic C:** any model object that carries a foreign key onto a
*local* table (here, `whiteboardId`; the same shape would bite `note_id` if a future feature ever
transmits a segment) has to be re-homed on receipt, not trusted verbatim off the wire — the id is
only ever valid on the device that minted it.

## Timestamps

**`util/RelativeTime` is the only way the app phrases a time** (2026-08-06). It replaced scattered
`DateUtils.getRelativeTimeSpanString` calls across the note rows, pinned cards, collection cards and
subtitle, deck rows and quiz rows — which were inconsistent exactly where they are read most: a note
saved seconds ago rendered as **"Updated 0 minutes ago"**, because that method's minute resolution
floors to zero instead of saying so in words.

The ladder: `now` · `5 min ago` · `3 hours ago` · `yesterday` · `12 Jun` (`12 Jun 2025` once the
year differs). `future()` mirrors it (`in 5 min`, `tomorrow`) for a deck's next review. The short
date's pattern comes from `getBestDateTimePattern`, so field order follows the locale.

Three things worth not re-deriving:
- **Under a day is elapsed time; "yesterday" is a calendar question.** Mixing them is what stops 30
  hours ago reading as yesterday when two midnights have passed, and stops 2am reporting
  "yesterday" for something three hours old.
- **The day difference is rounded, not truncated** — midnight to midnight is 23 or 25 hours across
  a daylight-saving change, a bug that would surface twice a year and nowhere else.
- **A negative distance reads as `now`**, so a record stamped a moment ahead of the clock doesn't
  render as a date.

Choosing the rung is split from wording it (`Bucket`, package-private) so the boundaries are unit
tested on the JVM — `RelativeTimeTest`, 8 cases. Wording is a resource lookup and needs a Context.

## Search, filtering and sorting

**Status: built 2026-08-06.** One control, `ui/search/SearchFilterBar`, used by Home and a
collection's detail screen — they had drifted into two copies of the search field alone. It is a
compound view holding the box, the filter button and the active-filter chip row.

**The box follows the Figma's HomePage 2 frame** (node `32:600`): white, 1px hairline outline, no
start icon, with the filter button *outside* it to the right. The magnifying glass was the one
thing making the screen look like a stock Android search widget, and it was
`@android:drawable/ic_menu_search` — a framework drawable, against the M3 rule below. The button
sits outside because the box is for typing and the button opens a different surface; inside, it
read as part of the field.

**The bar holds no state.** The screen owns a `NoteFilter` and passes it to `render()`; the bar
reports typing and taps. Filtering runs in memory because both screens already hold every note they
show — pushing it into SQL would be a round trip per keystroke.

**What it filters and sorts:** query (title + preview), any-of tag selection, pinned-only, and four
orderings (most recent, oldest, title A–Z, Z–A). Two decisions worth keeping:
- **Tags are any-of, not all-of.** Picking a second tag should widen, not empty, the list.
- **Collections ignore tag and pinned filters**, since both are properties of a note — a collection
  vanishing because none of its notes carried a tag looks like it was deleted. Sorting still
  applies, so the two lists agree on what "oldest" means. Collections sort on `lastActivityAt`,
  the value their card already shows.

**Selected tags in the filter sheet are shown by border colour alone, and the border is always
there.** Two things had to be got right, in order:
- The chips are tinted with the tag's own colour at rest, so Material's default checked treatment —
  a background change — lands on a colour already doing something else and reads as no change.
- The obvious fixes then cause a *layout* problem. A checked icon adds a glyph plus its padding, and
  a stroke that only appears when selected re-measures the chip; either way the row visibly reflows
  on every tap. So every chip carries the stroke at all times, painted the same colour as its fill
  when unselected — present but invisible. Selection changes exactly one colour and nothing
  re-measures.

Driven by a `ColorStateList` rather than set on toggle, so the chip restyles itself without a
listener.

**A dialog's list should size to its content, up to a ceiling** (`util/MaxHeightScrollView`). The
add-existing-notes picker set a *fixed* `note_picker_max_height`, so three candidate notes opened a
dialog sized for fifty. `WRAP_CONTENT` alone breaks the opposite case — a long list grows past the
screen and pushes the dialog's buttons off it. Measuring `AT_MOST` against the ceiling gives both.
Reuse this rather than a fixed height for any dialog list.

## Export — PDF and Markdown

**Status: built 2026-08-06.** Options menu → **Export** (`ic_share`) opens a second `PopupMenu`
offering **PDF** (`ic_pdf`) and **Markdown** (`ic_markdown`). Both write into
`Downloads/Quill/<title>_<timestamp>.<ext>` via `util/NoteExportStore`, which is `ImageExporter`'s
sibling — MediaStore with `IS_PENDING` on API 29+, a plain file plus `WRITE_EXTERNAL_STORAGE` below
it. Segments are read on the main thread and the file written on the disk thread, so an export is
of what's on screen, not of the last autosave. Nothing is saved first: unlike flashcards or a quiz,
exporting shouldn't be what creates a note's row.

**Markdown (`MarkdownExporter`) is deliberately thin** — the note already *is* Markdown, so text
passes straight through `MarkdownSerializer`. The work is only the parts that are Quill's rather
than Markdown's, and each is resolved toward "readable elsewhere" rather than "reloadable here"
(the database is the copy that round-trips): `quill://` embeds become italic placeholder lines,
```` ```quill-qa ```` fences become bold **Q:**/**A:** paragraphs, and the title — which lives on the
note's row, not in the document — is prepended as an H1.

**PDF (`PdfExporter`) preserves styling almost for free**, because the note's text is already a
`Spanned` and `StaticLayout` draws bold, italic, underline and bullets natively. Only headings need
translating: the editor stores them as invisible line markers and derives size/weight at display
time, so the exporter re-derives the same spans at `RichTextField`'s scales (1.6 / 1.3). Copying
into a `SpannableStringBuilder` first is what makes deleting the markers safe — it moves the
existing spans as characters are removed.

Two things worth knowing about the layout code:
- **Coordinates are PostScript points at 72dpi** (A4 = 595×842), never dp. A PDF has no screen
  density, and using dp would make the output depend on the device that produced it.
- **Pagination draws the whole layout clipped**, translated so the first wanted line lands at the
  cursor, rather than re-laying out a slice — re-measuring a subrange would re-wrap it and lose the
  bullet gutter. `drawLines` returns the first line it didn't draw.

**Audio becomes "Embedded Audio Recording - m:ss"** in both formats — in the PDF as a filled block,
so a reader skimming sees the same shape of document the note has. It is the one part of a recording
a reader can still act on.

**Fixed on the way**: `MarkdownSerializer` was encoding a heading's *derived* bold span, because its
skip-guard only recognised a span starting at `lineContentStart` while `RichTextField` applies it
from `lineStart` — the invisible marker included. Invisible in the app (a heading is re-bolded from
its marker on load) but it surfaced in an export as `# **Heading**`. The guard now accepts either
bound.

**The confirmation is a dialog, not a Snackbar** (`dialog_export_complete`): format badge, filename,
and **Open** / Done. A Snackbar took the useful action away again after a few seconds — long enough
to miss, and unrecoverable, since nothing in the app lists past exports. The badge springs in
(`OvershootInterpolator`, 320ms) and the two lines rise behind it; the buttons are usable
throughout.

**Opening needs a uri, so `NoteExportStore.save` returns `Saved(displayName, uri)`.** On API 29+
that's the MediaStore row's own uri; below it, a `FileProvider` uri — hence the `external-path`
entry for `Download/Quill/` added to `file_paths.xml`, since a `file://` uri can't be handed to
another app.

**Markdown is opened with two attempts.** Almost nothing registers for `text/markdown` — a stock
emulator image has *no* handler at all, while everything handles `text/plain` — so the accurate
type is tried first and the readable one is the fallback. Without it, Open on a Markdown export was
a button that could only ever fail. PDF needs no fallback (Google's viewer handles it).

**Performance is a non-issue for text**, measured on the emulator: 4.9K chars → 35ms; 38K → 43ms;
**304K chars (a 75-page PDF) → 150ms**, Markdown 49ms. It runs on the disk thread regardless.
Images are the cost that would matter — each is decoded to ≤1600px and drawn — and that has not
been measured.

**Sharing arrived as a third format, not as a button on these two** (2026-08-08). PDF and Markdown
still only offer **Open** — they are for reading elsewhere, and a `.md` whose images are
placeholders is not a thing to send someone. The Export menu's third item writes a `.quill` bundle
instead and its dialog's positive button is **Share**. See "Sharing and collaboration".

## Who owns a press on a waveform

**A waveform is only a scrubber while its clip is loaded** (`WaveformBarsView.setScrubbable`, driven
from `AudioSegmentView.render()` with `AudioPlayback.isCurrent`). The two modes exist because the
same pixels mean different things:

- **Live clip** — seek on `ACTION_DOWN` and follow the finger. Immediacy is the whole point, and the
  view claims the gesture from the scrolling note up front.
- **Dormant clip** — the card's long press (**delete the recording**) has to be reachable, so
  nothing happens on the way down. A hold past `ViewConfiguration.getLongPressTimeout()` delegates
  to the parent's `performLongClick()`; a lift before that seeks (which starts the clip); a
  horizontal drag past the slop becomes a scrub and claims the gesture *then*, not before; a
  **vertical** drag past the slop returns `false` and lets the scrolling note intercept.

The bug this fixes: `onTouchEvent` consumed `ACTION_DOWN` unconditionally, so a long press anywhere
over the waveform never reached the segment's long-click listener — and because seeking a dormant
clip *starts* it, holding to delete instead began playing the recording at whatever point the finger
landed. The delete gesture was only reachable on the card's padding, the play button and the time
label.

Worth remembering generally: **a child that consumes `ACTION_DOWN` silently removes its parent's
long press.** Any time a custom view sits inside a card with a long-press action, it owns that
decision whether or not it means to.

## Audio that finishes closes itself

**Both players tear down at the end of their audio** (2026-08-06), so the now-playing bar is never
a control for nothing. Read-aloud already did — `NoteReader` fires `onReadingFinished` on the last
chunk and `ReadAloud` clears. `AudioPlayback` deliberately did the opposite, staying loaded so the
bar could offer to replay a finished clip; it now reaches the same end state as ✕, notification and
foreground service included. The replay affordance lives on the note's own card, which resets to a
play button and the clip's full length as soon as it stops being the current clip.

The close is **posted, not run inline** — the callback comes from the `MediaPlayer` that `close()`
is about to release — and **keyed to the path that finished**, because a tap queued ahead of that
post can have started a different clip by the time it runs.

**A test-data trap that cost real time here.** The seeded clip in the emulator's
`files/audio/*.m4a` was an **Ogg Vorbis file with an `.m4a` extension** (`afinfo` says
`File type ID: Oggf`). Android plays it, reports a 30s duration, then pins `getCurrentPosition()`
near 29s and **never fires `onCompletion`** — which reads exactly like "the audio keeps replaying
and the bar never closes", i.e. like a bug in this code. If a clip won't end, check the container
before the logic: `run-as mse.quill cat files/audio/<f>` out and run `afinfo` on it. A clip made
with `say -o clip.m4a --data-format=aac …` completes properly.

## Read-aloud

**Status: made screen-independent 2026-08-06.**

**Who owns the voice.** `audio/ReadAloud` is a process-wide singleton holding one
`audio/NoteReader` (the `TextToSpeech` wrapper) for the life of the app. It is the TTS counterpart
of `AudioPlayback`, and it exists for the same reason: the engine used to be a **field on
`NoteEditorFragment`**, stopped in `onPause` and shut down in `onDestroyView`, so a reading died the
instant you navigated anywhere. Now the editor only *asks* for a reading and the now-playing bar
controls it from every screen. `NoteReader` moved out of `ui.notes.editor` into `audio` to match —
it is an engine, not a part of a screen.

**Nothing is cached that the reader already knows.** `isActive()`, `isPlaying()` and `progress()`
read straight through to the reader, so `MiniPlayerView` and the note's own menu cannot disagree
about whether a voice is speaking. The earlier design pushed state *into* `ReadAloud` via
`started`/`update`/`ended` and had to be re-pushed after every control; those are gone.

**A reading is identified by note id**, which is what lets the editor's menu offer "Stop reading"
for *its* note and "Play aloud" while a different note reads on in the background. Two seams keep
that id honest: a note read before it was ever saved starts with a null id and picks one up from
`ReadAloud.noteIdMinted` at the moment `autoSave` mints it, and renaming a note that is being read
calls `retitle` so the bar doesn't keep the old name.

**One voice at a time**, enforced at both ends: starting a reading closes `AudioPlayback` first, and
`AudioSegmentView` calls `ReadAloud.stopOther()` before playing a clip. The bar's clip-wins
tie-break is therefore a belt-and-braces branch, not a case that should occur.

**Opening flashcards or a quiz no longer stops the reading.** It used to, on the reasoning that
those screens are "a different mode" — but that reasoning came from the era when a reading belonged
to the open note, and the bar is present on those screens like everywhere else.

**Not done: the lock screen.** `AudioPlaybackService` (foreground service + `MediaSession`) is still
written entirely against `AudioPlayback`, so a reading has no notification and no lock-screen
controls; it survives in-app navigation, not the app going away. Giving read-aloud the same
treatment means generalising that service over both sources.

## Flashcards

**Status: built 2026-07-30** (Epic D's generation/sync and the per-note review screen; the
global cross-note session and reminders are still outstanding).

**Entry point.** The note editor's toolbar carries an options button (`ic_option`, ⋮) where
the read-aloud button used to sit. Its menu is "Play aloud" (title/icon toggle to "Stop
reading" while *this note* is being read — see "Read-aloud" — hidden when there is nothing to
read) and "Turn into flashcards" —
which becomes **"Review flashcards"** once the note has cards. That flag is re-asked on every
`onResume` rather than cached at load, so deleting a deck puts the label back.
The voice picker is still a **long-press** on that button, exactly as it was on the button it
replaced — a setting used once doesn't earn a line in a two-item menu. The button lives in
the toolbar rather than beside the title so it stays put as the note scrolls.

**A block's id is stored in the fence.** ` ```quill-qa:<uuid> `. This is the piece everything
else hangs off, and it isn't cosmetic: segment ids are minted by `BaseSegmentView`, so a
reload used to give every Q&A block a brand new one. A card keyed to that id would have lost
its review history on the next parse — and re-syncing would have inserted a duplicate every
single time. Old fences without an id still parse (they get one, which the next save writes
back), and a fence typed into ordinary prose is escaped whether or not it carries an id.

**Only complete pairs become cards.** Both halves have to be non-blank. A question with no
answer has nothing to turn over, and half-written is a normal state mid-note, so it's skipped
silently rather than flagged. A note can therefore hold Q&A and still have no deck — that's
what the Snackbar on "Turn into flashcards" explains. The option is deliberately *not* hidden
in that case: a missing menu item leaves someone hunting for a feature they were told exists.

**Sync is an update, never a duplicate-generator.** `FlashcardRepository.syncFromNote` matches
on `source_segment_id` and writes only `front`/`back` when they've changed — the SM-2 columns
are never touched, so fixing a typo in a question doesn't throw away what's known about how
well it's known. A card whose block has been deleted is left in the table (review progress
shouldn't evaporate because a note got tidied up); it simply stops appearing in the deck.
Front/back are stored as **Markdown**, not plain text, so a bolded term or bulleted answer
looks on the card the way it looks in the note.

**Two clocks, deliberately.** `FlashcardScheduler` (SM-2) decides when a card comes back in
*days*; `ReviewSession` decides what happens for the rest of the *sitting*. A missed card goes
to the back of the session queue and has to be answered right before the session ends — being
shown the answer and immediately moving on is how people finish a deck having learned nothing.
Only a card's **first** answer feeds the schedule: without that, missing a card and getting it
right two cards later would look to SM-2 like a clean recall and push it weeks out.

**Why two buttons, not SM-2's six grades.** SM-2 grades 0–5; Quill offers right/wrong and maps
them to 5 and 2, the two ends of the boundary the algorithm actually turns on. Self-rating
recall on a six-point scale mid-review is the part of SM-2 people get wrong most often and the
part that matters least — the interval ladder (1 day → 6 days → ×easiness) does the work.
Easiness floors at 1.3, below which intervals stop growing and it degenerates into daily
drilling.

**The Flashcards tab** (`FlashcardDecksFragment`) is the app's second top-level destination, added
alongside Home in a `BottomNavigationView` wired by `NavigationUI` — the bottom menu's item ids
*are* the destination ids, which is what gives correct tab-switching and back-stack behaviour
without a click listener. The bar hides itself on every other destination. One row per note that
has cards, ordered decks-with-something-to-do first: a due-count badge (the "reviews left" number,
which is what you scan a list like this for), then `N due now · M cards` with either the unseen
count or when the deck next comes back. Counted in SQL, not by loading cards.

**Deleting a deck is a hard delete**, against the app's soft-delete convention. A card is *derived*
from a Q&A block that the delete doesn't touch, so a tombstone would either be resurrected by the
next sync or, worse, block that note from ever making cards again. What's actually lost is review
history — which is what the shared confirmation (`DeleteFlashcardsDialog`, used by both the decks
list and the review screen) warns about.

**Schema v4** adds `flashcards.source_segment_id` and `last_reviewed_at`. Note that
`onUpgrade` is **no longer unconditionally destructive**: from v3 (the Markdown schema)
upgrades are additive, and only pre-v3 development-era databases are rebuilt. Wiping a user's
notes to add two columns isn't a trade worth making.

**The editor now navigates forward, and that exposed a latent bug.** `NoteEditorFragment` read the
note id straight out of its arguments in `onViewCreated`. For a note created *during* the session
the arguments have no id — the editor was opened without one — and until there was somewhere to
navigate to, nothing ever rebuilt the view to notice. Going to the review screen and back does: the
fragment instance survives on the back stack and only its view is recreated, so the read wiped the
id, showed a blank page, and autosaved itself into a **second, empty note**. The id is now taken
from the arguments only when the fragment doesn't already have one, with `onSaveInstanceState` as
the third fallback for a genuine recreation. Anything else added to the editor that navigates away
inherits this fix — don't re-introduce an unconditional read from `getArguments()`.

**UI gotchas worth remembering**, all hit and fixed on the emulator:
- A `ScrollView` consumes taps whether or not it has anything to scroll, so the card's click
  listener only fired on its margins. Fixed with a `GestureDetector` on the scroll view that
  watches touches without consuming them — a long answer still scrolls, a tap still flips.
- The icon set ships as **density-less PNGs**, whose intrinsic size is their pixel size at
  mdpi (~85dp on a 3x screen). Everywhere else they're drawn into a fixed-size `ImageView`,
  but a menu item asks the drawable how big it is and believes it — which blew the popup out
  to the icon's width and truncated the labels. `ic_menu_*.xml` wrap them in a size-pinned
  `layer-list`; do the same for any new menu icon. (A `BottomNavigationView` doesn't need this —
  it sets the icon's bounds from `itemIconSize` itself.)
- Running `connectedAndroidTest` **clears the app's data** on the device, so manual test data set
  up on the emulator is gone after a test run. Seed first, or test first — not the other way round.
- **Never ask `TextToSpeech.isSpeaking()` whether *your* reader is speaking.** It reports the engine
  service's global state, shared across every client in the process and outliving any one of them,
  so a note screen opened right after another one had been reading could inherit "busy" and offer to
  "Stop reading" with nothing playing (reported 2026-07-30 on a physical device). `NoteReader` now
  tracks its own flag, which starts false — right by construction, since a new reader has not been
  asked to read anything. The emulator's Google TTS settles fast enough that it never showed the
  symptom, so this one can only be confirmed on a real device.
- **An icon-only `MaterialButton` does not centre its icon by default.** The button lays the icon
  out relative to its *text* block and insets its own background (6dp top/bottom, plus a horizontal
  inset in the icon-button styles), so at a fixed square size the fill comes out as an ellipse with
  the glyph sitting up and toward the start — which is exactly what the flashcard grading buttons
  did. The recipe that works: `android:padding="0dp"`, all four `android:inset*="0dp"`,
  `android:minWidth/minHeight="0dp"`, `app:iconPadding="0dp"`, `app:iconGravity="textStart"`, and an
  explicit `app:cornerRadius` of half the size for a true circle. Worth measuring rather than eyeing:
  `adb shell uiautomator dump` gives the button's exact bounds, and cropping the screenshot to those
  bounds shows immediately whether the glyph sits at the centre.

## Brand, splash and window insets

**The mark.** `res/font/caprasimo_regular.ttf` (OFL in `licenses/`) is the logo face. The splash
draws it live rather than shipping a raster: `ui/splash/QuillLogoView` renders "Q" with the
typeface and derives the two dots entirely from `getTextBounds("Q")` — 0.30 of the glyph box
across, 0.70 of the way down it, gaps of 0.036 and 0.071 — so `android:textSize` alone scales the
whole mark and it stays sharp at any size. One `ValueAnimator` walks a cycle clock and each dot's
alpha is a pure function of it; an `AnimatorSet` per dot drifts apart over unbounded repeats.
Playfair Display is still the display serif for Home's greeting — the two are not
interchangeable.

**A device won't `drawText` glyphs much above ~250px** at the size you asked for, even though
`Paint`/`getTextBounds` report the large size honestly (measured: `textSize=667.5`, bounds
502×563, drawn ~229px). Harmless at the shipped 96dp, but don't trust large `drawText`.

**Launcher icon** is an adaptive icon in `mipmap-anydpi` (minSdk 26, so it wins everywhere) with
foreground/monochrome PNGs per density under `drawable-*dpi/`. `<monochrome>` must be a real
alpha-only glyph, not the colour foreground, or themed icons render a blob.

**Splash.** `SplashActivity` is the launcher entry point and finishes into `MainActivity` on
`max(2s, StartupTasks)` — `StartupTasks` is the seam for real startup work and completes
immediately today. Android 12+ draws its *own* splash before it, so
`Theme.Quill.Splash` sets `windowSplashScreenAnimatedIcon` to an empty vector: the mark cannot be
shown statically and then animated without a visible stutter.

**Window insets — the rules that cost time here:**
- `MainActivity` applies only the **side** insets to its root. The **top** goes to each screen
  (see below) and the **bottom** to whichever view actually reaches the screen bottom —
  `BottomNavigationView` pads *itself* by it, so padding the root too charged for it twice.
- **A view has exactly one `OnApplyWindowInsetsListener`.** A second `setOnApplyWindowInsetsListener`
  silently replaces the first. The note editor's root is already claimed by
  `KeyboardInsetsHandler`, which is why its status-bar inset goes on the toolbar.
- **Padding a view for an inset only moves its contents unless the view can grow.** A fixed
  `layout_height` squeezes; a `minHeight` taller than the content pushes the contents down inside
  the same box. `WindowInsetsUtils.applyTopInset` grows `minimumHeight` for that reason, and the
  editor toolbar had to move from `48dp` to `wrap_content` + `minHeight`.
- Screens get the top inset automatically from one
  `FragmentManager.FragmentLifecycleCallbacks` in `MainActivity` (`recursive = true` — the screens
  live in the nav host's *child* manager). Default target is the fragment root; implement
  `WindowInsetsUtils.TopInsetHost` to name another view. Only Home (gradient header) and the note
  editor (toolbar) do. **Don't re-add per-fragment calls.**

**A negative margin on a weighted `LinearLayout` child positions but does not measure.**
`measureVertical` accumulates `totalLength = max(totalLength, totalLength + childHeight + margins)`
and that `max` swallows it, so the child is pulled up *and* measured short — Home's content sheet
stopped 56dp above the bottom bar. Put the offset on the sibling above instead (the header's
`layout_marginBottom`).

## Two races that only show up when the user is fast

Both pre-date the work above and both need input faster than a hand-paced emulator run; chain
taps inside a **single** `adb shell "input …; input …"` call to reproduce (separate `adb shell`
invocations are 200-400ms apart, slower than a person).

- **`NoteReader` / TTS.** `TextToSpeech` binds asynchronously and `onDestroyView` → `shutdown()`
  nulled the field; leaving a note before the bind completed crashed the app in the init callback.
  The callback now takes a local reference and bails on a `shutDown` flag. Any per-screen async
  engine handle needs the same shape. *(The reader is no longer per-screen — see "Read-aloud" — so
  nothing calls `shutdown()` in normal operation and this race can't fire today. The guards stay:
  they are what makes the class safe to release at all.)*
- **`NoteEditorFragment.autoSave` deleting the note you just opened.** `loadNote` is async, so a
  note opened and left before the read returns has an empty title and no segments — `hasContent`
  was false and it took the "user emptied this" branch. Guarded by `contentLoaded`. Empty fields
  before a read means "not loaded", never "emptied".
- Relatedly, `NoteRepository.createNote` now takes an id the caller minted
  (`NoteRepository.newNoteId()`) instead of generating one behind an async callback. `noteId` is
  usable immediately, and the insert and the first save queue in order on the one disk thread —
  a save arriving mid-creation used to be dropped.

## Module layout — `:app` and `:shared`

Quill was one module until 2026-08-13. It is now two: `:app` (the Android application) and
`:shared` (a plain-JVM `java-library` holding `FlashcardScheduler`, `ReviewSession`, `QuizSession`,
`QuizGenerator`, `QuizQuestion`, `QuizRules` and the `Flashcard` model).

**Why split at all.** The study logic has been Android-free since Epic A, but only by convention —
nothing stopped someone reaching for a `Context` in `QuizGenerator` except a code review that
noticed. A module without the Android classpath turns that into a build failure, which is the only
version of the promise that holds up. Verified rather than assumed: adding
`import android.content.Context;` to `QuizRules` and running `:shared:compileJava` fails with
"package android.content does not exist". The immediate driver is Epic J — the Wear companion
reuses SM-2 verbatim, and a schedule that drifts between the wrist and the phone is the bug this
prevents by construction.

**The files moved; the package names didn't.** `FlashcardScheduler` still declares
`package mse.quill.data`, `ReviewSession` still `mse.quill.ui.flashcards`, and the quiz classes
still `mse.quill.ui.quiz` — so `mse.quill.ui.quiz` now spans both modules. That is deliberate. Split
packages across Gradle modules are legal here (there is no JPMS in the picture), and keeping the
names made the move import-invisible: **not one import in `:app` changed.** The diff is eleven
renames and three build files, which is reviewable at a glance in a way that the tidier
`mse.quill.study.*` rename — same zero behaviour change, sixty-odd files touched — would not have
been. It also sidestepped a real hazard: `QuizQuestion`'s constructor is package-private and only
`QuizGenerator` calls it, so a rename would have had to widen it or move both in lockstep anyway.

The package names are, admittedly, now lying twice over: `mse.quill.data` for a class with no
database in it and `ui.flashcards` for one with no views. They were lying before the split too —
that's *why* the split was possible. Rename if it ever earns itself; it hasn't.

**What stayed in `:app`.** `FlashcardRepository` and `QuizRepository` (SQLite), the fragments, and
`FlashcardRepository.reviewableQa` — which takes `NoteSegment` and so belongs on the Android side
of the line. `ExampleUnitTest` stayed too; the four real JVM suites moved (32 tests:
`FlashcardSchedulerTest` 9, `ReviewSessionTest` 8, `QuizGeneratorTest` 9, `QuizSessionTest` 6) and
run under `:shared:test` with no device and no Android test runner.

**Gradle gotcha worth keeping:** `:shared` pins `sourceCompatibility`/`targetCompatibility` to 11 to
match `:app`'s `compileOptions`. A mismatch compiles clean and then fails at dex time complaining
about class file versions, which reads as anything but a toolchain problem.

## Wear OS — why `:wear` will be Kotlin

Recorded here because it is the project's first departure from "one language, Java", and the
reasoning is not obvious from the outside. Full checklist in
[requirements.md](requirements.md)'s Epic J.

The tempting plan was to stay in Java as long as possible: build the tile and complication in Java
(both are ordinary Java-callable Android services), and add Kotlin only later, for the Compose
review screen. It doesn't survive the tile libraries, which split by language and not evenly —
`protolayout-material` (Material **2.5**) has Java builders, while `protolayout-material3` (M3
Expressive, `MaterialScope`, `Material3TileService`) is Kotlin-only with no Java builders at all.
So a Java tile is a Material 2.5 tile, which trades one recorded divergence (a Kotlin module) for a
worse one (a watch surface off the Material 3 standard the rest of the app was migrated onto),
to postpone a language boundary the review screen forces anyway. The boundary goes at the module
edge instead: `:app` and `:shared` stay Java, `:wear` is Kotlin + Compose.

**Correcting a thing that was written down wrong:** Wear's view-based widgets are *not* deprecated.
`androidx.wear:wear` ships; individual pieces are retired (`AmbientModeSupport` →
`AmbientLifecycleObserver`) and Compose is merely "recommended". The reason to skip the view path is
the M2.5/M3 split above — worth keeping straight, since a decision defended on a false claim gets
reopened the moment someone checks.

## Wear phase 1 — the projection, the tile, the complication

Built 2026-08-13. `:wear` is a third module (`com.android.application`, `applicationId = "mse.quill"`
to match the phone — a mismatch there is the classic reason a `DataItem` publishes fine and never
arrives). What exists: the phone builds and publishes the projection, the watch decodes it, and the
tile and complication render from it.

**The split between `:shared` and `:app` is the useful part.** `DueProjection.select` — horizon
filter, most-overdue-first ordering, the 120-card cap, the 240-character trim — is pure and lives in
`:shared` with ten tests. `FlashcardRepository.dueProjectionSync` does only the two things that need
a database: the query and the lock rule. Neither half can be got wrong without the other noticing.

**The lock rule is stricter here than anywhere else in the app, on purpose.** Every *locked*
collection is excluded, open or not — not just the hidden ones the rest of the app filters. Screens
ask "should this be visible"; the watch asks "should this leave the device", and for anything the
user chose to encrypt the answer is no regardless of what's unlocked this session. `NoteCrypto`
gained `excludeCollectionsClause` so the call site reads as the rule rather than as
`hiddenClause(lockedIds)`, which looks like a bug. A free consequence worth knowing: because rows
drop out by collection id, nothing in the projection can ever be ciphertext, so unlike `loadDecks`
there is no decryption step to forget.

**`null` and zero are different answers.** No `DataItem` means the pair has never synced → "Open on
phone". An empty card list means the phone looked and found nothing → "All caught up". The
complication returns *no data* rather than "0" in the first case: a watch face reading 0 would tell
someone they were up to date on the strength of never having heard from the phone.

**There is no cache on the watch.** The `DataItem` *is* the store — the Data Layer already persists
it across reboots and replaces it atomically, so a SharedPreferences copy beside it would only add a
second thing that can be stale. `ProjectionListenerService` therefore holds no state; it just calls
`TileService.getUpdater(...).requestUpdate(...)` and `ComplicationDataSourceUpdateRequester`.

**Three publish triggers**, and the ordering of one of them matters: `StudyReminderWorker` publishes
**before** its notifications-enabled early return, because turning the daily nudge off is not a
request for a tile that stops counting. The other two are `recordReview` (after the callback, so the
review screen advances at database speed rather than Data Layer speed) and `MainActivity.onCreate`
(which covers a watch paired since the last of the other two).

**Gotchas, all of them found the hard way:**

- **AGP 9 has built-in Kotlin support.** Applying `org.jetbrains.kotlin.android` fails twice over —
  first "already on the classpath with an unknown version", then "extension with name 'kotlin'
  already registered". `:wear` applies AGP alone and configures `kotlin { compilerOptions { } }`.
- **`Material3TileService.tileResponse` is an extension on `MaterialScope`**, not a method taking
  one: `override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile`. So
  `this` inside it is the scope, and the scope has its own `Context` — an unqualified
  `DueProjectionClient(this)` won't compile and an unqualified `getString` would resolve to the
  wrong receiver. Bind `val service = this@DueTileService` first.
- **The tile's main slot truncates at roughly twenty characters** on a 384px watch. "Open Quill on
  your phone" rendered as "Open Quill on your p…"; the string is now "Open on phone".
- **`Tasks.await` on `Dispatchers.IO`** rather than adding `kotlinx-coroutines-play-services` for a
  single `.await()`.

**Verified on the Wear emulator (`emulator-5556`, Wear OS 6 / API 36, 384×384 round):** both
services register, the tile was added with
`adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile
--ecn component mse.quill/mse.quill.wear.DueTileService`, and it renders the correct never-synced
state under the system's Material 3 theme. (`show-tile` is *not* a recognised operation on this
build — swipe left from the watch face instead.)

**Not verified, and the reason is worth writing down: the phone↔watch round trip needs the
emulators paired.** The publish path runs to the GMS boundary and fails there with
`ApiException: 17 — Wearable.API is not available on this device`, because the phone emulator has no
Wear OS companion app. Pairing needs the companion app from Play and a Google sign-in on the phone
emulator, which is the user's credential, not something automatable. So the decode half of
`DueProjectionClient` and every non-empty tile state are **written but unexercised**.

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
- **Study logic goes in `:shared`, not `:app`.** Anything that is pure state transitions over
  cards or questions — scheduling, session progression, generation — belongs in the plain-JVM
  module, and the compiler will tell you if it doesn't (no Android classpath in there). Anything
  touching SQLite, a `Context` or a view stays in `:app`. Tests for the former are ordinary JUnit
  under `study/src/test` and need no device.
- Soft-delete via a `deleted_at`/similar timestamp column, not hard `DELETE`, for
  anything user-facing (matches `notes.deleted_at`).
- IDs are `UUID.randomUUID().toString()` throughout, not autoincrement — consistent
  with the schema anticipating multi-device sync later.
- **Verify on the emulator (`ANDROID_SERIAL=emulator-5554`), not the phone**, even when both are
  attached — the phone is the user's. Its screen also sleeps within seconds, and `screencap`
  returns solid black while the display is off, which reads as a crash if you only sample pixels.
- Sizing wrappers for oversized PNG icons: a `layer-list` with `android:width/height` (see
  `drawable/ic_section_note.xml`). The raw assets are 256-1024px and cannot be compound drawables
  directly.
- `aapt` strips leading/trailing whitespace from a string resource unless the value is quoted —
  `<string name="count_separator">" · "</string>`.

## Home-screen App Widgets

**Status: built 2026-08-14, extended 2026-08-15.** Three Android launcher widgets,
`mse.quill.widget` package — Quill's first use of `AppWidgetProvider`/`RemoteViews` (the
"Epic I home-screen widget" mentioned in [requirements.md](requirements.md)'s
flashcard-reminders section was a forward reference to this; Epic I itself is unrelated
whiteboard work — see that file for the correction).

- **Collections widget** (`CollectionsWidgetProvider`) — pinned notes (fixed-height block,
  since `NoteRepository.MAX_PINNED_NOTES` caps it at 3) stacked above a scrollable collections
  list. Two `ListView`s, each needing its **own** `RemoteViewsService`
  (`PinnedNotesRemoteViewsService`, `CollectionsRemoteViewsService`) — one service can only
  feed one collection view. Locked collections (`Collection.biometricLocked`) are filtered out
  before rendering: a widget has no way to gate behind `BiometricPrompt`, so a locked
  collection's name/count would otherwise leak onto the home screen unlocked.
- **Whiteboards widget** (`WhiteboardsWidgetProvider` / `WhiteboardsRemoteViewsService`) — a
  `GridView` of recent boards with thumbnails.
- **Flashcards widget** (`FlashcardsWidgetProvider`, added 2026-08-15) — due-now cards (front
  text only, `DueCardsRemoteViewsService`) stacked above a deck list
  (`FlashcardDecksRemoteViewsService`), weighted **3:2 (60/40)** rather than the Collections
  widget's fixed-height-for-the-short-section approach — due cards are the reason to open this
  widget, so they get the larger, still-proportional share. `FlashcardRepository` gained
  `loadDecksSync()` (sync twin of the existing `loadDecks`) and a new
  `loadDueCardsSync(now, limit)` — no prior query returned individual due cards, only
  `countDueSync`'s counts. Tapping either section opens the tapped note's flashcard review
  screen via a new `EXTRA_OPEN_FLASHCARD_NOTE_ID` — deliberately separate from
  `EXTRA_OPEN_NOTE_ID`, which opens the note *editor*, not the review screen.

**All three widgets carry `android:previewLayout`** (added 2026-08-15, API 31+) pointing at
their own root layout, so the widget picker renders the real layout instead of a generic icon.
`android:previewImage="@mipmap/ic_launcher"` stays as the fallback below API 31 — there's no
screenshot asset in the repo to use instead.

**Reused rather than rebuilt**: each repository already had (or gained) a synchronous
`load*Sync()` twin of its async method — `NoteRepository.loadPinnedNotesSync`,
`CollectionRepository.loadCollectionsSync`, `WhiteboardRepository.loadWhiteboardsSync`,
`FlashcardRepository.loadDecksSync`/`loadDueCardsSync` — safe to call from a
`RemoteViewsFactory`, since Android already runs those calls off the main thread.
`WidgetUpdater.notifyCollectionsChanged`/`notifyWhiteboardsChanged`/`notifyFlashcardsChanged`
are called from the existing pin/unpin, collection/whiteboard CRUD, and
`recordReview`/`syncFromNote`/`deleteForNote` methods to push live refreshes
(`AppWidgetManager.notifyAppWidgetViewDataChanged`) rather than polling on a timer.

**Thumbnails needed a second cache.** `WhiteboardThumbnails` renders asynchronously through a
live `WhiteboardView` on the main thread and only ever hands the bitmap back via callback —
there is no synchronous path, but `RemoteViewsFactory.getViewAt()` must return synchronously
from a background binder thread with no view hierarchy to render through. `WidgetThumbnailCache`
mirrors every render `WhiteboardThumbnails` produces to a PNG under
`getCacheDir()/widget_thumbs/`, and the widget factory reads that file synchronously
(`BitmapFactory.decodeFile`), falling back to a placeholder for a board that's never been opened
in-app since install.

**Deep-linking a tap.** `MainActivity` gained the `EXTRA_OPEN_NOTE_ID`/`_COLLECTION_ID`/
`_WHITEBOARD_ID` extras (same shape as the existing `EXTRA_OPEN_FLASHCARDS` reminder-tap
pattern) and a `handleWidgetIntent`/`runWhenNavHostReady` pair. Each row's `RemoteViews` carries
a `fillInIntent` with the relevant extra; the provider sets one `PendingIntent` **template**
per collection view (`setPendingIntentTemplate`) rather than one per row.

**Three non-obvious bugs found the hard way, worth not rediscovering:**
1. **A plain `<View>` isn't in RemoteViews' inflatable-class allowlist.** Only specific widget
   classes (`TextView`, `ListView`, `FrameLayout`, `LinearLayout`, …) are inflatable inside a
   home-screen widget; a bare `android.view.View` (used as a divider) throws at inflation and
   the whole widget falls back to Android's "Problem loading widget" placeholder. Fixed by
   using a 1dp-tall `TextView` with a background color instead.
2. **The `PendingIntent` template needs `FLAG_MUTABLE`, not `FLAG_IMMUTABLE`.** The
   template + per-row `fillInIntent` mechanism requires the system to merge each row's extras
   into the template at click time; an immutable template silently refuses that merge, so every
   row still fires the same bare intent with none of its extras — the app opens, but always
   lands wherever `MainActivity` was left, never on the tapped item.
3. **Waiting for `HomeFragment` to resume isn't enough on its own** — nothing was actually
   driving the nav host back to Home if the user was already elsewhere in the app, so the wait
   callback registered and then hung forever. `runWhenNavHostReady` now forces
   `popBackStack(R.id.homeFragment, false)` first, the same call `handleViewIntent` already
   used for the identical reason on a shared-file import.

**No on-device testing tooling in this environment** — `adb` isn't on PATH here, so every fix
in this feature was verified by code review + a clean `assembleDebug`/`compileDebugJavaWithJavac`
build, then confirmed against real device behavior by the user afterward. If `adb` becomes
available, prefer it for anything widget-related; RemoteViews failures in particular
(the allowlist crash above) are much faster to diagnose from `logcat` than from symptom
descriptions alone.

### Widgets vs. collection locking — a real bug chain, found 2026-08-16

Locking a collection with pinned notes and the widget on the home screen surfaced a chain of
four distinct bugs, three of them **pre-existing and independent of the widget feature** — the
widget was just the first thing to reach these code paths from outside the normal in-app flow.
Fixed in `95859d5` plus one follow-up (`PinnedNotesRemoteViewsService`, still uncommitted as of
this entry). Worth reading in full before touching lock/widget code again:

1. **`RemoteViewsFactory` runs on a binder thread inside this app's own process.** Unlike almost
   everywhere else in a widget, an uncaught exception there crashes the *whole app*, not just the
   widget's rendering. None of the six factories had any exception guarding. All now catch
   `RuntimeException` in `onDataSetChanged`/`getViewAt`/`getItemId` and fall back to an empty
   list / blank row rather than propagate. Didn't turn out to be the actual crash cause here, but
   is a real gap that would have bitten some other data edge case eventually — worth keeping for
   any future `RemoteViewsFactory`.
2. **`CollectionLockRepository.lock()`/`unlock()`/`discardUnreadable()` never told the widgets
   anything changed.** The widget kept showing whatever `RemoteViews` rows it built *before* the
   lock, including the collection and its pinned notes, until something else happened to trigger
   a refresh. Fixed by adding `WidgetUpdater.notifyCollectionsChanged`/`notifyFlashcardsChanged`
   calls to all three methods (flashcards too — `writeNotes(locked=true)` deletes the collection's
   flashcards, so the flashcards widget goes stale the same way).
3. **The actual app-crashing bug, and it predates the widget entirely**:
   `NoteRepository.createNote()` writes a new note's title as **plaintext unconditionally**, even
   when the target collection is locked — it only ever encrypts on the *next* `saveNote()` call,
   never on creation. The very next autosave then tries to `Base64.decode()` that plaintext title
   as if it were ciphertext (in `saveNote`'s "did anything actually change" check), which throws
   `IllegalArgumentException` — a `RuntimeException` that
   `NoteCrypto.decryptTitleOrNull`/`decryptBodyOrNull` didn't catch (only the checked
   `GeneralSecurityException`), despite both methods' entire contract being "never throw, return
   null instead". Uncaught on the disk-IO thread → whole process dies, repeatedly, on every
   subsequent launch too (nothing about the broken state self-heals on restart). Fixed by
   widening both catches to `GeneralSecurityException | RuntimeException`. **Not fully fixed**:
   `createNote` itself still writes plaintext into a locked collection for the brief window
   before the follow-up `saveNote` call encrypts it — low real exposure (same disk-IO executor,
   nothing reads between the two queued calls) but worth closing properly if this file is touched
   again, by having `createNote` encrypt up front when `NoteCrypto.isLocked` is true.
4. **`NoteEditorFragment.loadExistingNote()` couldn't tell "note doesn't exist" from "note exists
   but is currently locked and unreadable".** Both came back as `note == null` from
   `NoteRepository.loadNote`, and the fragment treated either case as "blank, start typing" —
   marking itself `contentLoaded = true` regardless. The next autosave then saw an empty title
   and no segments and ran the **delete-empty-note-on-exit** path from `autoSave()` — on a real,
   still-encrypted note, not an actually-empty one. Fixed narrowly: `loadExistingNote()` only
   ever runs for a real pre-existing `note_id` (a genuinely new note skips it, see
   `onViewCreated`), so a `null` result there is now unambiguous — show
   `R.string.note_unavailable_locked` and pop back immediately, `contentLoaded` never set, so
   `autoSave()`'s first line (`if (!contentLoaded) return;`) refuses to run at all for that
   fragment instance.
5. **Pinned notes ≠ collections for lock visibility, and shouldn't have been.**
   `CollectionsRemoteViewsService` already excluded every `biometricLocked` collection
   unconditionally (session state or not) — that reasoning was written down explicitly in this
   file already. `PinnedNotesRemoteViewsService`, though, just called
   `NoteRepository.loadPinnedNotesSync()` as-is, which hides a locked collection's notes only if
   the collection *isn't unlocked this session* (`NoteCrypto.hiddenOf` → `CollectionLock
   .isUnlocked`) — correct for an in-app screen the user is actively looking at, wrong for a
   widget that can't represent "I already authenticated" at all. And `lock()` itself calls
   `CollectionLock.markUnlocked` right after locking (so the user isn't immediately re-prompted)
   — meaning the pinned note stayed visible in the widget for as long as the app session
   considered the collection open, i.e. until the app was backgrounded. Fixed by having the
   factory cross-check each pinned note's `collectionId` against `CollectionRepository`'s
   `biometricLocked` flag directly and drop it if locked, mirroring the collections list's own
   session-independent filter. `FlashcardRepository.dueProjectionSync` (the Wear OS watch
   projection, see below) already got this right from the start — worth pattern-matching next
   time rather than rediscovering it.

**Debugging note**: bugs 3 and 4 were found by tracing the actual code paths (`autoSave`'s
delete-on-empty branch, `NoteRepository.createNote`'s plaintext write, `NoteCrypto`'s narrow
catch clauses) after the user's symptom reports, not from a stack trace — `adb` wasn't available
in this environment for most of the session. `adb logcat -d *:E > crash.txt`, then search for
`FATAL EXCEPTION`, is the fast path if this happens again and a device is reachable.


## Structure after the 2026-08-26 refactoring pass

Seven of the eight items in `refactoring_plan.md` landed in one pass (R1, R2, R3, R5, R6
steps 1–3, R7a, R8). What changed structurally, for someone reading the tree cold:

**The whiteboard screen is two objects, not one.** `WhiteboardFragment` owns the canvas and
the database; `WhiteboardCollabController` owns the network — hosting, joining by scan, the
roster, snapshot reassembly, error mapping. They meet at `WhiteboardCanvas`, a five-method
interface (`applyStroke`, `applyText`, `retract`, `clearAll`, `replaceAll`) whose implementors
own persistence. The fragment does not import `CollabMessage`. Snapshot reassembly lives on the
controller, so a half-received board is no longer held in fragment fields that Android may
destroy mid-transfer.

**`data/` is the bottom of the stack and imports nothing above it.** The rule, worth keeping:
**`data/` may not import `ui/` or `widget/`.** Two things enforce it:

- the note segment model (`NoteSegment` and its six subclasses) lives in `data/model/`, not
  `ui/notes/editor/model/` — persistence, the Markdown format and the `.quill` bundle format
  all depend on it, so it is the domain, not a screen's;
- repositories no longer call `WidgetUpdater` inline. They call
  `DataChangeNotifier.getInstance().notifyChanged(Change.X)`, and `WidgetUpdater` subscribes
  once from `QuillApplication.onCreate` and decides for itself which widget that means.

The one remaining `ui` import in `data/` is `QuizRepository → mse.quill.ui.quiz`, which is a
package name lying about pure logic in `:shared` (R7b), not a layering violation.

**`QuillApplication` exists now.** It is the only process-wide wiring point; a widget's
`RemoteViewsService` or a Wear message can write data without `MainActivity` ever starting,
which is why the subscription cannot hang off the activity.

**Wear transport is `data/wear/`,** not eight `Wear*` files loose in `data/`. Their five
manifest `<service>` entries name the new package. Note that the `*Keys` classes they speak in
live in `:shared`, in package `mse.quill.data` — the split package from Epic J.

**`MainActivity` routes nothing.** `DeepLinkRouter` owns widget taps, the reminder
notification, `.quill` files opened from outside, and `quill://` session links, along with the
awkward state all of that needs (`pendingImportUri`, `viewIntentConsumed`, `pendingJoinToken`
and their saved-instance-state). The activity drives it through `onCreate`, `route`,
`onNewIntent`, `onSaveInstanceState`, `onUnlocked`. The six `EXTRA_OPEN_*` constants moved with
it, so widget code names `DeepLinkRouter`, not `MainActivity`.

**Read paths say so.** 28 read-only database accesses use `getReadableDatabase()`; the ~40 that
write still say `getWritableDatabase()`. SQLite hands back the same connection either way — this
is so a reader can tell the two apart at a glance.

**A callback-taking method must never invoke its callback before it returns.**
`CollectionRepository.isLocked` was the one violation and is fixed; the rule is written into
its comment.

## Drawables — the convention

`res/drawable/` was one flat folder of 95 files doing four different jobs, which is what made
"some are XML, some are PNG" feel arbitrary. It isn't a format question; it's a job question.

| Job | Where | Format |
|---|---|---|
| Shapes, gradients, selectors, layered backgrounds | `drawable/` | XML — no choice |
| Icons | `drawable/` | **vector XML**, `ic_*`, 24dp viewport |
| Raster icons not yet converted | `drawable-xxxhdpi/` | PNG, `ic_*` |
| Size-pinning wrappers around raster icons | `drawable/` | `<layer-list>` |
| Launcher assets | `mipmap-*` / `drawable-*dpi` | as generated |

**New icons go in as vectors.** That is the whole rule. A vector reports its own size, tints
cleanly, and costs a kilobyte.

**Why the PNGs moved to `drawable-xxxhdpi/` (2026-08-26).** `res/drawable/` with no qualifier
means **mdpi**, so Android treats a 1024px PNG as being 1024dp and upscales it for the device.
`ic_mic.png` was decoding to 3072×3072 — **37.7 MB of heap** — to be drawn at 40dp. In
`drawable-xxxhdpi/` the same file decodes to 768px, 2.4 MB, and is downsampled rather than
upscaled. Nothing on screen changed: every one of the 41 icons is drawn at a fixed size (a layout
dimension, a style, or one of the wrappers), so none of them was consulting its intrinsic size.

`ic_stars.png` is the exception and lives in `drawable-xxhdpi/`: at 58×62 px it is genuinely a 3x
asset for the 20dp box it sits in, and calling it 4x would have it upscaling.

**Lint's `IconDensities` is switched off** in `app/lint.xml`, with the reasoning in the file. It
wants five copies of every icon; that is right for a per-density icon set and wrong for single
high-resolution exports drawn at 18–40dp.

**The 14 `<layer-list>` wrappers** (`ic_menu_*`, `ic_section_*`, `ic_option_*`) exist because a
menu item or a compound drawable asks a drawable how big it is and believes the answer. They are
a symptom of the icons being raster — converting an icon to a vector deletes its wrapper.

**Known, not fixed:** `ic_stop.png` has no references. `ic_mic` and `ic_flashcard` are used as
notification small icons and lint flags them as not entirely white (`IconColors`) — the system
uses only the alpha channel, so they render, but a white-on-transparent asset is the correct thing
there.


## The `:shared` module (renamed from `:study`, 2026-08-28)

`:shared` is the one module both `:app` and `:wear` depend on, and that — not studying — is what
decides what belongs in it. It was called `:study` because SM-2 was the first thing extracted, and
the name then quietly authorised putting the phone↔watch wire protocol there too.

    mse.quill.sync              the 7 *Keys classes: the Data Layer wire protocol
    mse.quill.study.scheduling  FlashcardScheduler, DueProjection
    mse.quill.study.review      ReviewSession
    mse.quill.study.quiz        QuizGenerator, QuizQuestion, QuizRules, QuizSession
    mse.quill.data.model        Flashcard, DueCard

**`mse.quill.data.model` is deliberately shared with `:app`** and is the only split package left.
`Flashcard` and `DueCard` are domain models and belong beside `Note` and `Stroke`; splitting them
by module would be splitting them by build graph rather than by meaning.

The module is Android-free and JVM-tested, which is what lets `:wear` reuse SM-2 rather than
reimplement it. Keep it that way: anything needing an Android API belongs in `:app`.
