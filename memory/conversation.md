# Quill — Conversation Log

A running record of the sessions that shaped this project: what was asked, what options were
weighed, and what got decided. Kept alongside [note.md](note.md) (how the code is built) and
[requirements.md](requirements.md) (what's still to build) — this file is the *why we got here*
that neither of those captures, and that git history flattens away.

Oldest first. Entries through 2026-07-28 were reconstructed from the stored session transcripts,
so they reflect what was actually said rather than an inference from commits.

## How to maintain this

At the end of each session, append one `## <date> — <category>: <topic>` entry per distinct topic.
Summarize the ask (don't quote it in full), the options weighed and which was chosen, and the
decision reached. Categories in use: **Architecture decision**, **Feature implementation**,
**Bug fix**, **Refactor**, **Research**.

Keep entries skimmable — this is an index of decisions, not a transcript. Never invent an entry: if
a past session needs logging and isn't in context, its real transcript is on disk under
`~/.claude/projects/<project-slug>/*.jsonl` (one JSONL per session; human turns are `type=="user"`
with `origin.kind=="human"`). Read it rather than reconstructing from git history.

---

## 2026-07-11 — Research: architecture review before extending

**Asked:** is the project architecturally sound, given plans to add audio embedding, a local
database, and collections.

**Verdict given:** the segment-based editor is genuinely well-shaped — `NoteEditorView` orchestrating
`TextSegmentView`/`ImageSegmentView` through a `BaseSegmentView.SegmentCallback` is a clean composite
pattern, and `NoteSegment.TYPE_AUDIO` was already reserved so audio should drop in by mirroring the
image path. `ImageEmbedder` was doing the right Android things (private app storage, downsampled
decoding).

**But:** persistence was a facade — the thing all three planned features depend on.

**Decided:** fix persistence before extending.

## 2026-07-12 — Feature implementation: local persistence

**Asked:** save notes and collections locally on device before anything else gets built.

**Found first:** the app crashed on launch on the emulator — pre-existing, not from this work.
`AppDatabase.onCreate()` unconditionally created an FTS5 virtual table and that SQLite build has no
FTS5 module, so the whole DB failed to initialize and nothing could ever persist. Made it non-fatal.

**Built:** `AppExecutors` (shared background thread + main dispatch), `NoteRepository` /
`CollectionRepository`, `SpanSerializer` (formatting round-trip via `Html.toHtml`/`fromHtml`),
`Note`/`Collection` POJOs; `loadSegments()`/`exportSegments()` on the editor; lazy note creation on
first real content plus debounced autosave. Soft delete for notes, nullify-then-delete for
collections. Verified end-to-end on an emulator including a full app restart.

## 2026-07-12 — Feature implementation: home redesign + collections navigation

**Asked:** collections aren't visible anywhere; match `ui.png`, separate notes from collections, add
a collection detail screen with move in/out.

**The hard part wasn't the design.** Any custom layout inflated into a `RecyclerView` — even a bare
`TextView` — crashed with "You must supply a layout_width attribute" despite valid XML. Ruled out my
own code across many attempts: different root view types, different LayoutManagers, clean builds, a
full emulator data wipe. Root cause was the environment running a bleeding-edge preview SDK
(`compileSdk 36.1`), breaking `LayoutInflater`'s attrs-based `LayoutParams` path.

**Decided:** build the four RecyclerView item views programmatically instead of via XML. Justified as
extending an existing working pattern in this codebase (`FormattingToolbarController`,
`CollectionDialogs` already do this), scoped only to item views — the five real screens stay XML.

## 2026-07-12 — Bug fix: forced light theme, removed system title bar

**Asked:** app goes dark with system dark mode; also drop the "Quill" title bar.

**Done:** `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_NO)` plus switching the theme parent from
`DayNight` to `Light` so there's no dark path at all; deleted `values-night/`. Theme parent moved to
`…Light.NoActionBar` since every screen already has its own header. Verified by forcing the emulator
into dark mode.

## 2026-07-13 — Feature implementation: pinning, tags, home sections, editor formatting

**Asked (batched over several turns):** drop the "Uncategorized" pseudo-collection, pin/unpin notes
(max 3), tags with reusable-or-new + colors, home sections ordered pinned → recent → search →
collections → notes as cards per `ui.png`, "Untitled Note - <date>" fallback titles, a split FAB for
note-vs-collection, a collection page per `collection-page.png`, plus heading sizes and lists in the
editor.

**Notable fixes along the way:** notes disappearing from the list once pinned or added to a
collection; tag chips changing height on long names; the note-title `EditText` stealing focus and
highlighting while typing in the body.

## 2026-07-13 — Bug fix: keyboard/scroll saga (reverted)

**Asked:** the keyboard covers the active line; auto-scroll doesn't bring it into view.

**What happened:** roughly ten rounds of attempts — `fullScroll`, several custom cursor-position
scroll calculations, `WindowInsetsCompat` IME padding, `adjustResize`, then `adjustPan`. Real
discoveries were made (`fillViewport="true"` made `content.getBottom()` meaningless; `targetSdk 36`
means Android 15+ **enforces** edge-to-edge regardless of `EdgeToEdge.enable()`, so `adjustResize`
could never have worked and removing the call only stripped the compensating padding). Each fix
traded one symptom for another — toolbar detaching from the keyboard, short notes flying off-screen,
the title getting pushed out of view.

**Decided:** undo everything scroll-related from the start. Reverted precisely, keeping
headings/lists, tap-to-focus and the newline-preservation fix.

**Lesson worth keeping:** this ran far too long on a symptom that couldn't be observed directly. Ask
for a differentiated repro (which finally arrived as "long page works, bottom section doesn't") much
earlier, and stop after two or three failed attempts at the same mechanism.

## 2026-07-14 — Feature implementation: audio embedding, waveform, read-aloud

**Asked:** record audio from the formatting bar and play it back; then a modal recording dialog with
a timer and a live waveform; then transcripts; then a read-aloud button by the title.

**Choices:**

- **Waveform** — polled `MediaRecorder.getMaxAmplitude()` every ~100ms drawn as scrolling bars. Not a
  true FFT waveform, but it's how most voice-memo apps do it and reads as one.
- **Transcripts** — flagged upfront that Android can't transcribe a recorded file, only live-mic, one
  utterance at a time; chained recognition sessions for the recording's duration, degrading silently
  if no recognition service exists. A real race surfaced on self-review: waiting on the async
  transcript before inserting the segment meant backgrounding the app right after Stop could lose the
  recording entirely. Fixed by inserting immediately and attaching the transcript after.
  **The transcript feature was then undone.**
- **Read-aloud voice quality** — auto-select the highest-quality *offline* voice on init, plus a
  long-press voice picker (matching the long-press-for-options pattern audio segments already used).

**Also fixed:** read-aloud button showing on empty notes (the prefilled title was counting as content
— now only body text drives it), tag option only appearing on title focus, "New note" header removed,
long-press to delete images/audio since there's no keyboard gesture for it.

## 2026-07-26 — Feature implementation: architecture and requirements documentation

**Asked:** write a note.md capturing architecture and design decisions for future context; then a
requirements doc from `one-pager.pdf` with prioritized epics.

**Choices:** [note.md](note.md) explicitly separated what's *actively used* from forward-looking
schema scaffolding (`flashcards`, `voice_memos`, `outbox`, `vector_clock`, `notes_fts`) so a future
cleaner wouldn't read it as dead code, and documented the two `SpanSerializer` workarounds with
reasoning. [requirements.md](requirements.md) ordered 7 epics by architecture/dependency rather than
feature appeal — foundations and data safety first, then biometric-locked collections before P2P sync
so locked content is never shared unencrypted.

**Then:** moved both to `documentation/`, committed and pushed to `documentation/claude-note`.

## 2026-07-26 — Architecture decision: standardized note format (Markdown)

**Asked:** store notes in a standardized cross-platform format supporting headings/bold/italic/lists,
image and audio embedding, later whiteboard embeds, and a Q&A note style.

**Recommended:** Markdown (CommonMark) as the canonical stored format — closest thing to universal,
human-readable, diffable, and Markwon exists on Android with a plugin/AST architecture suited to
custom block types. Stressed that this changes only the serialized bytes, not the WYSIWYG editing UX.

**Decision left open at the time:** minimal-vs-full storage scope. Settled on 2026-07-28.

## 2026-07-26 — Architecture decision: flashcards and quizzes

**Asked:** Q&A embedded in notes, with a "Create flashcards for this note" action extracting them
into a separate page — is that good UX? Then: how to do quizzes with scores, given free-text answers
are hard to grade ("Personal Computer" vs "PC" vs "computer"), maybe using AI to generate MCQ options.

**Advised:** keep Q&A as an explicit visible segment rather than Anki-style inline cloze — it fits the
existing typed-segment architecture and matches how students write revision notes. Explicit
user-triggered generation beats anything automatic firing on save.

On quizzes, pushed back on the AI idea on two grounds: unpredictable quality, and calling a cloud AI
per question cuts against the offline-first, privacy-conscious positioning in the one-pager. Reframed
the problem — free-text matching doesn't need solving at all. Spaced-repetition review is self-rated
(the human is the judge, no string matching ever); only *quizzes* need real grading, and those can be
auto-graded locally via MCQ built from **cross-card distractors** (wrong answers pulled from other
cards in the same scope), with True/False as fallback for small scopes.

**Decided:** cross-card distractors, plus manual vs automatic flashcard sync as a user choice so a
review session never gets polluted mid-flight. Both docs updated.

**Bug found while designing:** segment `id`s were never preserved across saves (`exportSegments()`
never set `NoteSegment.id`), harmless then but would have silently broken flashcard↔segment linking.
Fixed on 2026-07-28 as part of the Markdown migration.

## 2026-07-26 — Research: connecting Figma

**Asked:** how to link Figma for UI designs; then pointed at the MSE file.

**Decided:** the MSE file (`OtDObKE7SPq4XC55lPdkvz`) is the default whenever "the Figma file" comes
up — recorded in [references.md](references.md). The `documentation/` folder was renamed to `memory/`
in the same session.

## 2026-07-26 — Architecture decision: adopt Material 3

**Asked:** how is the UI built right now, and would Material Design be better?

**Answered:** already on `Theme.MaterialComponents.Light.NoActionBar` with some MDC widgets, but
everything below screen level was hand-built Java views with hardcoded values. Recommended leaning in
fully — move to `Theme.Material3.*` and have custom views pull from M3 color/typography/shape tokens.
No dependency bump needed; `material 1.10.0` already had M3.

**Decided:** do theme/color plus one component as a POC (`TagChipView` → M3 `Chip`), and log the rest
as Epic H for the next session.

**Side note:** the "error in AppDatabase.java line 132" turned out to be Android Studio's SQL
language-injection inspector not understanding FTS5 virtual-table module arguments — not a real
compiler or runtime error.

## 2026-07-27 — Feature implementation: full Material 3 migration

**Asked:** migrate the UI to Material 3 following the planned epic, using the Figma for context.

**Done:** `NoteRowView`/`CollectionCardView`/`PinnedNoteCardView` → `MaterialCardView` via a shared
`applyFlatCardStyle`; cards styled *flat* (elevation 0, stroke 0) because the MSE Figma draws every
card as a plain fill with no shadow — sampled its grey into a new `surface_container` `#F5F6FA`.

**Biggest win:** all 13 dialog sites → `MaterialAlertDialogBuilder`. Most were on the *framework*
`android.app.AlertDialog`, which ignores app theming entirely, so they stayed visibly Material 2
(square corners, ALL-CAPS buttons) even after the theme switch.

**Then:** search fields → outlined `TextInputLayout`, Playfair Display for "Welcome back" (variable
font, fine at minSdk 26), and the home header curve corrected — it had been curving the wrong way.
The Figma layers a full-bleed gradient behind a content sheet that curves *over* it, so the header
lost its corners and a new `bg_content_sheet` got rounded top corners with a −56dp overlap.

**Decided:** every UI from here on uses Material 3.

## 2026-07-27 — Bug fix: camera crash in notes

**Asked:** app crashes opening the camera from a note.

**Root cause:** no `<provider>` declared in the manifest at all, and no `file_paths.xml`, so
`FileProvider.getUriForFile(...)` threw `IllegalArgumentException` — which the surrounding
`catch (IOException)` couldn't catch, so it propagated and killed the app. Pre-existing, untouched by
the migration.

## 2026-07-28 — Architecture decision: Markdown note storage

**Asked:** how switching to Markdown-based notes would affect segments, and whether image / audio /
whiteboard embeddings would still be possible.

**Presented two routes.** A: Markdown as text serialization only, keep segment rows — embeds
untouched since they were never in the text stream. B: Markdown as the whole note in
`notes.content_blob`, `note_segments` demoted to a media asset registry, segments become a view
concept parsed out of the document.

Key framing: Markdown doesn't delete segments, it moves where they live (storage → view). All three
embeds survive either way; whiteboard is unaffected because it isn't a segment today. Tension
identified — B unlocks FTS/export/preview off one field and retires both `SpanSerializer` hacks, but
A keeps finer merge units for the sync that `vector_clock`/`outbox` anticipate.

**Decided:** Route B, keeping `note_segments` as the media asset table. Sync granularity knowingly
traded away; the plan is to recover it later by block-level diffing of the Markdown rather than
reverting to rows.

## 2026-07-28 — Feature implementation: Markdown migration

**Asked:** proceed with Route B.

**Built:** `MarkdownSerializer` (Spannable ↔ Markdown), `NoteDocument` (segment list ↔ document, plus
plain-text/preview projections), `HeadingMarker` (shared, so the data layer needn't import a `View`).
Deleted `SpanSerializer`. Schema to v3: `note_segments` lost `text_content`/`position`; `notes_fts`
fixed from a never-populatable `content='notes'` table to standalone and maintained on save/delete.
`loadNote`/`saveNote` signatures unchanged, so no fragment was touched.

**Choices worth remembering:**

- Embeds reference assets by **id, not path** (`quill://image/<id>`), so moving media on disk can't
  invalidate a document. Metadata Markdown can't hold (width, duration, transcript) stays on the
  asset row and is rejoined by id. This also fixed the segment-id instability found on 2026-07-26.
- Italic is `_`, not `*`. Markdown needs proper nesting, so a format ending inside another forces a
  close-then-reopen; with `*` for italic that emits ambiguous `****`/`*****` runs. Cost is escaping
  literal underscores.
- The decoder coalesces abutting same-format spans, so a save/load cycle is structurally identical
  rather than fragmenting spans a little more each time.
- A dropped embed (missing asset row) merges the surrounding text into one segment, preserving the
  invariant that two text segments are never adjacent.

**Verified:** 42 instrumented tests (serializer, document, repository round-trip) run on emulator and
device. Two real bugs were caught by them, not by reading.

**Flagged, not done:** `onUpgrade` is still the destructive drop-all documented as dev policy, so
existing notes are wiped on next launch — a real v2→v3 migration was offered but not requested.
Search UI still filters in memory; only the index is maintained.

## 2026-07-28 — Bug fix: bold lost on the first character of a new line

**Asked:** toggling bold, pressing Enter, then typing leaves the first letter unbolded — reported as
affecting the other styles too.

**Found:** `restyleHeadingLine` identified its own derived heading-bold span by *bounds*
(`spanStart == lineStart && spanEnd == lineEnd`). The first character typed on a line produces a user
bold span with exactly those bounds, so the restyle pass deleted it. Bold only — the removal loop
only ever matched `StyleSpan(BOLD)`, so italic and underline were never affected.

**Fixed:** derived spans tracked by identity (`IdentityHashMap`-backed set), cleared in `setText`.
Kept the existing constraint that heading bold stays a plain `StyleSpan`, not a custom subclass.

**Corrected the report:** italic/underline verified working with new tests rather than assumed broken.
Remaining gap noted — the repro drives the `Editable` directly and doesn't exercise a real IME's
composing-text path.

## 2026-07-28 — Feature implementation: this conversation log

**Asked:** keep a file summarizing each conversation — the ask, the choices, what was decided, and a
category. Start with the sessions in context; then, on learning only the current session was
available, log the earlier ones anyway.

**Resolved by:** finding the stored session transcripts on disk — 8 prior sessions — and
reconstructing entries from what was actually said rather than inferring from git history.

**Correction:** the log was first written to Claude's private auto-memory directory instead of this
repo's `memory/` folder, so it wasn't visible in the project. Moved here, alongside note.md,
requirements.md and references.md, which is what "in memory" meant.

## 2026-07-29 — Bug fix: editor doesn't scroll to segments near the end of a note

**Asked:** revisit the keyboard/scroll bug from 2026-07-13 — tapping a segment near the bottom of a
note still leaves it hidden behind the keyboard.

**Root cause (found by reading, not guessing):** `formattingToolbar.setTranslationY(-height)` is a
*visual* transform. It moves pixels but not layout bounds, so the `ScrollView` — constrained
`bottom_toTopOf` the toolbar — kept full-height bounds and a viewport that nominally extended behind
the keyboard. Android's native reveal pass found the tapped segment already inside those bounds and
correctly decided no scroll was needed. Every earlier attempt (`fullScroll`, custom cursor maths,
`adjustResize`, `adjustPan`) failed identically because none of them fixed the lie in the layout.

**Fixed:** reserve the space as **bottom padding on the fragment root** (a layout change, so the
viewport becomes truthful and the toolbar lands above the keyboard by its existing constraint), plus
one explicit `revealFocusedInput()` after the resize — with edge-to-edge enforced at `targetSdk` 35+
the window never resizes, so `ViewRootImpl`'s keep-focus-visible pass never runs. No custom scroll
arithmetic, which was the whole problem last time.

**Verified on-device this time** — the missing ingredient in July's attempt, which ran blind. Four
cases checked: tap last line of a 30-line note, dismiss/restore, short note (the regression that
broke the previous attempt), and typing at the bottom.

## 2026-07-29 — Feature implementation: formatting toolbar restyle

**Asked:** the toolbar feels disjointed from the keyboard — can it look like part of it, and can we
read the keyboard's colour? Then: much smaller buttons, use the icons in `res/drawable`, and show
active state as a small primary-coloured dot under the icon.

**Answered on colour: no.** The IME renders in its own window in another process; there's no public
API for its theme, you can't screenshot outside your own window, and with custom keyboard themes it
isn't a stable target anyway.

**Decided (asked, not assumed):** given most keyboards follow the *system* theme while Quill is
pinned light, you chose "keep it light, make it flush" over tracking the system theme — preserving
the always-light decision from 2026-07-12.

**Built:** items shrank ~83dp → equal weights across the full bar width; plain AppCompat `Button`s
(the last non-M3 thing in the editor) replaced by composite icon items; one continuous
`surface_container` surface with a hairline top edge and no bottom treatment, since the keyboard
provides that boundary.

**Two bugs surfaced while wiring the dots:** `updateState()` didn't even take heading/bullet
parameters, and the H1/H2/bullet handlers never called it — so those three *couldn't* light up.
Fixing that alone wasn't enough: bold/italic/underline are a pending typing mode, but heading and
bullet describe the caret's line and go stale on any caret move. Added a selection-change signal,
kept deliberately separate from the content-change listener so a caret move doesn't schedule a save.

**Not changed:** B/I/U still reflect typing mode, so moving the caret into existing bold text doesn't
light B. Pre-existing; changing it would alter how the toggles work.

## 2026-07-29 — Bug fix: image rotation, sizing, and a viewer

**Asked:** camera photos come out rotated; cap the height of inserted images; tapping an image should
open a popup over a blacked-out background with download and delete.

**Rotation:** classic EXIF — cameras record orientation in a tag rather than rotating pixels, and
`BitmapFactory` ignores it. Normalised **once on ingest** (rotate, rewrite upright, bound to 2048px)
so no consumer downstream needs to know EXIF exists. Two things found while doing it: gallery picks
had the same bug (importing copies the tag verbatim), and `ImageSegmentView` was decoding at full
resolution with no sampling — the actual reason big images blew up.

**Viewer:** a bare `Dialog` over a scrim rather than a MaterialAlertDialog, whose inset card fights
an edge-to-edge image. Export goes to `Pictures/Quill` via MediaStore, with the runtime permission
(needed below API 29) routed up to the fragment because a segment view can't request one.

**Iterated after review:** neither action closes the viewer any more — save reports back into the
dialog, delete closes only once confirmed. Feedback had to move *inside* the dialog's window; a
Snackbar on the editor root sits behind it and is never seen. Also: exported files are named
`Quill_<timestamp>.jpg` rather than the internal `img_<uuid>.jpg`, and inline images are centred
(needs both `FIT_CENTER` and container gravity, since `adjustViewBounds` shrinks the view to the
scaled image's width).

**Verified on the emulator** end-to-end, plus 7 new tests covering rotation per orientation tag,
bounding, and that an already-upright image is left byte-identical.
