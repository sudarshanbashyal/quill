# Quill — Conversation Log

A running record of the sessions that shaped this project: what was asked, what options were
weighed, and what got decided. Kept alongside [note.md](note.md) (how the code is built) and
[requirements.md](requirements.md) (what's still to build) — this file is the _why we got here_
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
- **Read-aloud voice quality** — auto-select the highest-quality _offline_ voice on init, plus a
  long-press voice picker (matching the long-press-for-options pattern audio segments already used).

**Also fixed:** read-aloud button showing on empty notes (the prefilled title was counting as content
— now only body text drives it), tag option only appearing on title focus, "New note" header removed,
long-press to delete images/audio since there's no keyboard gesture for it.

## 2026-07-26 — Feature implementation: architecture and requirements documentation

**Asked:** write a note.md capturing architecture and design decisions for future context; then a
requirements doc from `one-pager.pdf` with prioritized epics.

**Choices:** [note.md](note.md) explicitly separated what's _actively used_ from forward-looking
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
(the human is the judge, no string matching ever); only _quizzes_ need real grading, and those can be
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
`applyFlatCardStyle`; cards styled _flat_ (elevation 0, stroke 0) because the MSE Figma draws every
card as a plain fill with no shadow — sampled its grey into a new `surface_container` `#F5F6FA`.

**Biggest win:** all 13 dialog sites → `MaterialAlertDialogBuilder`. Most were on the _framework_
`android.app.AlertDialog`, which ignores app theming entirely, so they stayed visibly Material 2
(square corners, ALL-CAPS buttons) even after the theme switch.

**Then:** search fields → outlined `TextInputLayout`, Playfair Display for "Welcome back" (variable
font, fine at minSdk 26), and the home header curve corrected — it had been curving the wrong way.
The Figma layers a full-bleed gradient behind a content sheet that curves _over_ it, so the header
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

**Found:** `restyleHeadingLine` identified its own derived heading-bold span by _bounds_
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
_visual_ transform. It moves pixels but not layout bounds, so the `ScrollView` — constrained
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

**Decided (asked, not assumed):** given most keyboards follow the _system_ theme while Quill is
pinned light, you chose "keep it light, make it flush" over tracking the system theme — preserving
the always-light decision from 2026-07-12.

**Built:** items shrank ~83dp → equal weights across the full bar width; plain AppCompat `Button`s
(the last non-M3 thing in the editor) replaced by composite icon items; one continuous
`surface_container` surface with a hairline top edge and no bottom treatment, since the keyboard
provides that boundary.

**Two bugs surfaced while wiring the dots:** `updateState()` didn't even take heading/bullet
parameters, and the H1/H2/bullet handlers never called it — so those three _couldn't_ light up.
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
dialog, delete closes only once confirmed. Feedback had to move _inside_ the dialog's window; a
Snackbar on the editor root sits behind it and is never seen. Also: exported files are named
`Quill_<timestamp>.jpg` rather than the internal `img_<uuid>.jpg`, and inline images are centred
(needs both `FIT_CENTER` and container gravity, since `adjustViewBounds` shrinks the view to the
scaled image's width).

**Verified on the emulator** end-to-end, plus 7 new tests covering rotation per orientation tag,
bounding, and that an already-upright image is left byte-identical.

## 2026-07-29 — Feature implementation: Q&A blocks in notes

**Asked:** a distinct Q&A block per the MSE Figma's **QA** frame — question above, answer behind a
green rule; bold/italic/list in both halves; heading/image/audio disabled while inside.

**Design pulled from Figma** (frame `108:4`). The block's fill turned out to be `#F5F6FA` — the
`surface_container` token already in use for note rows — so no new surface was invented. The green
came back as an image asset rather than a colour, so it was sampled from the rendered separator:
`#30B488`.

**Key structural decision:** all the formatting behaviour lived inside `TextSegmentView`, and a Q&A
needs the same thing in two fields. Rather than copy it, it was extracted into `RichTextField` —
`TextSegmentView` wraps one, `QASegmentView` wraps two. That extraction is also what made the
disabling clean: **capabilities are a property of the field, not the toolbar**. A Q&A field declares
`headingsAllowed = false`; the toolbar greys controls by asking the focused field and never learns
what a Q&A is. Refusal is enforced in the field too (`applyHeading` no-ops), not just hidden.

**Storage:** a fenced ` ```quill-qa ` block with a `---` divider between question and answer, chosen
over per-line `Q:`/`A:` prefixes so both halves stay ordinary multi-line Markdown. Content that
looks like scaffolding is escaped; a truncated document is read to the end rather than dropping
text — a real off-by-one the tests caught, where the fence-end index ate the last line.

**Flagged:** the toolbar icon was a placeholder authored here, since neither the Figma frame nor the
supplied icon set had a Q&A glyph. Replaced with `ic_question.png` in the follow-up below.

**Also removed:** `RichTextEditor` and `ImagePathSpan` — early prototypes superseded by
`TextSegmentView`, referenced by nothing but each other.

## 2026-07-29 — Bug fix: three Q&A follow-ups

**1. Toolbar stayed disabled after leaving a Q&A block.** State refreshed on content _and selection_
change — but not **focus**. Moving from a Q&A field to the trailing text segment often lands the
caret at an offset it already had, and Android fires no selection callback when the value doesn't
change, so nothing ever re-asked which field was focused. Fixed by reporting focus in its own right,
since focus is what decides _whose_ capabilities are on display. While there: `focusEnd()` only
worked when the note's last segment was text, so tapping below a note ending in a block silently did
nothing — it now appends a text segment first.

**2. Placeholder shown on every empty text segment.** The empty segments a block insert leaves
behind mid-note each said "Write something…". Now only the note's last text segment prompts, via
`NoteEditorView.updateHints()` called from the two places the segment list can change shape.

**3. Backspace deleted blocks.** Backspacing at the start of the line below an image, audio or Q&A
removed it — one keypress, no confirmation, no undo. Removed entirely; blocks are long-press only.
This exposed that **Q&A had no delete path at all** (long-press was never wired for it), so that was
added with the same confirmation dialog.

**Known rough edge, stated rather than hidden:** on a Q&A the long-press target is the card's chrome,
not the text, because long-press inside a field has to stay text selection — that's how part of an
answer gets bolded. Offered two ways to widen it (more padding, or long-press-deletes on an empty
field) if it proves fiddly in use.

## 2026-07-30 — Feature implementation: flashcards from Q&A blocks

**Asked:** replace the note screen's audio button with an options button (`ic_option`) offering
"play aloud" and "turn into flashcards"; build cards from Q&A blocks that have _both_ halves, show
a message when a note has none, and put a typical review algorithm behind a simple right/wrong
design.

**The prerequisite nobody asked for.** Card→block linking was already designed (Epic D's
`source_segment_id`) and already assumed to be unblocked, because segment ids became stable back in
July. They're stable _within a session_ — `BaseSegmentView` mints them — but the Q&A fence didn't
store one, so every reload minted fresh ids. A card keyed to that would have lost its history on the
next parse and re-syncing would have duplicated the deck every time. The fix is the extension the
format had already reserved: ` ```quill-qa:<id> `. It carries the **block's** id rather than a
flashcard id, which keeps the document the source of truth and the flashcard table the follower.
Old fences without an id still parse and get one written back on the next save.

**Two clocks, kept separate.** `FlashcardScheduler` is SM-2 over days; `ReviewSession` governs the
sitting. A missed card returns to the back of the session queue and must be answered right before
the session ends, but only a card's **first** answer feeds SM-2 — otherwise missing a card and
getting it right two cards later reads to the algorithm as clean recall and pushes it weeks out.
Both are plain Java with no Android or clock, and carry 18 unit tests between them.

**Right/wrong rather than SM-2's six grades**, mapped to quality 5 and 2. Self-rating recall on a
six-point scale mid-review is the part of SM-2 people get wrong most often and the part that matters
least; the interval ladder does the work.

**Deviations from the plan in requirements.md, stated rather than hidden:** no per-note
Manual/Automatic sync mode (sync just runs when the review screen opens — Manual in all but name),
and an orphaned card is left alone rather than surfaced as orphaned. Neither the per-note card list
nor the global cross-note session was built.

**Also changed:** `AppDatabase.onUpgrade` is no longer unconditionally destructive — v3+ upgrades
are additive now, so adding two columns doesn't wipe someone's notes. And `hasRealContent` didn't
count Q&A blocks, so a note whose only content was Q&A survived purely because the auto-generated
title is never empty.

**Verified on the emulator end to end** — two blocks → deck of 2 → miss one → it comes back → summary
"1 of 2 right first time" → reopening shows "All caught up, next card: In 23 hours", with the DB
confirming two rows (no duplicates), ids matching the fences, and the missed card's easiness down to
2.18. Two things only the emulator caught: a `ScrollView` eats taps whether or not it can scroll (so
the card only flipped on its margins), and the density-less PNG icons report their mdpi pixel size to
a menu item, which blew the popup out and truncated its labels.

## 2026-07-30 — Follow-up: a Flashcards tab, and deleting decks

**Asked:** say "Review flashcards" once a note's cards exist; add a bottom navigation menu with Home
and Flashcards; list one entry per note that has cards showing how many reviews are left and other
useful detail; allow deleting from that list and from the review screen.

**Navigation.** `BottomNavigationView` wired with `NavigationUI`, with the menu's item ids set to the
_destination_ ids — that's what makes tab switching pop back to the start destination instead of
stacking Home on Flashcards on Home. The bar hides on every non-top-level destination; the editor
and a review session are places you arrived from a tab, and a bar offering to jump away mid-note is
noise.

**The deck row leads with the due count**, because "how many reviews are left" is the number worth
scanning a list for; `N due now · M cards` and either the unseen count or the next due time follow as
detail. All counted in SQL — a note with two hundred cards should cost what one with three costs.

**Deletion is a hard delete**, deliberately breaking the app's soft-delete convention: a card is
derived from a Q&A block the delete doesn't touch, so a tombstone would either be resurrected by the
next sync or block that note from making cards ever again. What's lost is review history, which is
what the shared confirmation says. Both entry points use the same dialog so the warning can't drift.

**A latent bug the feature exposed.** `NoteEditorFragment` read its note id from `getArguments()` on
every `onViewCreated`. For a note created _during_ the session there is no id in the arguments, and
until this feature there was nowhere to navigate to and come back from, so it never showed. Coming
back from the review screen does exactly that: the fragment instance survives on the back stack and
only its view is rebuilt, so the editor forgot which note it was editing, went blank, and autosaved
itself into a **second, empty note**. Caught on the emulator, confirmed in the database (two rows,
one of them 0 bytes), and fixed by only reading the arguments when the fragment doesn't already know
its id — with `onSaveInstanceState` as the fallback for a real recreation. My first attempt at the
fix was the `onSaveInstanceState` half alone, which did nothing: the instance isn't destroyed, so
there was no saved state to restore from and the argument read still clobbered the id.

**Verified on the emulator**: generate → label flips to "Review flashcards" → tab shows `2 · 2 due
now · 2 new` → review both → row becomes `0 · All caught up · Next: In 23 hours` → delete from the
list (empty state + snackbar) → regenerate → delete from the review screen (returns to the note,
label back to "Turn into flashcards"), with the database checked at each step. Also confirmed a
trashed note's deck drops out of the list while its cards stay in the table.

**Worth knowing for next time:** `connectedAndroidTest` clears the app's data, which ate a round of
manually seeded emulator state mid-session.

## 2026-07-30 — Bug fix: "Stop reading" on a note that isn't reading

**Reported:** play a note aloud, go back, open a note again — the options menu says stop even though
nothing is playing.

**Cause:** `NoteReader.isSpeaking()` delegated to `TextToSpeech.isSpeaking()`, which reports the
**engine service's** state. That state is global — shared by every client in the process and
outliving any one of them — while a `NoteReader` is created per note screen. So a freshly opened
note could inherit "busy" from the screen before it, and Android's own docs are explicit that a lag
sits between audio being handed to the mixer and playback actually finishing.

**Fix:** the reader tracks its own flag (set when it speaks or queues a pending utterance, cleared
on done/error/stop/shutdown). A new reader starts false, which is right by construction: it hasn't
been asked to read anything. The symptom is now impossible regardless of how slowly an engine
settles.

**Honest about verification:** not reproducible on the emulator — its Google TTS clears the flag
promptly, and the same sequence showed "Play aloud" on both the pre-fix and post-fix builds (checked
by stashing the fix and reinstalling). What the emulator did confirm is that the label still turns to
"Stop reading" while a reading is genuinely in progress on the same screen. The reported case needs
a real device to confirm.

## 2026-07-30 — Bug fix: off-centre icons on the flashcard grading buttons

**Reported:** the right/wrong icons aren't centred in their circles.

**Cause:** an icon-only `MaterialButton` doesn't centre its icon. It positions the icon relative to
the _text_ block — of which there is none — and insets its own background (6dp top and bottom, plus
a horizontal inset in the icon-button styles), so a fixed 64dp square renders as an ellipse with the
glyph pushed up and toward the start.

**Fix:** zero padding, all four insets zeroed, `minWidth`/`minHeight` zeroed, `iconPadding` zero,
`iconGravity="textStart"`, and an explicit `cornerRadius` of half the button size so the fill is a
real circle rather than whatever the style's default shape works out to.

**Measured rather than eyeballed**, which is what made this quick: `adb shell uiautomator dump` gives
the button's exact bounds, then cropping the screenshot to precisely those bounds and magnifying
shows whether the glyph's centre matches the box's. The first attempt centred the glyph but left the
fill narrower than it was tall — visible only at that magnification — and the horizontal insets were
the remaining culprit. Confirmed working by the user.

## 2026-08-01 — Quizzes from Q&A blocks (Epic E, per-note MCQ)

**Asked for:** a "Make quiz" option in notes mirroring "Turn into flashcards"; at least 5 complete
Q&A blocks required, since the wrong options are drawn from the note's other answers; a Quizzes tab
(`ic_stopwatch`) listing quizzes; per-quiz attempt history with scores or an abandoned marker; a
15-second-per-question timer kept in a constant.

**One thing was ambiguous enough to ask about**: "multi-select". It could mean multiple-choice
(pick one) or genuinely selecting several. Chosen: **pick one, confirm with Submit** — a mis-tap
isn't final, and the timer has something to auto-submit when it expires.

**The design decision worth remembering: a quiz stores no questions.** `quizzes` is one row per
note saying "this is a quiz"; the questions are regenerated from the note's Q&A blocks at the start
of every attempt. That's what makes a quiz incapable of going stale against an edited note — the
whole `source_segment_id` reconciliation problem the flashcard side had to solve simply doesn't
arise when nothing derived is stored. It also gives fresh distractors and a fresh order each run.

**Departure from the plan in note.md**: quizzes read Q&A blocks _directly_ rather than the
`flashcards` table. Sharing the rows would have meant "Make quiz" silently generating flashcards as
a side effect, and a quiz's history depending on whether its deck had since been deleted. What the
two features share is the _rule_ — `FlashcardRepository.reviewableQa` — not the storage.

**Schema v5** (additive from v4): `quizzes` + `quiz_attempts`. Two columns that weren't in the
sketched shape earned their place: `total` per attempt (a note's block count moves, so "2 / 6" only
means something next to that day's 6) and `answered` (2/12 having answered three questions and 2/12
having answered twelve are not the same afternoon). `answered` was added mid-implementation after a
stub method that returned a hardcoded 0 made the gap obvious — the row wanted to say "Abandoned
after 4 of 12" and nothing stored the 4.

**The attempt row is written at start**, so leaving is recorded rather than rewarded. Normal exits
mark it abandoned on the way out; a killed process leaves it in progress, and a sweep on the next
load retires it — staleness is _computable_ here rather than arbitrary, since a quiz can't outlive
`total × 15s` plus a grace period.

**Marked at the end, never per question**, and the results list restates the correct answer only
where the answer was wrong. Grading as you go turns a measurement into a study session; restating
a correct answer under a correct answer is noise, but omitting it everywhere makes the list a
scolding.

**`QuizRules` holds every tunable** (`MIN_QA_BLOCKS`, `OPTIONS_PER_QUESTION`, `QUESTION_TIME_MS`,
`ABANDON_GRACE_MS`) and the option views are built in code from that constant rather than four
`MaterialCardView`s in the layout — otherwise "flexible" would only be true of the timer.

**Verified on the emulator** end to end, with a six-block note seeded by pulling the app's DB,
editing it locally and pushing it back (no `sqlite3` on the system image): Make quiz → detail
("6 questions · 15 seconds each") → a full run → the marked paper → history → the leave dialog and
its abandoned row ("Abandoned after 1 of 6", greyed score) → the Quizzes tab (`33%` badge, "4
attempts", "Last taken 1 minute ago") → the menu label flipped to "Open quiz" → the Snackbar on a
note with one Q&A block. Letting all six questions time out was the _fastest_ way to reach the
results screen — `adb shell input tap` pacing kept overshooting into the retake/done buttons.

**Caught by looking at a screenshot rather than the code**: the shortfall Snackbar was 137
characters and ellipsised at two lines, hiding the reason for the rule. Shortened to fit, with the
constraint written next to the string so the next edit doesn't undo it.

**30 JVM unit tests now** (12 new): `QuizGeneratorTest` and `QuizSessionTest`. `QuizQuestion`'s
constructor is package-private, so the session tests build their questions through the generator —
which also keeps them honest about the shapes the app actually produces.

## 2026-08-01 (same session) — Quiz session reworked into an answer sheet

**Asked for**, after seeing the first version: one budget of 15s × questions for the whole quiz
instead of 15s per question; Previous/Next at the bottom with free movement whether or not the
question is answered; indicators at the top for what's been answered; a red warning at 10 seconds;
and submitting with blanks allowed but warned about.

**These are one change, not five.** The per-question timer was what forced the conveyor belt — a
question had to be sealed when you left it, or its clock made no sense. Moving to a whole-run
budget is what makes free navigation, changeable answers and blanks coherent, so `QuizSession` was
rewritten from "submit and advance" into an answer sheet: selections for every question, a cursor
that moves both ways, `goTo` for the indicator taps, and `unanswered()` for the submit warning.
Marking still happens only at the end — which is also what stops revisiting a question from being
a free second guess.

**Small decisions worth keeping:**

- Tapping the selected option again **clears** it. On a paper that can be revisited, the only other
  way to undo a mis-tap would be to leave it wrong.
- Running out of time **completes** the attempt rather than abandoning it. Every question was put;
  the blanks are answers the user didn't reach, so the paper is marked as it stands under "Time's
  up". Abandoned stays reserved for walking out.
- The clock **pauses behind dialogs** and resumes from where it stopped — time spent answering the
  app's question isn't the user's to pay for.
- The warning is **latched**, not re-evaluated per tick: styling applied once gets attention,
  reapplied 20×/second it fights for it.
- Blanks are marked **wrong, not excluded**. A percentage out of "the ones I attempted" would
  flatter exactly the run that ran out of time.
- The indicator row is built from the question count and scrolls; pips are distinguishable by fill
  (solid = answered) as well as by the ring on the current one, so the current _blank_ question and
  the current _answered_ one don't look the same.

**Verified on the emulator**: 1:30 on the clock for six questions on both the detail and session
screens, pips filling as questions are answered, jumping to question 5 by tapping its pip,
Previous/Next enable/disable at the ends, the blanks dialog ("5 questions have no answer, and will
be marked wrong") with the clock frozen behind it and resuming on "Keep answering", the red
clock/bar/warning appearing exactly at 0:10, and expiry landing on "Time's up · 2 of 6 correct"
with the four blanks marked wrong. 33 JVM unit tests pass; `QuizSessionTest` was rewritten around
the sheet (movement bounds, out-of-range jumps ignored, blank-and-return, change/clear, blanks
counted wrong).

**Note for next session:** a physical device (`34211FDH2005RG`) appeared on adb partway through, so
emulator commands now need `ANDROID_SERIAL=emulator-5554` — an unqualified `adb` call fails with
"more than one device/emulator" and, worse, could target the phone.

## 2026-08-02 — App icon, and an animated splash drawn from the font

Two related asks: use the new `res/drawable/logo.png` as the app icon, then show the same mark on
launch — but with the font rather than the export, "because the exported img is too low quality".

**The icon.** `logo.png` is a 157px tile whose "Q" sits low and left (67px of empty space above it,
6px below), so feeding it to an adaptive icon whole would have let the mask clip the glyph. The
background (`#DFE7FB`) is flat and uniform, so it keys out cleanly: the glyph was lifted onto
transparency and re-composited centred inside the 66dp safe zone, at all five densities, plus a
black-alpha `ic_launcher_monochrome` for themed icons (the adaptive XML had been pointing
`<monochrome>` at the full-colour Android-robot vector, which would have rendered as a blob). The
stock `.webp` robot icons and both `ic_launcher_*` vectors are gone; `minSdk` is 26, so the
`mipmap-anydpi` adaptive icon wins everywhere and nothing else referenced them.

**The splash.** `SplashActivity` is now the launcher entry point and finishes itself into
`MainActivity`. It draws `QuillLogoView`, a custom `View` — a recorded exception to the Material 3
rule, since no MDC component is a brand mark and a `MaterialTextView` plus two circle `View`s can't
keep the dots locked to the glyph. It renders "Q" in **Caprasimo** (bundled at
`font/caprasimo_regular.ttf`, OFL in `licenses/`) and derives _everything else_ from
`getTextBounds("Q")`, so `android:textSize` alone scales the mark. The dot ratios (0.30 of the Q box
across, 0.70 of the way down it, gaps of 0.036 and 0.071) were measured off `logo.png` and then
checked against a screenshot of the running app — the first pass was 0.05/0.065 and read very
slightly wrong, which the measurement caught.

The dots fade in left to right, hold, then fade out right to left, forever, driven by **one**
`ValueAnimator` walking a cycle clock with each dot's alpha a pure function of it — an
`AnimatorSet` per dot would drift apart over an unbounded number of repeats.

**Built so the background checks can land later**: the splash waits on `max(2s minimum,
StartupTasks)`, and `StartupTasks.run()` is an empty `AppExecutors.diskIO` block today. Real work
goes in there and the splash simply holds longer, still animating, because none of it touches the
main thread. The hand-off is also deferred while the Activity is stopped — Android 10+ blocks
background activity starts, so a user who leaves mid-splash would otherwise land nowhere.

**Non-obvious thing learned**: temporarily setting `splash_logo_size` to 300dp to harvest a
high-resolution render produced a mark only ~40% of the expected size. The paint was correct
(logged `textSize=667.5`, `getTextBounds` → 502×563) — the device simply does not _draw_ glyphs
above ~272px at the requested size, even though `getTextBounds` reports them honestly. Harmless at
the shipped 96dp, but don't trust `QuillLogoView` (or any large `drawText`) past ~250px without
checking. The 229px-tall render it did produce still beat the 84px glyph in `logo.png`, so the
launcher icons were regenerated from _that_ and are now downscaled rather than upscaled.

**Verified on the phone** (`34211FDH2005RG`): launched from the home-screen icon, frame-by-frame
screencaps show Q alone → one dot → two dots → dots shrinking away, then `topResumedActivity`
becomes `MainActivity`. `drawable/logo.png` is now referenced by nothing — kept as the source asset.

## 2026-08-02 (same session) — The logo flashing up before the animation

The static logo was visible for a moment on every launch before the animated mark replaced it.
Not our code: **Android 12+ draws its own splash screen** before the first frame of any activity,
and by default puts the launcher icon on it — so the mark was being shown twice, statically then
animated.

It can't be made continuous: the platform centres its icon in the window, while `QuillLogoView`
centres the whole mark, which leaves the "Q" alone sitting left of centre. The two can never line
up, so `Theme.Quill.Splash` sets `windowSplashScreenAnimatedIcon` to an empty vector
(`drawable/splash_system_icon.xml`) and `windowSplashScreenBackground` to the same lavender. The
launch is now one unbroken lavender field that the animated mark appears on. Both attributes are
API 31+ and carry `tools:targetApi="s"`; they live in the one style rather than a `values-v31`
copy, since overlaying by qualifier would mean duplicating every other item in it.

**Verified on the emulator**: frame-by-frame captures of a launch from the home-screen icon go
home screen → two frames of flat lavender with nothing drawn (the system splash, icon suppressed)
→ the animated mark.

**Note for next session:** the user wants the **emulator** used for verification, not the phone,
even when both show up in `adb devices`. Export `ANDROID_SERIAL=emulator-5554`. The phone also
sleeps within seconds, and `screencap` returns solid black while its display is off — which looks
exactly like a crash if you are only reading pixel values.

## 2026-08-02 (same session) — Home screen UI pass

Seven fixes, all on Home (two of them reaching the collection-detail screen, which shares
`NoteRowView`).

- **Greeting in Caprasimo.** The logo face now carries "Welcome back" too. No `textStyle`: the
  family ships one weight and no italic, and the old `italic|bold` would make Android synthesise
  both off a display serif. **Playfair Display is gone** — three font files and its OFL — it had
  exactly one call site.
- **Pinned cards are a fixed `pinned_card_width` × `pinned_card_height`.** Height alone wasn't
  enough: the title also had to become `setLines(2)` rather than `setMaxLines(2)`, or a one-line
  title pulls the date up and the _contents_ sit at a different height inside equal-height cards.
  A weighted spacer pins the tag row to the bottom so tagged and untagged cards agree.
- **Pinned cards get neutral (white/dark) tag chips** via a new `TagChipView.renderNeutral` —
  those cards are a pastel fill, and a tinted chip on a tinted card is colour on colour. Grey note
  rows keep the tag's own colour.
- **Spacing.** New `list_item_gutter` (8dp) is set as each item view's own margin _and_ as the
  RecyclerView's horizontal padding, so every item sits 16dp from the edge while two collection
  cards are 16dp apart — one value, no per-column margin maths. Plus taller section headers
  (`section_header_margin_top`/`_bottom`), 12dp between note rows, and 16dp padding inside them.
- **Collection cards are `surface_container` grey**, dropping the per-collection pastel tint that
  `HomeAdapter` used to apply — a grid of pastel tiles above grey note rows read as two different
  kinds of thing.
- **Collection cards summarise contents**: "3 notes · 12 flashcards · 2 quizzes". Notes always
  show (even at zero); flashcards and quizzes only once they exist. Both counts are new subqueries
  in `CollectionRepository.loadCollections` reaching through `notes` — flashcards and quizzes hang
  off a note, not a collection — and skipping soft-deleted notes, as `note_count` already did.
- **Section headers carry icons** (`ic_note`, `ic_collection`), and the 📝 emoji is gone from note
  rows along with the string. The source PNGs are 512/1024px, so they can't be compound drawables
  directly; `drawable/ic_section_{note,collection}.xml` are `layer-list` wrappers pinning them to
  `section_header_icon` and giving them usable intrinsic bounds for both XML and code.

**Gotcha worth remembering:** `<string name="count_separator"> · </string>` shipped as a bare "·" —
aapt strips leading/trailing whitespace unless the value is quoted (`" · "`).

**Verified on the emulator** against a seeded database (4 collections, 9 notes, 3 pinned, tags,
flashcards, quizzes). Seeding: `adb exec-out run-as mse.quill cat databases/quill.db` out, edit
with Python's `sqlite3`, `adb push` to `/data/local/tmp` and `run-as … cp` back, deleting
`-journal` first. There is **no `sqlite3` binary on the emulator image**, and `quizzes.note_id` is
UNIQUE — one quiz per note, so a collection's quiz count is really "notes that have a quiz".

## 2026-08-02 (same session) — Lost new notes, a pinned-card gap, and a fat bottom bar

**A new note left quickly wasn't saved.** `NoteEditorFragment.autoSave` only learned the note's id
from `createNote`'s async callback, and guarded creation with an `AtomicBoolean` that made any
autosave arriving _while_ creation was in flight `return` outright — so the save `onPause` fires on
the way out was dropped, and with it everything typed since creation started. There was a second,
quieter half: even when it did save, `createNote` and the follow-up `saveNote` were two separate
disk tasks with a main-thread hop between them, so `HomeFragment.onResume`'s `loadNotes` could slot
into the queue _between_ them and render the note without its body.

Both go away by **minting the id on the main thread**: `NoteRepository.createNote` now takes the id
(`NoteRepository.newNoteId()`) instead of generating one, `noteId` is valid immediately, and the
insert and the save are enqueued back-to-back on the single disk thread before anything else can
read. The `AtomicBoolean` and `OnNoteCreated` are gone.

**Pinned cards** went back to `setMaxLines(2)` from `setLines(2)` — reserving the second line left
short titles floating above a gap before their date. The fixed card height already keeps the row
even, so the title doesn't have to hold the shape too.

**The bottom bar was charging twice for the gesture inset.** `BottomNavigationView` pads itself by
the bottom system-window inset, and `MainActivity` was _also_ padding the root by it — so the bar
drew its own inset-height strip and then sat on a second, empty one. The root now takes
left/top/right only, and `applyBottomInset()` gives the bottom inset to the nav host instead
whenever the bar is hidden (the editor, a review session), so those screens still clear the pill.
The bar's height is also set in code as `bottom_nav_height` (64dp) + inset: at `wrap_content` M3
floors it at 80dp, and `app:itemPaddingTop`/`Bottom` do **not** move that floor — measured, they
changed nothing.

Net on a 1080×2400 emulator: bottom bar 104dp → 88dp, nav host 762dp → 802dp, and the Home
RecyclerView 296dp → 336dp of visible list.

**Verified on the emulator**: typing in a new note and hitting back immediately, and again typing
either side of the 500ms debounce, both land in `content_blob` in full (`AAABBB`), and the note is
in the Home list on return. Bottom bar screenshot-checked for clipping at the smaller height.

## 2026-08-02 (same session) — Bottom bar trimmed again, pin icon on Pinned Notes

`bottom_nav_height` 64dp → **56dp** (80dp on screen once the bar adds its own gesture inset), with
`bottom_nav_item_padding_top`/`_bottom` down to 2dp so the icon, active-indicator pill and label
still fit. 56dp is the floor for a bar that keeps its labels — anything shorter means
`app:labelVisibilityMode="unlabeled"`, which is a different decision, not a smaller number.

Across both passes on a 1080×2400 emulator: bar 104dp → 80dp, Home's RecyclerView 296dp → 344dp.

The **Pinned Notes** header now uses `ic_pin` (via a new `drawable/ic_section_pin.xml` size-pinning
wrapper, same pattern as the other two) instead of `ic_note`. `ic_section_note.xml` is still in use
— it's the "Notes" section header in `HomeAdapter`.

**Also verified this pass:** the hidden-bar path added last round. In the editor, `nav_host_fragment`
runs to the screen bottom (2400) but its content stops at 2337 — the 63px inset lands as padding,
so the scroll view clears the gesture pill.

## 2026-08-02 (same session) — Crash opening notes quickly, and notes silently deleted

Reported as "click a note, go back, click another — crash", only when done fast. Two separate
bugs, both **pre-existing** (`NoteReader.java` last changed in 38aadae; the delete branch in
`autoSave` is older still), both needing speed to hit — which is why three-second-paced emulator
runs missed them and only a chained `adb shell "input tap …; input keyevent …; input tap …"` with
no sleeps between reproduced it.

**The crash.** `NoteEditorFragment` builds a `NoteReader` per note screen; its `TextToSpeech`
binds asynchronously, and `onDestroyView` → `shutdown()` sets `tts = null`. Leave fast enough and
the engine's init callback lands after that:

```
NullPointerException: … 'int android.speech.tts.TextToSpeech.setLanguage(java.util.Locale)'
  on a null object reference at NoteReader.lambda$new$0(NoteReader.java:58)
  at android.speech.tts.TextToSpeech.dispatchOnInit
```

The callback now takes a local reference to the engine and returns early on a new `shutDown` flag,
`shutdown()` clears `ready`, and every public entry point goes through `isUsable()`.
`restorePreferredVoice` also tolerates a null `getVoices()`, which some engines return.

**The data loss, found while testing the crash.** Rapid open/back had _soft-deleted three seeded
notes_. `loadNote` is async, so a note opened and left before the read returns has an empty title
and no segments — `autoSave`'s `hasContent` is false, and it takes the "user emptied this note"
branch and calls `deleteNote`. A new `contentLoaded` flag (false from when an existing note's id is
known until its read lands, true for a brand-new note) makes `autoSave` a no-op in that window.
Empty fields there mean "not read yet", not "emptied".

**Verified**: 4 runs × 3 rapid open/back cycles → 0 FATALs, 9 alive notes, 0 deleted, 0 emptied.
The identical hammering before the fix gave 1 FATAL and 3 deleted notes.

**Method worth reusing:** timing bugs need input chained inside a _single_ `adb shell` call —
separate `adb shell` invocations are ~200-400ms apart, which is slower than a person and hides
exactly this class of bug.

## 2026-08-02 (same session) — The bottom "nav space" was a dead band, not the bar

The user's screenshot (`home.jpeg`) showed a note row clipped mid-height with a white gap below it
before the bottom bar. Measuring the hierarchy: `nav_host_fragment` ran to 2190 but the content
sheet and `recycler_home` stopped at **2043** — a 147px (56dp) dead band, exactly
`content_sheet_overlap`.

`LinearLayout.measureVertical` accumulates with
`totalLength = max(totalLength, totalLength + childHeight + topMargin + bottomMargin + …)`. That
`max` **swallows a negative margin on the weighted child**: the sheet's `layout_marginTop="-56dp"`
still shifted it up 56dp, but contributed nothing back to the space handed to `layout_weight="1"`,
so the sheet measured 56dp short of the bottom. Charging the same offset to the _header's_
`layout_marginBottom` shortens `totalLength` for real, and the sheet now reaches 2190 exactly.

Worth remembering generally: **a negative margin on a weighted LinearLayout child positions but
does not measure.** Put it on the sibling above instead.

Home's RecyclerView across the whole session: 296dp → 336dp (inset double-count) → 344dp (56dp
bar) → **400dp** (this fix), +35%. The bar itself was only ever part of it.

## 2026-08-02 (same session) — Status bar takes the colour of the screen under it

`MainActivity` was padding its root by the _top_ inset, which pushed every screen below the status
bar and left the strip behind the clock showing the window background — white, on Home's purple
header as much as anywhere. The root now applies only the side insets, and each screen hands the
top inset to the view whose paint should run up behind the bar, via a new
`util/WindowInsetsUtils.applyTopInset(View)` (captures the layout's own paddingTop once, so
re-dispatched insets don't compound).

- **Home** → the gradient header (`@+id/home_header`, new id), _not_ the root: the root is a
  transparent `FrameLayout`, so padding it would have kept the white strip.
- **Everywhere else** → the fragment root, which already carries `@color/app_background`. Its
  background paints through its own padding, so the bar reads white, matching the page.
- **Note editor** → the `Toolbar`, because `KeyboardInsetsHandler.attach(view, …)` already owns the
  root's insets listener. **A view has exactly one `OnApplyWindowInsetsListener`** — setting a
  second silently replaces the first, and the back arrow ended up under the clock. The toolbar also
  had to move from a fixed `layout_height="48dp"` to `wrap_content` + `minHeight`, or the padding
  would have squeezed its contents instead of moving it down.

Playfair Display was also restored (`git checkout` of the three font files and its OFL) for the
"Welcome back" greeting — 24sp, `textStyle="italic|bold"`, the family's real cuts. Caprasimo stays
for the splash mark.

**Verified on the emulator**: Home's status strip samples #CCC6FA (the gradient) where it was
#FFFFFF before; the editor, collection detail, flashcards and quizzes read white with their
headers clear of the clock.

## 2026-08-02 (same session) — Status-bar inset made reusable, and the clipped subtitle

Two follow-ups to the status-bar work.

**The greeting's subtitle disappeared.** The header is `wrap_content` with `minHeight=176dp`, and
its content sits _under_ that minimum — so adding the status-bar inset as padding didn't make the
header taller, it pushed the contents down inside a fixed-height box, sliding the subtitle under
the content sheet that overlaps the header's bottom 56dp. `applyTopInset` now grows the view's
`minimumHeight` by the inset as well as its padding. Rule of thumb: **padding a view for an inset
only moves its contents unless the view is free to grow** — check `minHeight` and fixed heights.

**One registration instead of nine call sites.** `MainActivity.applyTopInsetToEveryScreen()`
registers a single `FragmentManager.FragmentLifecycleCallbacks` (with `recursive = true`, because
the screens live in the nav host's _child_ fragment manager) that applies the inset in
`onFragmentViewCreated`. Default target is the fragment's own root; a screen needing somewhere else
implements `WindowInsetsUtils.TopInsetHost` and returns the view — only Home (gradient header) and
the note editor (toolbar, since `KeyboardInsetsHandler` owns the root's listener) do. New screens
now get this for free rather than having to remember a call.

Ordering that makes it work: `onFragmentViewCreated` is dispatched _after_ the fragment's own
`onViewCreated`, so the editor's `KeyboardInsetsHandler.attach(root, …)` has already run — and
since the editor's target is the toolbar, neither clobbers the other. `NavHostFragment` and
`DialogFragment` are skipped.

**Verified**: Home purple with the subtitle clear of the sheet; editor, flashcards and quizzes
white with their headers clear of the clock.

## 2026-08-05 — Inserting a Q&A block jumped to the top of the note

**Bug**: adding a Q&A block from the toolbar while writing at the bottom of a long note threw the
editor back to the top of the note, and the new block ended up behind the keyboard.

Two separate scroll bugs stacked, both in `NoteEditorView`:

1. **Focusing a block that has no bounds yet.** `insertQaBlockAfterFocused` called
   `focusQuestion()` in the same breath as `addView`. The ScrollView is asked to reveal a child
   that hasn't been measured, so it reveals where a zero-sized child nominally sits — the top.
   Nothing corrected it afterwards, because the keyboard is _already up_ when a block is inserted:
   `reserveKeyboardSpace` sees the padding unchanged, returns early, and never fires
   `revealFocusedInput`. Fix: focus and reveal from a `OneShotPreDrawListener`, when the block has
   real bounds.

2. **The reveal was losing a race it looked like it had won.** Splitting the segment above calls
   `setText`, which drops that field's caret to 0; a TextView brings its own caret into view from
   _its_ pre-draw pass, and — registered first — that was already running as a `smoothScrollBy` by
   the time the reveal went. An immediate `scrollBy` lands on the block and is then simply animated
   away by the in-flight scroller on the next draw. Logging `ScrollView`'s scroll changes with a
   stack trace showed it exactly: 715 → 1225 → 1369 (the reveal), then `computeScroll` dragging it
   back to 715 and animating to 1068. Fix: ask for a _non-immediate_ reveal — `smoothScrollBy`
   restarts the scroller rather than racing it — and put the split field's caret at the split point
   instead of leaving it at 0.

Lesson for this editor: **a scroll you perform synchronously is not final if a TextView in the same
tree has a pending caret-reveal.** Match its mechanism (smooth, so the scrollers merge) rather than
trying to out-order it.

Image and audio inserts had byte-identical split code with the same latent bug, so they now share
`splitFocusedTextForBlockInsert` and `focusOnceVisible`.

**Verified on the emulator** (emulator-5554): Q&A inserted mid-paragraph, at the end of a long
note, and after existing blocks — each time the block lands fully visible above the keyboard with
the caret in Question. Image insert re-checked through the photo picker. Unit tests pass;
`connectedDebugAndroidTest` can't compile on this branch for a pre-existing reason —
`NoteRepositoryMarkdownTest` still calls the 3-arg `NoteRepository.createNote`, which now takes 4.

## 2026-08-05 (same session) — One header line, one back arrow, and a title you can just type

**The editor's header.** Back and options sat in a `Toolbar` with nothing between them while the
note's title was the first row _inside_ the ScrollView — two lines doing the job of one. The title
moved up between the two buttons and the `Toolbar` became a plain `LinearLayout` header (id
`toolbar` → `header`, which `topInsetTarget` follows). Consequences worth knowing:

- The title no longer scrolls away, which is the point — but it also can't wrap any more. Two lines
  growing under the caret would shove the whole note down a row mid-keystroke, so it's `maxLines=1`
  at 20sp and long titles scroll inside the field.
- Its `imeOptions="actionNext"` broke: the next focusable after the header is the formatting
  toolbar, so "next" focused the _italic button_. `NoteEditorView.focusBodyStart()` plus an
  `OnEditorActionListener` sends the caret to the top of the body instead. Worth remembering that
  moving an EditText across the layout silently re-points its next-focus.

**One back arrow.** Four screens each drew a TextView holding a literal "←" and the editor showed
the platform's default — five arrows for one gesture. All five are now
`@style/Widget.Quill.Button.Back` (new `values/styles.xml`): `ic_back` in a
`Widget.Material3.Button.IconButton`, sized by `back_icon_size`. A new screen gets the arrow by
naming the style. The quiz's "← Previous" button is deliberately untouched — that arrow means the
previous _question_, not leaving the screen.

**The untitled title is a hint now.** A new note used to have "Untitled Note - <date>" typed into
the field for real, so naming it began with selecting a sentence and deleting it. It's the field's
hint instead, and an existing note whose title is empty gets the same hint from its `createdAt`, so
the editor reads the same as every list. One knock-on: the pre-filled title was also what made a
brand-new note save (and its "+ Add tag" chip appear) the moment the editor opened. An untitled,
empty note now isn't written at all until there's real content — which is what `autoSave` already
says it wants ("blank note — don't create a row for it"), so the chip appears on the first
keystroke rather than on open.

**Verified on the emulator**: header aligned on one row with the hint in grey; typing a title
straight over the hint; "next" landing in the body; the chevron on note editor, collection detail,
flashcard review, quiz detail and quiz session (the last needed a note with five complete Q&A
blocks built by hand to reach); a body-only note reading "Untitled Note - Aug 5, 2026" in both the
list and the editor.

## 2026-08-05 (same session) — Audio grew up: a real block, and playback that outlives the screen

Recordings were a `▶` button with a duration beside it, wrapped to its contents, and a
`MediaPlayer` living inside the segment view — so leaving the note killed the sound.

**The block.** `AudioSegmentView` is now a full-width card (same tonal fill and radius as a Q&A
block, so the two read as siblings): round play/pause button, waveform, elapsed/total time. The
waveform is scrubbable — dragging seeks, and dragging on a clip that isn't the live one starts it
first and lands the playhead where the finger went down.

**The waveform is measured, not decorative.** `WaveformCache` decodes the file with
MediaExtractor + MediaCodec and keeps the RMS of each of 256 buckets, normalised to the clip's own
peak (a quietly recorded memo would otherwise be a flat line). Views resample those buckets to
however many bars they can fit, so one decode serves the full-width card and the pill's short
strip. Notably the live recording waveform **can't** be reused: it's polled
`MediaRecorder#getMaxAmplitude()`, never stored, and clips recorded before this existed would have
nothing. Decoding runs on the shared disk thread and is cached for the process's life; failure
falls back to a flat placeholder rather than breaking the note.

**Playback moved out of the view.** `AudioPlayback` is one process-wide player; segments and the
pill are just controls that ask it questions. That's what makes a clip survive leaving the note.
`AudioPlaybackService` is a `mediaPlayback` foreground service holding a `MediaSession` and a
`MediaStyle` notification — two mechanisms doing two different jobs: **the foreground service is
what stops the system freezing the process** once Quill isn't visible, and **the media session is
what puts controls on the lock screen** and makes headset buttons work. The player deliberately
lives _outside_ the service so views can read state synchronously with no binding dance.
`POST_NOTIFICATIONS` is asked for on the first play, since refusing costs the controls, not the
audio.

**The pill** (`MiniPlayerView`) floats over the content in `activity_main`, so no screen loses
height to it. Two things it has to negotiate: it takes the gesture inset only on screens where the
tab bar is gone, and it hides itself while the IME is up — the keyboard and the editor's
formatting bar own the bottom of the screen then. That IME check lives in `MainActivity`, not the
editor, because a view gets exactly one insets listener and `KeyboardInsetsHandler` already claims
the editor's root.

Cost accepted: the pill covers the last line of a note, and it swallows taps so they can't land on
the text behind it.

**Verified on the emulator**: recorded a clip, then swapped a real 30s file into the app's private
dir (the emulator's mic records silence, so the decoded waveform was flat until then) — bars match
the audio's shape. Playing then locking the phone: `dumpsys power` says `mWakefulness=Asleep`
while `dumpsys audio` shows Quill's MediaPlayer `state:started`; the system media card shows title,
pause and a seek bar in the shade. Playback survives navigating to Home; pause and ✕ from the pill
work (`PlaybackState` PAUSED, then service and session both gone); the pill hides for the keyboard
while audio keeps playing. Unit tests pass.

## 2026-08-06 — Read-aloud outlives the note too

**Ask:** a recording keeps playing (and keeps its bar) when you leave the note; "Play aloud" should
behave the same, and it didn't — the bar vanished the moment you navigated.

**Why it didn't.** Nothing was broken; it was built that way on purpose. `NoteEditorFragment.onPause`
called `stopReading()` and `onDestroyView` called `noteReader.shutdown()`, on the reasoning recorded
in the code that read-aloud "is an action performed _on_ the open note, so it stops with it".
`ReadAloud` existed only as a passive bridge — it held a `Controller` implemented by the fragment,
so the thing driving the voice died with the screen. That premise is what the ask overturns.

**The fix is the one `AudioPlayback` already demonstrates:** move the engine out of the view layer.
`ReadAloud` is now a process-wide singleton owning one `NoteReader` for the life of the app, and
`NoteReader` moved `ui.notes.editor` → `audio` to match. The `Controller`/`started`/`update`/`ended`
push-state API is gone; `isActive()`/`isPlaying()`/`progress()` read straight through to the reader,
so the bar and the note's menu can't disagree. The engine is never shut down now, which as a
side-effect retires the bind-vs-shutdown crash race — its guards stay, since they're what make the
class releasable at all.

**Two seams for identity**, so the menu can say "Stop reading" for _this_ note while another note
reads on: `ReadAloud.noteIdMinted` is called where `autoSave` mints an id (a note can be read before
it has ever been saved), and `retitle` follows a rename.

**Judgement call, worth flagging:** `openFlashcards`/`openQuiz` used to stop the reading as "a
different mode". Removed — that reasoning belonged to the old note-scoped model, and the bar is on
those screens like every other. Say so if you want the voice to stop there.

**Verified on emulator-5554.** The trap: the test note is six short sentences, so a hand-paced run
lets the reading _finish_ before you navigate and you misread a correct empty bar as a regression.
Pause from the bar first — a paused reading can't end on its own — then navigate. Bar `V` on the
note, after back, and on the Flashcards tab; resume from the bar produced fresh synthesis requests
in logcat; ✕ took it to `G`. Re-entering the note mid-reading (a fresh fragment) offers "Stop
reading". Playing an embedded clip while reading swaps the bar to the waveform, i.e. mutual
exclusion still holds. `dumpsys activity top | grep MiniPlayerView` is the quickest ground truth —
its `V`/`G` flag beats reading screenshots.

**Not done:** the lock screen. `AudioPlaybackService` is still written entirely against
`AudioPlayback`, so a reading has no notification or lock-screen controls — it survives in-app
navigation, not the app going away. That needs the service generalised over both sources.

## 2026-08-06 (same session) — The bar should close when the audio ends

**Ask:** the now-playing bar should go away by itself when the audio finishes, for both an embedded
clip and read-aloud.

**Read-aloud already did this** — last chunk → `onReadingFinished` → `ReadAloud.clear()`. Only
`AudioPlayback` needed changing: its completion listener deliberately stayed loaded so the bar could
offer a replay ("less abrupt than it vanishing"). Now it closes, reaching the same end state as ✕,
service and notification included. Two details in that one edit: the close is **posted** (the
callback comes from the `MediaPlayer` that `close()` releases) and **keyed to the finished path**
(a tap queued ahead of the post can have started a different clip by then).

**The part that ate the time.** Testing said the bar never closed, and the clip appeared to freeze
at 0:29/0:30 — then "it's not frozen, the audio keeps replaying". Instrumenting `play()`/`close()`/
`onCompletion` with stack traces settled it in one run: exactly **one** `play()` call, so nothing in
our code was restarting anything, and **no `onCompletion` ever**. The seeded clip turned out to be
an **Ogg Vorbis file named `.m4a`** (the "real 30s file" swapped in during the 08-05 session);
`afinfo` says `File type ID: Oggf`. Android plays it, reports 30s, pins the position near 29s and
never signals end-of-stream. Replaced it with a real AAC clip (`say -o real.m4a --data-format=aac`)
and the log read `play()` → `onCompletion pos=7384 dur=7384` → `close()`, bar `G`, foreground
service gone. Read-aloud's own completion closed the bar too.

**Lesson worth keeping:** when audio won't end, check the container before the logic — a
mislabelled file mimics a state-machine bug almost perfectly. Don't reason about it from the UI
clock; `dumpsys activity top | grep MiniPlayerView` plus a stack-trace log on the player's entry
points is the short path.

**Left on the emulator:** the valid AAC clip is in place at the seeded note's audio path so manual
testing behaves; the original is beside it as `ORIG_BACKUP_<name>.m4a` under the app's
`files/audio/`. Restore with `run-as mse.quill cp` if the broken file is wanted back.

## 2026-08-06 (same session) — Long-pressing a recording scrubbed it instead of offering delete

**Symptom:** long-press an audio block to delete it and the playhead jumped to wherever the finger
was — no confirmation dialog.

**Cause:** `WaveformBarsView.onTouchEvent` consumed `ACTION_DOWN` and seeked immediately, so the
touch never reached `AudioSegmentView`'s long-click listener. And since seeking a clip that isn't
loaded _starts_ it (`AudioSegmentView`'s seek listener plays first, then seeks), holding to delete
began playing the recording. Delete was only reachable on the card's padding, play button and time
label — everything except the part of the card that looks most like "the audio".

**Fix — the waveform is a scrubber only while its clip is loaded.** `setScrubbable(boolean)`, driven
from `render()` off `AudioPlayback.isCurrent`. Live: unchanged, seek on DOWN. Dormant: nothing on the
way down, then a hold → parent's `performLongClick()`, a lift → seek (starts the clip), a horizontal
drag → scrub (claims the gesture at that point, not before), a **vertical** drag → return `false`
so the scrolling note can intercept. That last branch was a bug I wrote and caught on review: my
first version treated _any_ slop-exceeding drag as a scrub, which would have blocked scrolling the
note wherever a recording sat under the finger.

**Verified on emulator-5554:** long-press mid-waveform → "Delete audio?" with the playhead untouched
and nothing playing; tap → starts and seeks; live clip after ~4s → tap near the left edge seeked back
to 0:01/0:07; vertical drag on a dormant waveform → no seek, no playback. Scroll pass-through itself
wasn't exercised — the test note fits on one screen, so there was nothing to scroll.

**`adb input tap` coordinate trap:** the now-playing bar takes a 211px row at the top, so every
card's y shifts by that much depending on whether something is playing. Several test taps landed in
the body text (opening the keyboard) or dismissed a leftover dialog instead of hitting the target.
Re-read the bounds from `uiautomator dump` after any state change rather than reusing coordinates,
and prefer `KEYCODE_BACK` sparingly — it exits the note when no keyboard is up.

## 2026-08-06 (same session) — Export a note as PDF or Markdown

**Ask:** an Export item (`ic_share`) in the note's options menu, offering PDF (`ic_pdf`) and
Markdown (`ic_markdown`). Markdown simple, since notes are already Markdown; PDF with styles
preserved; audio replaced by "Embedded Audio Recording - <length>".

**Shape:** `util/NoteExportStore` (Downloads/Quill, MediaStore on 29+, plain file + permission
below), `util/MarkdownExporter`, `util/PdfExporter`, a second `PopupMenu` from the options menu.
The storage-permission flow in `NoteEditorFragment` was image-only — it now holds a
`pendingStorageAction` Runnable, since two different things queue behind that permission.

**Markdown stayed thin on purpose**: text goes straight through `MarkdownSerializer`; only the
Quill-private constructs are rewritten (`quill://` embeds → italic placeholders, `quill-qa` fences →
bold Q:/A:, title → H1). Resolved toward "readable elsewhere", not "reloadable here" — the database
is the copy that round-trips.

**PDF got styling nearly free** — the note is a `Spanned` and `StaticLayout` draws bold/italic/
underline/bullets natively. Only headings needed work (invisible markers → size+bold at
`RichTextField`'s 1.6/1.3). Pagination draws the whole layout _clipped and translated_ rather than
re-laying out a slice, which would re-wrap the text.

**Bug found and fixed while reading the output:** the first Markdown export came out as
`# **Lecture 7 …**`. `MarkdownSerializer` skips a heading's derived bold so it isn't encoded twice,
but the guard only matched a span starting at `lineContentStart`, while `RichTextField:268` applies
it from `lineStart` — the invisible marker included. Latent for ages and invisible in the app, since
a heading is re-bolded from its marker on load; only an export shows it. Guard now accepts either
bound.

**Verified on emulator-5554** by reading the actual artefacts, not just the UI: exports pulled off
the device, `.md` read back, `.pdf` rendered to PNG with `sips` and looked at. Seeded notes had no
Q&A or inline styling, so a note was built in the editor with bold/italic/underline, bullets and a
Q&A block — the PDF shows all of them plus the accent-ruled Q&A, and the audio block renders as
"Embedded Audio Recording - 1:38". Snackbar confirms with the filename.

**Two testing notes.** The FAB is a speed dial whose items `uiautomator dump` can't see, and a
second tap on the FAB _closes_ it — several attempts silently did nothing. Chain the taps and track
whether it was already open. And a `Snackbar` is `LENGTH_LONG` = 3.5s, which is shorter than the
round trip of `adb exec-out screencap` + `uiautomator dump` between commands; use
`adb shell "input tap …; sleep 1; screencap -p /sdcard/x.png"` and pull it afterwards.

**Left on the emulator:** a scratch note "Untitled Note - Aug 6, 2026" holding the style test, and
two sample exports in `Downloads/Quill`.

## 2026-08-06 (same session) — How slow is export, and an export-complete dialog

**Asked how long export takes for a really long note.** Measured rather than guessed: temporary
timing log around the export, then a note grown by select-all/copy/paste doubling
(`input keycombination KEYCODE_CTRL_LEFT KEYCODE_A` … `KEYCODE_V`, ~10 doublings from one
paragraph). Results, disk thread so the UI never blocks either way:

| chars                 | Markdown | PDF   |
| --------------------- | -------- | ----- |
| 4,888                 | 34ms     | 35ms  |
| 38,152                | 27ms     | 43ms  |
| 304,264 (75-page PDF) | 49ms     | 150ms |

Text is free. **Images are the cost that would matter** — each decoded to ≤1600px and drawn — and
that is still unmeasured. The 75-page PDF was also the first real test of pagination: pages 2, 40
and 75 rendered via a small Swift/CoreGraphics script (no ffmpeg/pdftoppm on the machine; `sips`
only does page 1) and the flow is continuous with nothing truncated.

**Then: replace the Snackbar with a dialog** — badge, filename, Open / Done, small spring-in
animation. `NoteExportStore.save` now returns `Saved(displayName, uri)` so Open has something to
hand a viewer, and `file_paths.xml` gained an `external-path` for `Download/Quill/` (the pre-29 path
must go through FileProvider — a `file://` uri can't cross to another app).

**Worth knowing:** `text/markdown` has **no handler at all** on a stock emulator image
(`pm query-activities -a android.intent.action.VIEW -t text/markdown` returns nothing), so Open on a
Markdown export could only ever fail. It now tries `text/markdown` then falls back to `text/plain`,
which everything handles. Verified: PDF opens `com.google.android.apps.viewer.PdfViewerActivity` and
renders correctly; Markdown reaches the chooser.

**Testing note:** `animator_duration_scale 0` (set earlier to steady the taps) makes
`ViewPropertyAnimator` finish instantly — an animation looks like it isn't running. Raising it to
10 to inspect one instead slowed the _menus_, so chained taps landed on the wrong items and
accidentally started read-aloud. Scale affects everything, not just the thing under test; restore
it to 1 when done. The user took over checking the animation.

**Restored:** the Lecture 7 note's original text (the doubling had left ~300K chars of lorem ipsum
in it), animation scales, and `Downloads/Quill` emptied of test output.

## 2026-08-06 (same session) — Search bar redesign, filtering/sorting, and a dialog that sized wrong

**Asks:** drop the magnifying glass and restyle the search bar to the Figma; add tag filtering and
sorting behind `ic_filter`; show the active tag filters under the box; use the same bar on the
collection screen; and make the add-existing-notes dialog size to its content with a ceiling.

**Figma reference:** HomePage 2, node `32:600` in the MSE file. The box is white with a 1px
hairline, no start icon, and the filter button sits _outside_ it to the right; selected tags render
as pastel chips underneath — which is exactly the "filtered tags at the bottom of the searchbar"
that was asked for, so the chip row follows the tag's own colour via the existing `TagChipView`.

**Built:** `ui/search/SearchFilterBar` (compound view, shared by Home and collection detail),
`NoteFilter` (query + any-of tags + pinned-only + four sorts, applied in memory), `SearchFilterDialog`,
`FilterChips`, and `util/MaxHeightScrollView`. The old field was
`@android:drawable/ic_menu_search` — a framework drawable, which the M3 convention forbids anyway.

**Two judgement calls, both recorded in note.md:** tags filter as _any-of_ (a second tag widens
rather than empties), and collections ignore tag/pinned filters (they're note properties; a
collection disappearing looks like deletion) while still following the sort.

**Two mid-task corrections from the user, both mine.** First: selected tags in the filter sheet
weren't distinguishable — the chips carry the tag's colour as their resting background, so
Material's default checked styling (a background change) landed on a colour already doing something
else. I added a ring plus the checked icon. Second: that made the chips _grow_ on selection and the
row reflow on every tap. Final shape is border-only, with the stroke present on every chip at all
times and painted the same colour as the fill when unselected — selection changes exactly one
colour and nothing re-measures. Verified by cropping the chip row in both states: the unselected
"Urgent" chip sits at an identical x, so the width is provably unchanged.

**The dialog bug was a one-liner with a general lesson.** `AddExistingNotesDialog` gave its
`ScrollView` a _fixed_ height from `note_picker_max_height` — a max in name only. `WRAP_CONTENT`
alone would break the long list instead. `MaxHeightScrollView` measures `AT_MOST` against the
ceiling, which handles both; use it for any dialog list rather than a fixed height.

**Verified on emulator-5554:** bar renders without the glass on both screens; filter sheet shows
sort/tags/pinned; selecting Uni + Title A–Z filtered notes to the Uni-tagged two and re-sorted
collections alphabetically; chips appear under the box in tag colours and remove on tap; selected
chips are visibly ringed; the picker shrank from six rows to one when filtered.

## 2026-08-06 (same session) — One way to phrase a timestamp

**Ask:** times were inconsistent — a just-saved note said "Updated 0 minutes ago". Wanted a ladder
(now / x min / x hour / yesterday / short date), as a reusable util, used everywhere dates show.

**Cause:** every screen called `DateUtils.getRelativeTimeSpanString(..., MINUTE_IN_MILLIS)`, whose
minute resolution floors a seconds-old timestamp to zero rather than saying "now".

**`util/RelativeTime`** now owns it, with `past()` and a mirrored `future()` for a deck's next
review. Migrated all seven call sites (note rows, home rows, pinned cards, collection cards,
collection subtitle, deck rows, quiz rows); no `getRelativeTimeSpanString` remains in the codebase.

**Two details that would be easy to get wrong twice:** under a day is _elapsed_ time but
"yesterday" is a _calendar_ question (otherwise 30 hours ago reads as yesterday when two midnights
have passed, and 2am reports "yesterday" for something three hours old); and the calendar-day
difference is rounded, not truncated, because midnight-to-midnight is 23 or 25 hours across a DST
change.

**Testing note:** the emulator wouldn't let me move the clock (`date: Operation not permitted`, no
`su` on this image), so "yesterday" couldn't be produced on device. Instead the rung choice is split
from the wording — package-private `Bucket` + `bucket()` — and covered by a JVM test
(`RelativeTimeTest`, 8 cases, including the 30h-vs-40h calendar distinction). On device I confirmed
now / x min ago / x hours ago / short date in a single Home screenshot.

**Left alone:** `NoteDisplayUtils.untitledWithDate`, which formats an absolute date into a note's
default _name_ ("Untitled Note - Aug 6, 2026"). It isn't a relative timestamp and shouldn't drift.

## 2026-08-03 — Feature implementation: Whiteboards section on Home

**Asked:** Home only lists notes; show a whiteboards section there too.

**Found first:** whiteboards were entirely unreachable. Nothing listed them, no note screen linked
to one, and Home's existing "New Whiteboard" FAB option navigated with an empty Bundle while
`nav_graph.xml` declared `note_id` as a _required_ argument — so that button could only ever throw.

**Decided: whiteboards become first-class, not note-attached.** `whiteboards.note_id` stays but is
explicitly nullable — a board created from Home stands alone, one opened from a note still belongs
to it. The table gained `title`, `created_at`, `updated_at`, because a board has no body text to
derive a name or a timestamp from the way a note does.

**Schema v3 → v4 migrates in place** rather than taking the documented destructive `onUpgrade`
path — the change is purely additive, so three `ALTER TABLE`s plus a backfill dating existing rows
from their strokes. First real migration in the codebase; the drop-everything branch still covers
every other version step.

**Built:** `WhiteboardRepository` (AppExecutors, callback-based, matching the other repositories
rather than the fragment's ad hoc threads), a `WhiteboardCardView` grid card, a Whiteboards section
in `HomeAdapter` between Collections and Notes, `WhiteboardDialogs` for create/rename/delete, and
`updated_at` bumps on draw/undo/clear so the section sorts by real recency.

**Two judgement calls worth recording:** the card shows a glyph and a stroke count, not a thumbnail
— a real preview would mean loading every board's strokes just to draw Home. And deletion is a hard
delete against the app's soft-delete convention, because there is no whiteboard trash surface, so a
soft-deleted board would just be unreachable rows.

**Also fixed in passing:** `exportWhiteboard()`'s unconditional "Export failed" toast (the known bug
in note.md) — now that whiteboards are reachable, users will actually hit it.

**Not verified:** the build was not run at the user's request, and nothing has been seen on a device.

## 2026-08-06 (same session) — Re-scoping Epic C: sharing and collaboration

**Discussion, no code.** Question was whether NFC + Wi-Fi Direct is really the right basis for
offline sharing, or whether Quick Share/something else is nicer. Outcome: Epic C rewritten, and
`note.md` gained a "Sharing and collaboration" section.

**What the review turned up:**

- **The NFC step as specified was built on a dead API.** "NFC tap-to-pair handshake" is Android
  Beam / NDEF push, deprecated in Android 10 and non-functional on modern devices — two phones
  cannot push to each other. Phone-to-phone means `HostApduService` + reader mode.
- **Wi-Fi Direct was the wrong layer to hand-roll.** Epic C's own transport bullets (framing,
  reconnect/backoff) are re-implementing Nearby Connections, which is equally offline and
  peer-to-peer but supplies discovery, encryption, payloads and reconnect.
- **Quick Share isn't an API.** It's a share _target_ — `ACTION_SEND` + FileProvider and it
  appears in the sheet for free. So note sharing was already 90% built by the exporter.

**Decisions:** notes are _copied, not co-edited_ (import mints a new id, so the conflict domain
disappears — and with it the reason the Markdown storage decision's coarse merge granularity
mattered); whiteboards are the only live collaboration, because strokes are already a CRDT;
transport is Nearby Connections.

**The load-bearing idea worth not losing:** a Nearby `endpointId` is assigned locally by the
_discovering_ device, so it can't be handed over out-of-band. What NFC carries is a **session
token** the host advertises under — which makes NFC and QR interchangeable carriers of the same
string. QR first (no NFC APIs, testable on one machine), tap second.

**Two traps recorded for whoever builds it:** receiving a shared file via an intent filter is
unreliable because Quick Share delivers `content://` typed `application/octet-stream` with no
usable path, so explicit Import (`ACTION_OPEN_DOCUMENT`) has to come first; and `MainActivity`
is `exported="false"`, so nothing can be received at all today.

**Planning constraint:** none of the P2P work runs on the emulator — it needs two physical
devices, which is a change of habit for this project.

**Dropped, deliberately:** per-note vector-clock resolution, the outbox writer/drainer for
notes, and hand-rolled `WifiP2pManager`. The schema columns stay as inert scaffolding.

## 2026-08-07 — Blank tag pills on pinned cards: scroller first, then a measured `+N`

**The report:** on a pinned note with more than two tags, the extra chips rendered as blank
pills. Cause is structural, not a drawing bug — a horizontal `LinearLayout` on a fixed-width
card hands out width in order, so chips past the edge are allotted nothing and only their
background survives.

**First attempt, as asked: make the tag row scroll.** It worked, and the nesting was the whole
problem — the pinned band is itself a `HorizontalScrollView`, so two scrollers wanted the same
swipe. Claiming the gesture on ACTION_DOWN and handing it back at the row's end got horizontal
right, but **a vertical drag starting on a tag then did nothing at all**. Worth keeping: the
usual `requestDisallowInterceptTouchEvent(true)` travels all the way to the `CoordinatorLayout`,
which cancels its behaviours on the spot, and `AppBarLayout` can't pick a drag back up
mid-stroke — so the header refused to collapse for any swipe that began on a tag. The fix was a
`PinnedBandScrollView` the tag strip could ask _directly_, keeping the claim local so nothing
above it ever heard about it. Verified on the emulator: tags scrolled, the band took over past
the last tag, and the header collapsed again.

**Then the user changed their mind mid-turn: "+n is better if it doesn't fit."** All of the
above was reverted (custom scroller deleted).

**What shipped instead — the cap is measured, not guessed.** The prior code capped at a
hard-coded 2, with a comment claiming two chips plus a `+N` always fit. They don't: "exam" +
"important" + "+3" needs ~164dp of a 132dp content box, which is the same blank-pill bug at a
different tag count. `renderNeutralFitting` measures each chip, walks a prefix-width array to
find how many fit, then gives chips back until the `+N` fits too. How many fit is a question
about the _names_, not the count — two long tags overflow where four short ones don't.

**Consequence worth expecting:** the row can look sparse ("exam +4" on a 164dp card) because
dropping one long chip frees a lot of width. That's honest — the alternative is a chip drawn
past the card edge.

**Emulator note:** there's no seeding path, so multi-tag cases were tested by pulling
`databases/quill.db` via `run-as`, editing with local `sqlite3`, and pushing it back — the
device has no `sqlite3` binary. Seed rows were deleted afterwards and the DB restored.

## 2026-08-07 (same session) — A whiteboard canvas bigger than the screen

**Asked:** the scrollable whiteboard "we talked about yesterday". **Nothing in memory recorded it** —
the 08-06 entries cover audio, export, search and timestamps, and Epic I didn't list scrolling. The
whiteboard _title-rename_ work from that session is also sitting uncommitted and unlogged, so that
session appears to have ended without a log. Spec came from the user instead: two-finger pan, ten
screens of canvas, no zoom, a centre option.

**Built:** strokes moved from raw view coordinates into canvas coordinates, with the window moved by
the View's own `scrollTo` — so `onDraw` gets an already-offset canvas and strokes draw where they
live. One finger draws, two pan, `CANVAS_SCREENS = 10` bounds it. A Centre button (`ic_centre`) in
the top toolbar, `centreOnContent()` behind it.

**Three things that fell out of the change rather than being asked for:**

- Storing points in _view_ coordinates was already a latent bug — a board drawn on one screen size
  opened misaligned on another. Canvas coordinates fix it, and legacy boards are unaffected because
  their ink is a screen wide at the origin.
- **Opening a board now centres on its ink**, or a board drawn far out reopens on blank canvas with
  nothing on screen to say which way its drawing lies.
- **Export had to stop capturing the viewport** — it would silently crop anything larger. Now the
  ink's bounding box, scaled down past `MAX_EXPORT_PX`, with eraser strokes left out of the bounds
  since white on white can't extend what's visible.

**Testing, and a costly lesson.** The emulator will not accept injected multi-touch: `sendevent` on
`/dev/input/event*` is refused by SELinux despite the shell user being in the `input` group, and
`adb shell input` is single-pointer. So the pan behaviour is covered by `WhiteboardViewPanTest`,
which builds multi-pointer `MotionEvent`s and hands them to the view (10 tests, all passing).

**Running it destroyed the emulator's app data**: `connectedDebugAndroidTest` uninstalls both APKs
when it finishes, taking `/data/data/mse.quill` with it — the demo notes, collections and the
"Sprint planning" board. The backup was unrecoverable because the failing `run-as ... cat` in the
same command truncated it to zero bytes through the `>` redirect. Recorded in global memory; back
up `quill.db` to a fresh filename before any instrumented run.

**Also fixed to get there:** `NoteRepositoryMarkdownTest` hadn't compiled since `createNote` changed
to take the id from the caller, which was blocking the whole androidTest source set.

**Verified on device afterwards** with a seeded board whose ink sits ~1.7 screens right and ~1.3
down: it opens centred on that ink, one-finger drawing lands under the finger in a panned window,
and Centre re-frames the drawing. The two-finger pan itself is test-covered, not finger-verified.

## 2026-08-07 (same session) — Whiteboard screen: centred canvas, one rail, Move tool

Follow-ups to the pannable canvas, in the order they arrived:

**The board now opens in the middle of the canvas** rather than at the top-left corner, so there is
room in all four directions. "Home" for an empty board and for Centre-with-nothing-drawn moved with
it. Legacy boards are still fine — their ink sits at the canvas origin, so centring just clamps the
window to that corner.

**One rail instead of two bars.** Everything but the editable heading moved into a left-hand card:
tools, colours, widths, and the board actions. The card is sized to its content and centred, with
`layout_constrainedHeight` so a short screen shrinks it into a scroll instead of clipping it. The
show/hide eye stays in the top bar for the obvious reason that a control cannot be the thing it
hides. A back button matching every other detail screen went in beside the title.

**Three things worth remembering from the visual pass:**

- Framework `Widget.ImageButton` sets `scaleType` to **center**, not `fitCenter`. That was invisible
  while every icon was a 24dp vector and obvious the moment 512px PNGs arrived — one icon vanished
  (its centre is transparent) and another showed a single corner. Every icon button now sets
  `fitCenter` + padding explicitly.
- The separate "current colour" indicator, moved down beside the swatches, read as a sixth colour
  you could pick. Replaced entirely: the selected swatch highlights itself with the same
  `tool_selector_bg` the tools use, and stroke widths got the same treatment plus a fourth, heavier
  option.
- The user dropped new PNG icons into `res/drawable` mid-task, colliding with committed vectors of
  the same name (`ic_pen`, `ic_highlighter`) and failing the build with "Duplicate resources". The
  superseded `.xml` versions were deleted. `jc_eraser.png` is still sitting there — a typo for
  `ic_eraser.png`, unused, and left alone pending a decision on whether the eraser is being swapped
  too.

**The Move tool** hands single-finger drags to the canvas. Kept off `currentTool` on purpose: that
value goes into every stroke row and a pan is not a stroke. It needed a `oneFingerPan` flag because
a two-finger pan follows the midpoint while a one-finger pan follows the finger — using the midpoint
rule for one pointer jumps the canvas the moment a second finger lifts.

**Two fixes after looking at it on device:** the canvas was constrained to the rail's end, which
left a grey column where the old full-height sidebar had been — it now runs the full width with the
rail floating over it, which also means hiding the rail no longer resizes the canvas. And the card
looked grey because Material composites an elevation overlay into any card background: swapped 2dp
of elevation for a 1dp stroke. The eraser also had to be pointed at the new artwork —
`jc_eraser.png` was a typo, so `@drawable/ic_eraser` kept resolving to the old vector.

**Verified:** 13 instrumented tests pass, and on device the move tool pans without drawing, the eye
hides the rail, and drawing still lands under the finger. This time the database was backed up
before `connectedAndroidTest` and restored afterwards.

## 2026-08-07 (same session) — The drawing vanished on rotation

**Reported:** open a board in portrait, turn the phone, and the drawing is gone; draw in landscape,
turn back, same thing.

**Cause:** the canvas extent was defined as `getWidth() * CANVAS_SCREENS` — _relative to the window_.
Rotation redefines the entire coordinate space with it. On a 1080x2400 device a fresh board opens
centred at roughly (4860, 9810) and ink drawn there lands near y≈9800; rotated, the canvas is only
~8600 tall, so the ink sits outside it and clamping can never bring the window back to it. The
strokes were never lost — they were reloaded from the database every time and drawn somewhere
unreachable.

**Fix:** the canvas is now a fixed square, `CANVAS_SCREENS × the display's shorter edge`. That is
the one screen dimension rotation doesn't change, so ink stays in bounds whichever way up the
device is, and `centreOnContent` finds it after the fragment is recreated.

**Covered by two tests:** the canvas size is identical after a landscape re-layout, and ink drawn in
portrait is still inside both the canvas and the window once rotated.

**Checked on device** by locking the emulator to landscape (`settings put system
accelerometer_rotation 0` then `user_rotation 1` — the `wm`/`cmd window` rotation commands don't
exist on this image, and `user_rotation` is ignored while auto-rotate is on). All three strokes
survived the turn. The tool rail is taller than a landscape screen, and scrolls inside its card as
intended, so nothing becomes unreachable — but it is worth a proper landscape treatment eventually.

## 2026-08-07 (same session) — Scroll position indicators, and real stylus support

**Scrollbars** down the right edge and along the bottom, showing where the window sits on a canvas
ten screens across. No custom drawing needed: a `View` that declares `android:scrollbars` and
implements `compute{Horizontal,Vertical}Scroll{Range,Offset,Extent}` gets the framework's own, with
correct thumb size for free. The one non-obvious part is that `scrollTo` doesn't reveal them —
`awakenScrollBars()` has to be called from the pan path, which also means they fade out while you
draw instead of sitting on top of the drawing.

**Stylus: the honest answer was "partly".** Drawing with a pen already worked, because Android
delivers stylus input as ordinary touch events. What didn't exist was any _distinction_ between a
pen and a finger, so three things were added:

- **Palm rejection.** Previously the hand resting on the screen mid-stroke arrived as a second
  pointer, which discarded the stroke and started a two-finger pan — the pen would stop drawing the
  moment the hand touched down. Extra pointers are now ignored while a stylus is drawing.
- **The eraser end erases**, whatever the rail has selected — read from `TOOL_TYPE_ERASER` _or_ a
  barrel button in `getButtonState()`, because pens report it both ways.
- **Pressure scales stroke width**, clamped 0.5–1.5× and neutral at 1.0. Deliberately applied once
  at stroke start: a stroke carries one width in the database and `points_blob` is x/y pairs only,
  so per-point pressure would mean changing the storage format. Worth doing properly if pressure
  ever matters more than this. Finger pressure is ignored — it isn't the same measurement.

**Verified:** 20 instrumented tests pass, including a palm landing mid-stroke, the eraser end, and
pressure. The scrollbars were confirmed on device mid-pan. The stylus paths are covered by
synthetic `MotionEvent`s only — there is no pen on this emulator, so they have not met real
hardware.

## 2026-08-07 (same session) — Text on whiteboards, treated as a stroke

Asked whether text was worth adding and what it would cost; the answer was yes, but only the cut
that treats a label as an _item you place_, not an object you edit. That is what was built.

**The design constraint doing the work:** a text item is immutable. Place it, undo it, or clear the
board — there is no editing, no selection, no dragging. That keeps the board append-only, which is
exactly why whiteboards are the app's live-collaboration surface (two devices adding items never
conflict; two devices editing one text box always can). It also means no hit-testing and no
floating-editor-follows-canvas problem, which is where this feature gets expensive.

**Built:** `whiteboard_texts` table (schema v7, added via `ensureAdditiveSchema`, so existing boards
are untouched), `WhiteboardText` + `WhiteboardTextDao` shaped like `Stroke`/`StrokeDao`, a text tool
in the rail, and an `EditText` that appears at the tapped point. `setPanTool(boolean)` became
`setInputMode(MODE_DRAW|MODE_MOVE|MODE_TEXT)` — there are three things one finger can do now, and a
boolean was the wrong shape. Undo became a stack of `Undoable(id, isText, createdAt)`, rebuilt on
load by sorting both kinds together so strokes and labels interleave by when they were added.

**Details that were decisions, not defaults:**

- The editor **doesn't follow the canvas** — panning is suspended while it is open. One less thing
  to keep in sync, and it makes placement feel like a single act.
- **Single-line.** With `textMultiLine` the IME replaces Done with a newline, leaving no gesture
  that means "this label is finished". Found by trying it on device.
- Text draws **under** the ink, so a highlighter over a label highlights it rather than being
  covered by it.
- Text size follows the stroke-width picker (×4), so the rail keeps one meaning of "how big".

**A false alarm worth recording:** undo appeared to remove a stroke instead of the newer text. The
cause was the test data, not the code — seeded strokes carry _host_ timestamps and the emulator's
clock is ~10 hours behind the host, so they sort as newer than anything created on device. Verified
properly with two items both created on device; undo took the newer one.

**Verified:** the whole instrumented suite, 101 tests, passes (8 new ones for text). On device:
placed a label, committed with Done, reopened the board and it was still there, and undo removed
the most recent item.

**Left undone on purpose:** board text is not searchable. Home matches whiteboards by title only,
and these labels are the first real content a board has — the obvious next win, and a search-side
change rather than a whiteboard one.

## 2026-08-07 (same session) — Paper styles, and the eraser that was never an eraser

**Asked** how hard three backgrounds would be — white, yellowish, dotted. The backgrounds
themselves are trivial. The answer was that they have one non-obvious dependency, and it is the
interesting part of this change.

**The eraser was painting opaque white strokes**, not erasing. On a white board that is
indistinguishable from erasing, which is exactly why it lasted this long — the flaw was recorded
back when the canvas was first documented and never bit. Put a warm or dotted paper behind it and
every eraser stroke becomes a white smear. So this had to be fixed before backgrounds were worth
anything: ink is now drawn into its own layer (`canvas.saveLayer`) and the eraser uses
`PorterDuffXfermode(CLEAR)`, clearing back to the paper. The paper is drawn _before_ the layer or
CLEAR would punch through it as well. Old eraser strokes stored as white erase correctly under the
new rule, so nothing needed migrating.

**Built:** `whiteboards.background` (schema v8, additive), three styles on the view, a paper picker
in the rail (a menu, not a cycling button — three states whose order you can't see are worse to
cycle than to pick), persistence per board, and export that carries the paper.

**Detail worth keeping:** the dots are on a grid in _canvas_ coordinates, not window ones, so they
stay put under the drawing while you pan rather than sliding across it; only those inside the
visible rectangle are drawn, so a ten-screen canvas costs a screenful of dots.

**Verified:** `WhiteboardBackgroundTest` asserts on exported pixels — after erasing on warm paper
there must be no pure white anywhere, and the erased area must be paper. On device: all three
papers render, an eraser stroke through a thick line on dotted paper leaves the dots showing
through the gap, and the choice survives closing and reopening the board.

## 2026-08-07 (same session) — Paper as a preference, and "Updated now" on an untouched note

**Two asks: the chosen paper should apply to future boards, and opening something shouldn't mark it
as edited.**

**"Updated now" was one bug in two places.** Both the note editor and the whiteboard save on pause
whether or not anything was touched, and both save paths wrote `updated_at` unconditionally — so
merely opening either and backing out relabelled it and jumped it to the top of Home. Fixed by not
writing when nothing changed: `NoteRepository.saveNote` compares the incoming title and markdown
with what is stored (checked there rather than in the editor, because the markdown is already built
on that thread and it fixes every caller at once), and `WhiteboardFragment.saveTitle` remembers the
loaded title and skips the write when it matches.

**One trap in that fix:** `createNote` writes no `notes_fts` row, so skipping the first save on an
untouched new note would have left it permanently unsearchable. The guard therefore also requires
the note to be indexed already. A test covers it — and had to be made environment-aware, because
this emulator image ships without fts5 and `AppDatabase` deliberately skips the table when that
happens.

**The paper preference took two goes, and the reason is worth keeping.** The first version taught
`WhiteboardFragment` to read a preference when it creates a board — and boards made from Home's FAB
stayed white, because that is a _different creation path_: `WhiteboardRepository.createWhiteboard`,
which is the one actually used. Only the DB told the truth (`background=0` on the new row while the
preference file said `2`), which is why checking storage rather than the screen found it. Now a
shared `WhiteboardPreferences` holds the default and both paths read it. Existing boards keep their
own style — a preference must not repaper old work.

**Verified:** full instrumented suite, 110 tests. On device: opening the board and leaving it left
`updated_at` untouched (checked in the database, not just the label), and a board created from the
FAB opened on dotted paper after dotted was chosen elsewhere.

## 2026-08-07 (same session) — Whiteboard previews on Home

**Asked** for thumbnails on the Home cards, referred to the Figma `HomePage_whiteboard` frame
(node 32:600). The frame shows a preview image with the board's name and date beneath it — and no
glyph or stroke count, which is what the cards carried.

**This reverses a decision recorded on 08-03**: the card deliberately showed a glyph and a count
because a real preview meant reading every board's strokes just to draw Home, with no cover image
to cache. `WhiteboardThumbnails` answers that: only the cards on screen ask for a preview, and an
`LruCache` keyed by `id@updatedAt@background` renders each board once per change — the key carries
the modification time, so there is no invalidation call to forget.

**The preview goes through the export path** (`WhiteboardView.renderThumbnail`, which is
`exportToBitmap` with a smaller cap) rather than a second, simpler drawing routine. That matters:
a card shows the actual board — its paper, its erasures, its text — and there is no second
implementation to drift. Strokes are read on the disk thread; the render itself is on the main
thread, because it goes through a View and those are not built to be touched off it.

**Deviation from the frame, deliberate:** the design floats the image with no card behind it. Home's
collections and notes are all cards, so the card stays and the preview sits inside it — what
changed is the information: preview, name, date, no stroke count.

**Two things the screenshots taught:**

- A `MaterialCardView` does not clip its children to its rounded outline, so the preview's square
  corners sat proud of the card. Fixed with an outline provider on the image whose rounded rect is
  pushed a radius past the bottom edge, leaving only the top two corners rounded — the join with
  the title stays square.
- That rounding was impossible to confirm on a white or dotted board, since card, page and preview
  are all near-white. Switching a board to warm paper made it visible in one screenshot — and
  double-checked that a preview really does carry the board's own paper.

**Verified:** full instrumented suite, 111 tests. On device: a drawn board shows its drawing, an
empty one keeps the glyph placeholder, and the rounded top corners are correct on warm paper.

## 2026-08-07 (same session) — Whiteboards attached to notes

**Asked** for a whiteboard control in the note toolbar offering "new or import", an attached board
shown as a picture with a way through to it, and removal by long press or from that picture.
Followed mid-task by three more: search in the picker, move the whiteboard models next to the other
ones, and rename the DAOs to repositories.

**Built on the shape Epic D reserved**, `![whiteboard](quill://whiteboard/<id>)`. The design
decision that makes it small: a whiteboard embed **resolves without the media registry**. Image and
audio embeds are dropped when their asset row is missing, because the row is where the file path
lives; a whiteboard's id _is_ a `whiteboards` row, so `WhiteboardSegment.isMedia()` is false, no
asset row is written, and `replaceMediaAssetsSync` never touches it. The note points at the board
rather than owning it — so removing detaches, a board can be attached to several notes, and an
embed can outlive its board (shown as "This whiteboard was deleted").

**A trap that would have eaten notes:** `hasRealContent` decides whether an untouched note is
deleted on exit by enumerating segment types. A note whose only content was an attached board
counted as empty, so attaching a board to a blank note and backing out deleted the note. Caught by
reading that method rather than by testing — worth remembering that this list needs extending every
time a segment type is added.

**The picker got search** on request, following `AddExistingNotesDialog`'s idiom, with each row
carrying the board's preview: boards are usually untitled, and three "Untitled Whiteboard - Aug 7"
rows are no help when you are looking for the one with the diagram on it.

**Two housekeeping asks, both done:** `mse.quill.model` (Stroke, Whiteboard, WhiteboardText) moved
to `mse.quill.data.model` beside Note, Tag and the rest — 18 files updated. And the DAOs became
repositories: `StrokeDao` → `StrokeRepository`, `WhiteboardTextDao` → `WhiteboardTextRepository`,
and `WhiteboardDao` **folded into the existing `WhiteboardRepository`** rather than renamed, since
that name was taken. Its synchronous methods now carry a `Sync` suffix — `insertSync`,
`getByIdSync` — which keeps the honest bit visible: the naming is consistent now, the threading
still isn't, and that remains Epic A work.

**Verified on device end to end:** toolbar button → New/Import → searchable picker (filter tested)
→ embed rendered with its drawing and name → survives closing and reopening the note → tap opens a
sheet with the drawing and Open whiteboard / Remove / Cancel → Open navigates to the board →
long-press offers "Remove from note?" explaining the board is kept. 113 instrumented tests pass,
including two new document round-trip tests.

## 2026-08-08 — Feature implementation: note sharing via `.quill` bundle

**Built the file-based half of the sharing epic**: `share/QuillBundle` (format), `share/BundleWriter`
(pack), `share/BundleReader` (unpack), `data/NoteImporter` (insert). Reader/importer are split
deliberately so the format is testable without a database and a malformed file never reaches a
transaction.

**Key decisions, recorded in note.md:** the bundle carries `note.md` as the stored document verbatim
(not the lossy Markdown export — that flattens images/audio to placeholders, fine for another tool,
broken for another Quill); ids are always re-minted on import via `NoteDocument.rewriteEmbedIds`,
which drops any embed whose id isn't in the map (covers both a missing asset and a whiteboard embed,
since a bundle carries one note and boards aren't part of it); tags match by name case-insensitively
rather than by id; `created_at` is inherited, `updated_at` is now; media is moved before the DB
transaction opens rather than inside it, to avoid blocking the shared disk thread. Deviated from the
original plan by always zipping (never a bare `.md` for a media-free note) — one container means one
import path.

**Security-relevant, since a bundle is untrusted input from an unauthenticated transport:** zip entry
names are whitelisted to a plain filename directly inside `media/` (`".."` alone isn't a sufficient
check), and the 256 MB cap is counted as bytes arrive rather than trusted from the entry's declared
size, which a zip bomb writes itself.

**Boundary decided with Epic B:** a locked collection's notes are not shareable at all (not
unlock-on-both-ends) — a bundle is plaintext, so sharing one would be the lock's only hole. The
Export menu item stays tappable rather than greyed out or hidden, so the tap can explain why.

**UI:** Options → Export → third item "Share to another Quill" (`ACTION_SEND` + FileProvider, file
also lands in `Downloads/Quill`); Home's FAB gains "Import Note" (`ACTION_OPEN_DOCUMENT`, filter
`*/*` rather than `application/zip` since Quick Share types the file `application/octet-stream`).

**Left as polish, not done:** the `ACTION_VIEW`/`ACTION_SEND` intent filter for tapping a received
file directly — `MainActivity` is still `exported="false"`, and content sniffing after open would be
the only reliable check anyway.

**Scaffolding staged for the next feature, not yet wired to code:** while this was in flight, deps
for the P2P session-join feature were added ahead of time — `play-services-nearby`,
`play-services-code-scanner`, `zxing-core`, plus the full manifest permission ladder (Bluetooth/
location across API levels, `NEARBY_WIFI_DEVICES`, optional camera). No `ConnectionsClient`, QR, or
`HostApduService` code exists yet — noted in both note.md and requirements.md so it isn't mistaken
for the feature having started.

## 2026-08-08 (same session) — Bug fix: multi-catch compile error, continued from a prior session

Picked up mid-task in a fresh session (previous session's context wasn't carried over). Build was
failing: `NoteImporter.java` caught `IOException | SecurityException | RuntimeException` in one
multi-catch, which javac rejects since `SecurityException` is already a `RuntimeException`. Dropped
`SecurityException` from the list. Then updated note.md and requirements.md to reflect the session's
state (see above) per explicit request to keep memory current.

## 2026-08-08 (same session) — Feature implementation: sharing extended to whiteboards and collections

**Asked**, after being shown that only notes could be shared: extend the same idea to whiteboards
and to whole collections. Neither was in the original requirements.md plan, which only ever
specified a note bundle.

**Whiteboard side.** `share/WhiteboardBundle` (`.quillboard`) is JSON, not a zip — unlike a note, a
board has no files to carry, only point lists and strings, which JSON already stores natively, so
wrapping it in a zip would be a container for a container. `authorId` on a `Stroke`/`WhiteboardText`
is dropped rather than carried and ignored, since it's a live-collaboration field this single-device
format has no reader for. `WhiteboardFragment`'s single export button became a `PopupMenu`: the
existing flat-PNG export stayed (lossy, a picture), joined by **Share whiteboard** (lossless, another
Quill can keep editing it). `data/WhiteboardImporter` mints fresh ids on the way in, the same rule
`NoteImporter` already follows for a note.

**Collection side.** `share/CollectionBundle` (`.quillpack`) is a zip of zips — `manifest.json` plus
one `notes/<n>.quill` per member note, each a complete, ordinary `.quill`. The design choice that
kept this small: nothing about a single note's format changes. `data/CollectionImporter` makes the
new collection, then runs the _existing_ note importer once per entry — which required promoting
`NoteImporter`'s private `insert` to a public `insertBundle(contents, collectionId)`, the one place
a collection import differs from a lone note import (which still passes `null` and lands the note
loose on Home, as before). A corrupt member note is skipped rather than failing the whole pack,
reported back as "N of M imported". A new `NoteRepository.loadForBundleSync` (title, segments, tags,
timestamps, synchronous) backs the writer's loop over note ids — the existing `loadNote` is async
and shaped for one screen, not a batch on an already-disk-thread caller.

**The routing problem this created, solved by the manifest.** Home's import picker is `*/*` with no
signal upstream about which of three formats a file is. Each reader already threw
`InvalidBundleException` on a file that isn't its own shape, which is what makes trying them in
sequence (note → whiteboard → collection) safe: a `.quill` note's `manifest.json` never carries
`CollectionBundle.KEY_NOTE_COUNT`, so the collection reader rejects it correctly, and vice versa; the
whiteboard reader rejects both outright since its JSON has an explicit `"type":"quillboard"` and
neither a note zip nor a collection zip is valid JSON at all.

**Same locked-collection boundary carried over**: `CollectionDetailFragment`'s new share button asks
`CollectionRepository.isLocked` before packing, exactly like a single note's Export menu already
does.

**Verified via `assembleDebug`** rather than on-device this pass — a full compile and resource build
succeeded, but the three new share/import paths haven't been exercised on the emulator or a device
yet (left for the user, who said they'd debug independently going forward).

## 2026-08-09 — Bug fix: a note's attached whiteboard wasn't in its `.quill` export

**Reported:** a note with an embedded whiteboard, exported/shared as `.quill` and imported on
another device, loses the board.

**Root cause:** `BundleWriter` only ever packed segments where `isMedia()` is true — image and
audio. A `WhiteboardSegment` isn't media by design (see note.md's "Whiteboard embeds are built"),
so it was silently skipped: the embed line still serialized into `note.md`
(`![whiteboard](quill://whiteboard/<id>)`), but nothing behind it made the trip. Worse than a
merely missing board: `NoteDocument.rewriteEmbedIds` drops any embed line whose id isn't in the
importer's id-remap table, and the whiteboard id was never added to it — so the embed line itself
vanished on import, leaving no trace it had ever been there (not even the "This whiteboard was
deleted" placeholder a dangling reference normally renders as).

**Fixed by reusing, not duplicating, the standalone `.quillboard` format.** `BundleWriter` now
also packs each attached whiteboard's row/strokes/text under `whiteboards/<id>.json` inside the
`.quill` zip, via the existing `WhiteboardBundleWriter`/`WhiteboardBundleReader` (built 2026-08-08
for the standalone board-sharing format) rather than inventing a second serialization. `BundleReader`
parses those entries the same way it already treats `media/`. `NoteImporter` mints the new
whiteboard its own fresh id _before_ calling `NoteDocument.rewriteEmbedIds` — into the same
id-remap map media assets already use, since the rewrite matches purely on the id inside a
`quill://` line and doesn't care which kind of embed it names — then inserts the whiteboard, its
strokes and its text items inside the same transaction as the note. A whiteboard whose row was
already gone on the sender's device (a dangling embed) is left exactly as dangling on the receiver,
rather than inventing a board that never existed.

**Also flowed through for free:** `CollectionBundleWriter`/`.quillpack` calls `BundleWriter.write`
per note, so a collection export now carries its notes' whiteboards too — no separate fix needed
there, just a `Context` parameter threaded through.

## 2026-08-09 — Feature implementation: `.quill` opens Quill directly

**Asked:** can receiving a `.quill` on another device import it automatically, instead of the
recipient having to open Quill and use Home's manual Import option.

**This was already flagged as the remaining, unbuilt half of Epic C's note-sharing checklist**
(memory/requirements.md — "Polish, expect flakiness"), for a documented reason: a file that
arrives over Quick Share is typed `application/octet-stream` by the transport, not `.quill`'s real
`application/zip`, and has no meaningful path for an intent filter's `pathPattern` to match — so
the filter can only be as good as the mime types it lists, and the real check still has to happen
after the file is opened.

**Built:** `MainActivity` (previously `exported="false"`, i.e. unreachable from outside the app)
now declares an `ACTION_VIEW` intent-filter matching `application/zip`/`application/json`/
`application/octet-stream` over both `content` and `file` schemes, and `android:launchMode=
"singleTask"` so a second file opened while Quill is already running reuses the one Activity
(`onNewIntent`) instead of stacking a second on top of the app's single nav host. The Uri is held
until Home is the resumed fragment (a `FragmentLifecycleCallbacks` watch, not a fixed delay — cold
start and warm start take different amounts of time to get there) and popped back to if the user
was elsewhere, then handed to a new `HomeFragment.handleSharedFile`, which is just a public alias
for the same three-format-cascade `importBundle` already runs for a manually picked file — same
Snackbar-with-Open-action outcome either way.

**Deliberately not covered:** `ACTION_SEND` (Quill doesn't need to _receive_ a share targeting
itself as an attachment recipient today) and `pathPattern` matching (per the reasoning above,
tried in an earlier draft and abandoned — it degrades a `content://` Uri delivery to guessing).
