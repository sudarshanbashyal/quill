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
swipe. Claiming the gesture on ACTION*DOWN and handing it back at the row's end got horizontal
right, but **a vertical drag starting on a tag then did nothing at all**. Worth keeping: the
usual `requestDisallowInterceptTouchEvent(true)` travels all the way to the `CoordinatorLayout`,
which cancels its behaviours on the spot, and `AppBarLayout` can't pick a drag back up
mid-stroke — so the header refused to collapse for any swipe that began on a tag. The fix was a
`PinnedBandScrollView` the tag strip could ask \_directly*, keeping the claim local so nothing
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

## 2026-08-08 — Research: scoping a Wear OS companion

**Asked** what features and integrations would actually make sense if Quill were extended to
Wear OS. No code — a scoping discussion, written up as [requirements.md](requirements.md)'s Epic J.

**The framing that decided everything else:** a watch is good at _capture_ and _micro-review_, and
bad at _authoring_ — and Quill's phone app is overwhelmingly authoring. So the epic ports two
features rather than shrinking the app, and its "Out of scope" list carries as much of the design
as its checklist does.

**What crosses over, and why it's cheap.** Flashcard review is the feature that justifies a watch
app at all: `ReviewSession`'s entire API is front → flip → right/wrong, which is already the
interaction a watch supports, and the payload is a few strings and four SM-2 ints. The affordability
is an accident of Epic A — `FlashcardScheduler`, `ReviewSession`, `QuizSession`, `QuizGenerator`
and `QuizRules` import nothing but `java.util`, kept that way so they could be JVM-tested without a
device. That property lets a `:study` module be shared with a `:wear` module so SM-2 cannot drift
between the two, and it makes the module extraction the first task in the epic rather than a
cleanup at the end. Voice capture is the second: the one authoring act a watch does better than a
pocketed phone, and the receiving end (`AudioRecorder`, audio segments, waveform) already exists.

**Nearly free, so taken:** `AudioPlaybackService` already runs a real `MediaSession` with a
`PlaybackState` and a `Notification.MediaStyle`, so the watch's media card can drive read-aloud
with no new playback code — the work is bridging configuration, not playback.

**Sync decision: a projection, not a replica.** The watch holds today's due cards and nothing else,
as a `DataItem`; reviews travel back as append-only `(card id, grade, timestamp)` events replayed
through the phone's scheduler, never as SM-2 state computed on the watch. That is Epic C's
append-only-strokes reasoning reused — it makes the merge a dedupe. The 100 KB `DataItem` cap
happens to forbid the wrong design anyway: no media, no asset registry, no `content_blob`.

**Two things it makes into dependencies.** A tile showing a stale due count is worse than no tile,
so Epic D's unbuilt reminder infrastructure stops being filler. And Epic E's unbuilt True/False
fallback turns out to be the only watch-shaped quiz — two buttons, one question — so if quizzes go
to the watch, T/F gets built watch-first rather than the MCQ session being squeezed down.

**The one open decision, flagged rather than settled:** Wear's view-based widgets are legacy and the
supported path is Kotlin + Compose, against a Wear Material library that is not the MDC one Epic H
standardized on. That is a defensible divergence from the project-wide Material 3 convention, but it
needs deciding deliberately before the module exists, not discovered mid-build.

**Ruled out, with reasons recorded:** whiteboards (a ten-screen pannable canvas has no watch form),
MCQ quizzes as built, note browsing (if the watch is in range, so is the phone), and sensor
gimmicks — a watch makes Epic G tempting, but heart-rate-during-review is a demo, not a feature.

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

## 2026-08-09 (same day) — Bug fix: exported `.quill` arrived as `.zip`

**Reported after testing the above:** an exported note is still named `….zip`, not `….quill`, and
the tap-to-import filter above therefore never had a real `.quill` to catch.

**Root cause, once actually checked rather than assumed:** `QuillBundle.MIME_TYPE` was
`application/zip` — a real, IANA-registered type. `NoteExportStore.saveViaMediaStore` (API 29+)
inserts the file through `MediaStore` with that MIME type, and `MediaProvider` corrects a saved
file's extension to match any MIME type it recognises, the moment the row is written — silently
renaming `note_20260809_120000.quill` to `.zip` regardless of the `DISPLAY_NAME` requested. Same
story for `.quillboard` (`application/json` → `.json`) and `.quillpack` (`application/zip` →
`.zip`). Every save _and_ every `ACTION_SEND` share reuses the same one `MIME_TYPE` constant per
format, so the fix is one line per format rather than a call-site hunt.

**Fixed:** all three now declare vendor types instead — `application/x-quill`,
`application/x-quillboard`, `application/x-quillpack`. Nothing in `MimeTypeMap` recognises them,
so `MediaProvider` has nothing to correct the extension against, and the requested extension
survives. The manifest's `ACTION_VIEW` filter (added earlier the same day) gained these three
alongside the generic `zip`/`json`/`octet-stream` entries it already had, since a transport is
still free to relabel a file to something generic on the way and both need to be caught.

**Worth remembering:** check what actually wrote the bytes (here, `NoteExportStore`'s `MediaStore`
insert) before reasoning about what carried them — the first-pass theory blamed the transport for
what turned out to be a save-time rename.

## 2026-08-11 — Feature implementation: live whiteboard collaboration (Epic C)

**Asked:** build the live whiteboard session the requirements had only designed — host/join over
Nearby Connections, QR as the token carrier (NFC deferred), snapshot/stroke/text/retract messages,
and host-only Clear. Confirmed upfront: build session-join and message sync together in one pass,
QR only, and two physical devices are available for real verification.

**Built:** `collab/CollabMessage` (JSON wire format for `snapshot`/`stroke`/`text`/`retract`/
`clear`), `collab/CollabSession` (Nearby `ConnectionsClient` wrapper, host or joiner, one fixed
`SERVICE_ID` with the session token as the advertised endpoint name), `collab/QrCodes` (zxing
encode), `ui/whiteboard/CollabDialogs` (entry/host/joining dialogs, built in code like
`WhiteboardDialogs`). Wired into `WhiteboardFragment` via a new "Collaborate" toolbar button:
Host shows a QR and waits; Join opens `GmsBarcodeScanning`'s own scanner UI (Quill never holds
`CAMERA`) and connects on a successful scan.

**Why no accept dialog:** the token *is* the authorisation. A host only advertises under it and a
joiner only discovers by matching it, so reaching `onConnectionInitiated` on either side already
proves the other device saw the QR — a second "accept this stranger?" prompt would be asking about
someone who, by construction, already passed the real gate.

**Undo/Clear, enforced by construction rather than by checking a permission:**
- Undo only ever pops `WhiteboardFragment`'s own `undoStack`, and messages received from a peer
  are applied to the view/DB directly without ever being pushed onto it — so "retract only your own
  last item" needed no author check, it's just what the stack already contains.
- Clear is host-only: `btnClear.setEnabled(isHost)` while a session is live, and the host's clear
  travels as a `CLEAR` message rather than each device clearing on its own. `CLEAR` isn't one of
  the three messages requirements.md named — it was added because a destructive, everyone-affecting
  action still has to travel as a message like the other three, it just isn't append-only.

**Snapshot semantics:** on join, the host reads its current strokes/text straight off disk (not off
the view, which could be stale) and sends everything; the joiner wipes its own board and DB first,
then loads the host's snapshot as ground truth. That's a replace, not a merge — there's no vector
clock or CRDT reconciliation here, matching the plan's "dedupe by id" scope, which only covers
*during* a session, not reconciling two boards that diverged before one connected.

**Deferred, stated rather than hidden:** NFC carrier (QR alone ships and tests fine); payload
chunking for a stroke/snapshot that would exceed Nearby's ~32KB `BYTES` cap (not hit in testing);
"tap to send a note" as a `FILE` payload (the obvious next near-free feature once this transport
existed, per requirements.md, just not asked for this pass).

**Verified:** `./gradlew :app:compileDebugJavaWithJavac` and `:app:assembleDebug` both clean.
**Also verified end-to-end on two physical devices** in the same session — see the next entry for
a bug the first real run caught.

## 2026-08-12 — Bug fix: joiner crashed on receiving the host's snapshot

**Reported:** the first live two-device run — as soon as host and joiner connected, the host
reported the session had ended and the joiner's app had dropped to the home screen.

**Found:** not two bugs, one. The joiner's process had actually crashed
(`SQLiteConstraintException: FOREIGN KEY constraint failed`, from `StrokeRepository.insertStroke`
inside `applySnapshot`), which is what silently sent it to the home screen; the host's "session
ended" message was just its normal, correct reaction to losing the connection that crash caused.

**Root cause:** the host and joiner each have their own row in their own `whiteboards` table —
there is no shared board id between two devices. But a received `Stroke`/`WhiteboardText` still
carried the *sender's* `whiteboardId`, unchanged, straight into a local `insertStroke`/`insert`
call. Inserting a row whose `whiteboard_id` names a board that doesn't exist on this device is
exactly the case `strokes.whiteboard_id`'s foreign key exists to reject, and the resulting
uncaught exception (thrown from a background thread with no catch around it) took the whole
process down rather than just failing that one insert.

**Fixed:** re-tag every received item onto `whiteboardId` — this device's own board — before it
touches the view or the database, at all three receive sites (`TYPE_STROKE`, `TYPE_TEXT`, and the
loop inside `applySnapshot`). `authorId` was deliberately left alone; it's specifically a foreign
key onto a *local* table that can never be trusted verbatim off the wire, and the fix generalises
to any future message that carries one.

**Verified**: rebuilt, reinstalled on both devices, redid the full flow — connect, live stroke
sync, undo (confirmed it only ever retracts your own last item), and clear (confirmed disabled on
the joiner, and wipes both boards when triggered from the host). All working. Also confirmed as
intentional, not a leftover: a joiner keeps the host's drawing in its own local `strokes` table
after the session ends — joining is a one-time copy, the two boards are independent again once
the session is over, same shape as importing a shared note.

## 2026-08-13 — Profile screen + optional app-wide biometric lock

**Asked:** a security pass, starting with an optional biometric lock at app open, toggled from a
Profile page; per-collection PIN locks floated as a second idea.

**Design decision — one authenticator, no Quill passcode.** The per-collection *PIN* idea was
argued down and replaced with the same device credential the app lock uses
(`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`). Three reasons, in order of weight: a custom PIN needs a
recovery path, and every version of that either weakens the lock or loses the notes; a custom PIN
cannot gate an Android Keystore key, so Epic B's real encryption would have to hand-roll its own
crypto instead of using `setUserAuthenticationRequired(true)`; and two different secrets for two
levels of the same app is a model users won't hold. Per-collection locking stays in Epic B — this
pass is the gate only.

**Stated plainly in the UI, because it's the honest framing:** the app lock is a door, not a safe.
`quill.db` is plaintext on disk either way, so this defends against a phone already unlocked and in
someone else's hand — nothing more. The Profile screen says so under the toggle, so the switch
can't be mistaken for encryption.

**Built:** `AppLock` (prefs, availability, session state, prompt), `ProfilePreferences` (display
name, notifications placeholder), `ProfileFragment` + a fourth bottom-nav tab, `DataWipe`, and the
gate overlay in `activity_main`. Home's greeting picks up the display name.

**Grace period defaults to 1 minute, not "Immediately".** Importing a file, copying from another
app, answering a notification — all stop the Activity, so a zero grace re-prompts on the way back
from each one, which is the behaviour that gets app locks switched off.

**Recents snapshot is part of the lock.** The gate is raised in `onPause`, not just `onStart`:
without it the task switcher keeps showing whatever note was open, and the lock is bypassed by a
gesture. The *grace period* still starts in `onStop`, because a dialog or the system's own
biometric sheet pauses nothing but does sit in front of the app.

**Bug found on-device, worth remembering:** `BiometricPrompt`'s callback lives in an
Activity-scoped ViewModel that holds exactly one callback — the most recently constructed prompt's.
The gate built its prompt once in `onCreate`; the moment the user had visited the Profile tab, that
fragment's prompt claimed the slot, and the gate's unlock result was delivered to Profile's
listener instead. Symptom: credential accepted (`resetLockout … hat=present` in logcat), gate stays
up over an unlocked app. Fix: `AppLock.authenticate()` now always constructs immediately before
showing, so claiming and showing are inseparable, and `MainActivity` re-claims in `onCreate` *only
when the gate is due* — unconditionally would swallow Profile's own answer after a rotation.

**Delete-all-data** is two steps: a warning naming what goes, then a typed `DELETE` with the
confirm button disabled until it matches. One "are you sure?" is dismissed by the same reflex that
opened it, and this is the only action in Quill with nothing behind it. The wipe drops the DB,
empties `filesDir`/`cacheDir` (recordings and images would otherwise be orphaned but still on
disk), clears all three preference files, and relaunches via `CLEAR_TASK` — the back stack holds
ids of rows that no longer exist.

**Verified on emulator-5554**, including the destructive path: set a device PIN with
`locksettings set-pin 1234`, backed the app data up with `run-as … tar` first, confirmed the wipe
emptied every table and reset both prefs to `<map/>`, then restored the backup (1 note, 3
whiteboards, 5 strokes back). Note the emulator now has PIN 1234 set and the app lock left on.

## 2026-08-13 (later) — Collection lock: real per-collection encryption

**Asked:** implement the collection lock, and cap the display name at 20 characters allowing
letters, numbers, `-`, `_` and emoji.

**Display name** (`ui/profile/DisplayName`): an `InputFilter` on the field rather than validation
on save, so the keystroke is refused instead of the text being accepted and complained about
later. Counted in **code points, not chars** — an emoji outside the BMP is a surrogate pair, so a
char-based cap would charge 😀 twice and a truncation could cut between the halves and leave a
lone surrogate. Emoji are admitted by `Character.getType` (OTHER_SYMBOL plus the modifier /
non-spacing / enclosing / format types that carry ZWJ sequences, skin tones, variation selectors
and keycaps) rather than by a range list that would rot. Covered by `DisplayNameTest` — 11 JVM
tests, which is also the only practical way to test the emoji cases, since injected key events
can't produce them. **Note: spaces are excluded**, per the literal list given; worth confirming.

**Crypto design.** One AES-256-GCM key per collection in the Android Keystore
(`quill.collection.<id>`), `setUserAuthenticationRequired(true)` with a **5-minute validity
window** rather than a per-use `CryptoObject` — the per-use form means a fingerprint per cipher
operation, i.e. per note, for someone reading through a collection. The key material never leaves
the TEE, which is the property that makes this encryption rather than a second lock screen: a copy
of `quill.db` is undecryptable anywhere else, even with the user's PIN.

**No per-row "encrypted" flag.** Whether a row is ciphertext is decided solely by its collection's
`biometric_locked` column, which the migrations flip *inside the same transaction* that rewrites
the rows. A second source of truth could disagree with the first, and a flag an attacker can clear
is worse than none.

**The leak surfaces were most of the work.** Encrypting the notes is not the same as hiding them:
`getAllNotesSync` had no collection filter, so a shut collection's previews — the first line of
each note — would still be on Home, including pinned ones that never go through a collection
screen. Also closed: search (which filters the same list), the flashcard decks list, the quiz
list. Two stores had to be emptied rather than filtered, because they hold *plaintext copies*:
`notes_fts` (the body as searchable text) and `flashcards` (`front`/`back` copied from Q&A
blocks). Deleting the cards costs the SM-2 schedule, so the confirmation dialog says so.

**A hole worth remembering:** `assignCollection` set `collection_id` and nothing else. Moving a
plaintext note *into* a locked collection would leave bytes and format disagreeing — read as
ciphertext, fails to decrypt. It now converts in both directions, or abandons the move.

**Mid-edit re-lock is handled, not ignored.** If the key's window closes while a note is open,
`saveNote` writes nothing and reports `onNeedsUnlock`; the editor offers a Snackbar action to
re-authenticate and retry. Nothing is lost — the editor still holds the text — which is why it's
an offer rather than a warning.

**Verified end-to-end on emulator-5554**, with the DB pulled and inspected at each step: locking
produced Base64/binary ciphertext with **no plaintext anywhere in the .db file** (`strings | grep`
found nothing); backgrounding re-locked and the notes vanished from Home; re-opening prompted and
decrypted; editing while open re-encrypted on save; removing the lock decrypted everything back and
cleared the flag. Test data was seeded by pushing a modified DB and removed afterwards by restoring
a `run-as tar` backup.

**Not done, stated rather than hidden:** media files (`note_segments.file_path`) are still
plaintext on disk. They're unreachable through the UI while a collection is shut, but a filesystem
copy gets the images and recordings. See the deferral note in requirements.md — it needs a
decrypt-on-demand path through four decode sites, and audio needs a real seekable file.

## 2026-08-13 (later still) — Study reminders for flashcards

**Asked:** make the Profile screen's placeholder "Study reminders" switch real.

**One-time work that re-arms itself, not `PeriodicWorkRequest`.** This is the decision the whole
feature turns on. A periodic request measures its period from whenever it was enqueued and the
system may slide each run within a flex window, so "remind me at 20:00" drifts into the afternoon
inside a week — there is no way to pin periodic work to a wall clock. Instead each run is a
`OneTimeWorkRequest` whose initial delay is computed to the next occurrence of the chosen time,
and the worker queues tomorrow's before it finishes. Recomputing from the calendar every run is
also what absorbs a DST change, a timezone move, or the device being off over the scheduled
moment — none of which a fixed 24-hour period survives.

**WorkManager over AlarmManager** for the persistence: it re-registers its queue after a reboot by
itself, where AlarmManager would need a `BOOT_COMPLETED` receiver, its permission, and the
discipline to re-arm from it.

**The re-arm is in a `finally`.** A reminder that stopped scheduling itself because one run threw
would be a feature that silently died months later. `MainActivity.onCreate` also calls
`StudyReminders.sync()` on every launch as the recovery path — some OEM builds drop WorkManager's
pending jobs on force-stop, and without that the switch would keep claiming to be on.

**Nothing is sent when nothing is due.** A daily "0 cards due" is how a reminder trains someone to
ignore it, and then to switch it off.

**Locked collections are excluded**, and in a background worker that is total — nothing is
unlocked there, so every locked collection is hidden by the same `NoteCrypto.hiddenCollectionIds`
the UI uses. That's intended, not a limitation: a lock-screen line reading "3 cards due" for a
collection the user deliberately encrypted would announce both that it exists and that they've
been neglecting it. The Profile screen says so under the switch, or the absence looks like a bug.

**Permission at the moment it means something:** POST_NOTIFICATIONS is requested when the switch
is first turned on, not at launch, and a refusal leaves the switch *off* rather than on-and-mute.

**Verified on emulator-5554, end to end:** job scheduled at `+3h41m47s` from 16:18 for a 20:00
reminder (exact); notification posted with the right plurals ("3 flashcards are due" / "In 1
deck."), on the right channel, `VISIBILITY_PRIVATE`; tapping landed on the Flashcards tab with the
tab selected; the time picker came up in the device's 12-hour format pre-set to the stored value;
and a run with nothing due posted nothing while still re-arming for tomorrow. Turning the switch
off cancelled the job (confirmed via `dumpsys jobscheduler`).

**Gotcha worth recording for future emulator testing:** the emulator's clock was four days *behind*
the host's. The first attempt seeded `next_review` from `date +%s` on the Mac, so the cards weren't
due by the device's reckoning and the worker correctly did nothing — which looked like a bug for
about ten minutes. Seed time-sensitive rows from `adb shell date`, not the host clock.

**`StudyRemindersTest`** covers the delay arithmetic on the JVM: time later today, time already
passed, time exactly now (must be tomorrow — a zero delay would re-fire the worker in a loop), the
midnight boundary, and the invariant that the delay is always in (0, 24h].

## 2026-08-13 (later still) — Two leaks in the collection lock: ciphertext titles, visible whiteboards

Both reported as bugs against the lock shipped earlier today, and both the same mistake in two
places: **hiding a shut collection is only half of what a read path owes the lock.** The other half
is that a collection which *is* open is still encrypted at rest, so its titles come back from SQL
as Base64 and have to be decrypted on the way out.

**Encrypted titles on the Flashcards and Quizzes tabs.** `FlashcardRepository.loadDecks` and
`QuizRepository`'s summary query both filtered on `NoteCrypto.hiddenCollectionIds` and then rendered
`n.title` straight from the cursor. Unlock the collection and its decks reappear — titled in
ciphertext. Both queries now also select `n.collection_id` and run the title through
`NoteCrypto.titleForDisplay(lockedIds, …)`, which passes plaintext through, decrypts what's locked,
and returns null when the key has gone (`decryptTitleOrNull` re-locks the collection on the way
out) — in which case the row is dropped rather than shown unnamed. Two new helpers carry this:
`hiddenOf(lockedIds)`, so a caller that needs *both* sets doesn't derive one twice, and
`titleForDisplay`. `NoteRepository.getAllNotesSync` lost its duplicated copy of the derivation.

`loadQuiz` — the single-quiz path behind the detail and session screens — had no lock filter at all
and now has one. Returning null there is already the "deleted from underneath you" case both
callers handle by leaving the screen.

**Whiteboards were never gated at all.** `loadWhiteboards` had no notion of the lock, so a board
attached to a note in a locked collection sat on Home with its *thumbnail rendered* — the drawing
itself, not just a title. The lock migration converts note text and nothing else, so strokes are
plaintext rows regardless; this filter is the whole of what keeps a locked note's drawing off the
screen. `getByIdSync`/`getByNoteIdSync` refuse a hidden board too — holding an id is not permission
to read it, and the note embed, the thumbnailer and the share bundle writer all reach boards that
way. A standalone board (no `note_id`) is in no collection and is never hidden; the `LEFT JOIN`
gives it a null `collection_id`, which `hiddenClause` already passes.

`WhiteboardFragment` also re-checks on resume and leaves if the board no longer resolves. That's
for the case the list filter can't reach: leaving the app re-locks every open collection
(`MainActivity.onStop`), so a board opened through a locked note would otherwise still be sitting
there, fully drawn, on return.

**Verified on emulator-5554** with a fixture DB pushed via `run-as` (a locked collection and an
open one, each with a note, a board with strokes, a deck and a quiz; plus standalone boards). The
pre-fix build put "Private sketch" on Home with a visible stroke while correctly hiding its note;
the fixed build drops it and keeps the open collection's board and both standalone boards. The
Flashcards and Quizzes tabs list only the open collection's note, titled normally.

**Emulator gotcha:** `screencap` returns an all-black PNG while `BiometricPrompt` is up, and the
emulator has no fingerprint enrolled, so `adb emu finger touch 1` does nothing. Testing anything
behind the app-lock gate means flipping `app_lock_enabled` to false in `shared_prefs/
security_prefs.xml` (back up the original and put it back afterwards).

**Still plaintext at rest, and knowingly so:** whiteboard strokes, board titles, and the `w.title`
column are not encrypted by the lock — only note titles and bodies are. Everything above is
display-level gating over unencrypted rows. Encrypting strokes would need a stroke-blob migration
and a decision about what live collaboration does with a locked board; not attempted here.

## 2026-08-13 (same session) — "Import whiteboard" left the board visible after locking

Follow-up to the whiteboard gating above: creating a board *from* a note hid it correctly, importing
an existing board into a note didn't. The cause is a real gap in the model, not an oversight in the
filter. `whiteboards.note_id` records the note a board was **created from**; "Import whiteboard"
attaches an existing board by writing an embed line into the note's Markdown and never touches that
column. So the board stayed unowned as far as SQL was concerned, and the gate had nothing to test.

**Why the Markdown can't be the answer.** The obvious fix — ask which notes reference this board —
fails exactly where it matters: a locked note's body is ciphertext. The reference is unreadable
precisely when we need to know it's there.

**So there is now a `note_whiteboards` table** (`note_id`, `whiteboard_id`, many-to-many, DB
version 9), and it is emphatically *an index, not a source of truth*: rewritten from the document on
every save, on both directions of the lock migration, and on bundle import. Nothing reads it to
decide what a note contains. Many-to-many because embedding is — the same board can be imported into
a second note without leaving the first, so a board is hidden if **any** note holding it is hidden.
That means a board embedded in both a locked and an unlocked note disappears from the unlocked one
too; the safe direction, and the alternative is a board the lock doesn't cover.

`NoteDocument.whiteboardIdsIn(markdown)` reads the embed lines, so the link rows and
`toMarkdown` can't drift apart the way a private regex would.

**Backfill, in three places, because one isn't enough.** The migration seeds the table from every
note it can read — which is every note *not* in a collection that was already locked when the
upgrade ran. Those it skips get their rows the next time `CollectionLockRepository` holds their
plaintext, which is either direction of lock/unlock, or on the next save. The residual gap is a
collection locked before the upgrade and never touched again; it closes the moment it's opened and
anything is saved.

**Found while in there:** `ensureAdditiveSchema` never added `collections.biometric_locked`. It
shipped in `onCreate` only, so any database created before yesterday would upgrade into a build that
queries a column it doesn't have — "no such column" on every read that consults the lock, on exactly
the devices with notes worth locking. Fresh installs and the wiped emulator hid it. Added as an
`addColumnIfMissing` step.

**Verified on emulator-5554, through the real UI:** upgraded a v8 database (user_version went to 9,
and the backfill correctly picked up a pre-existing embed nobody remembered — the untitled note
holds `seed-wb`); imported "Scratch pad" into a note via Import whiteboard, which wrote the embed
line and the link row; flipped that collection's `biometric_locked` and restarted — the imported
board disappeared from Home along with the board owned by the same note, while unembedded standalone
boards stayed. Flipping the flag back brought both back. The picker itself already excluded the
locked collection's board, since it reads `loadWhiteboards`.

## 2026-08-13 (same session) — Untitled notes had no name on the study tabs; the lock gate showed behind the share sheet

**Two more, both older than the lock work.** Neither was a regression from the encryption fixes,
which is worth recording because the first one arrived as "I don't see the titles *now*".

**Unnamed notes lost their name on Flashcards and Quizzes.** An untitled note stores an empty title
on purpose — the editor offers "Untitled Note - <date>" as the field's *hint*, one keystroke from
being replaced, and every list resolves it at display time through `NoteDisplayUtils`. Home does
that; the two study tabs never did, so a deck or quiz made from an unnamed note arrived with a blank
title line. `git show HEAD` confirms the same `""` before any of this session's changes.

Fixed at the display end, where the fallback already lives, rather than by writing the generated
name into the column — persisting it would turn a placeholder into a real title the user then has to
delete before typing their own, which is exactly what the hint exists to avoid. `resolveTitle` gained
an overload taking `(title, createdAt)` for callers holding a deck or a quiz rather than a `Note`,
and both queries now carry `n.created_at` so the fallback can be dated from the note rather than
from the quiz row. The delete confirmations name the deck properly now too.

**The lock gate painted behind every share sheet.** `onPause` raised the gate whenever the app lock
was on. The comment explained why — keeping the open note out of the recents thumbnail — but it
treated every pause as a departure, and most pauses aren't: a share chooser is translucent and pauses
without stopping, so "Quill is locked" sat behind it while the user was in the middle of sharing from
Quill. File pickers and permission dialogs did the same.

Now `onPause` adds `FLAG_SECURE` instead and `onResume` clears it. Invisible from the front, and it
applies to the task snapshot — which is the only thing that ever needed covering. Coming back is
still gated, because `onStart` raises the gate before anything is drawn if a prompt is due.

**Not verified end to end, and worth knowing why:** demonstrating the share case needs the app lock
*on*, and getting past the gate to reach a note needs the emulator's device credential, which I
don't have (no fingerprint is enrolled, so `adb emu finger touch` is inert, and enabling the lock
from Profile authenticates first). The untitled-title fix *was* verified — both tabs now read
"Untitled Note - Aug 7, 2026" and "Untitled Note - Aug 13, 2026", dated per note.

**Also worth knowing:** with the grace period set to "Immediately" (the emulator's setting; the
default is one minute), returning from a share still costs a prompt. That is the grace period doing
what it says, not the gate bug coming back.

## 2026-08-13 (later) — Wear OS: the `:study` extraction, and phase 1 of the companion

"watchOS" here meant **Wear OS** — Epic J. Started as a design discussion and ended with three
modules and a tile rendering on an emulator.

**What had gone stale in Epic J**, scoped 08-08 and read again today: the tile's "blocked on Epic D"
was no longer true (`fbc25a2` shipped the reminder worker, which is exactly the scheduled refresh it
wanted), and the epic had no answer for the app lock, which landed after it was written.

**Two things the code said that the plan didn't.** `recordReview` stamps `System.currentTimeMillis()`
rather than the answer's own time, so replaying a queued offline watch review would anchor every
interval to drain time — silent interval corruption, not a visible failure. And "today's due cards"
needs an end-of-day horizon, or the watch says "all caught up" at 09:00 for a card due at 09:05.
Both are now written into the epic; the horizon is built, the `recordReview` overload is not.

**The stack decision flipped mid-discussion, and the reason is worth keeping.** The plan was
Java-first — tile in Java, Compose later for the review screen — until a search showed
`protolayout-material3` is Kotlin-only with no Java builders, so a Java tile is a *Material 2.5*
tile, off the design system the app was migrated onto. Also corrected a claim in the epic: the
view-based Wear widgets are **not** deprecated. `:wear` is Kotlin; `:app` and `:study` stay Java.

**`:study` extracted first** — seven classes, not the six the plan listed (`QuizQuestion` had to come
too; its constructor is package-private and only `QuizGenerator` calls it). Packages deliberately
unchanged, so **zero imports in `:app` changed** and the diff is eleven renames plus build files.
32 tests pass, and the module's whole point was verified rather than assumed: adding
`import android.content.Context` to `QuizRules` now fails `:study:compileJava`.

**Phase 1 built and partly verified.** Emulator toolchain came first — no `cmdline-tools` were
installed, so `sdkmanager`/`avdmanager` had to be downloaded (SHA-1 checked against Google's
repository XML) before a Wear OS 6 arm64 AVD could exist. The tile renders its correct never-synced
state on `emulator-5556`. The phone's publish path runs to the GMS boundary and stops at
`Wearable.API is not available on this device` — the emulators aren't paired, which needs the
companion app and a Google sign-in. Everything downstream of that (the decode, every non-empty tile
state) is written but unexercised, and `note.md` says so.

**One bug introduced and caught in the same session**: the publish was first wired *after* the
reminder worker's notifications-enabled early return, which would have frozen the watch's count for
anyone who turned the daily nudge off. Moved ahead of it — the two surfaces are separate promises.

## 2026-08-14 — Pairing the emulators, and Wear phase 2 (review screen + return path)

Started as "the watch says check your connection when I import a Google account" and ended with the
phase-2 round trip working. The pairing half took most of it, and **four separate causes** stacked
up — worth recording, because each one alone produced the same useless error message.

**1. Never paired at all.** `clockwork_paired` was null, the phone emulator had no companion app,
and nothing was forwarded on 5601. The account import had no phone to talk to.

**2. `adb forward` was pointed at the wrong device.** The forward makes the *host* listen and
forward *into* the named device, so it belongs on the **phone** (which listens on 5601), not the
watch (which dials `10.0.2.2:5601`). Had it backwards first; the giveaway is a `0x15E1` listener in
`/proc/net/tcp` inside the phone.

**3. A 4.6-day clock skew** on the phone emulator with `auto_time=0`. Google auth rejects that and
surfaces it as a generic connection error. Nothing about the message suggests the clock.

**4. The 16 KB page size, which was the real blocker.** The phone AVD was built on
`google_apis_playstore_ps16k` (`getconf PAGESIZE` = 16384). The Wear OS companion app ships
`libcronet.114.0.5735.84.so` with an unaligned LOAD segment, so it cannot load on a 16 KB device —
and cronet is the companion's *network stack*, loaded lazily on the first request, which is exactly
when "import account" fails. **You cannot fix this from the APK side**: realigning and re-signing
breaks the Play signature that pairing requires. Fix is a 4 KB phone AVD
(`system-images;android-36;google_apis_playstore;arm64-v8a`).

**What actually paired them was Android Studio's Device Manager → Pair Wearable.** Manual pairing is
a dead end on this image: it auto-provisions on boot (`device_provisioned=1` straight out of
`-wipe-data`) and then disables the setup-wizard components, so `RegularPairActivityV2` reports
"Activity class does not exist" and there is no OOBE to return to. Forcing the provisioning flags to
0 and rebooting does not bring it back either. The Studio assistant does not use OOBE at all.

**A non-bug worth remembering**: Play Store "opening and immediately closing" on a fresh Play image
is `installPackageLI` — it is replacing its own APK, and Android kills the running app to do it.
Same again for GMS right after (25.08.34 → 26.30.32). Wait, don't debug.

**Phase 2 built**: the Compose review screen and the `MessageClient` return path, both of which the
epic had specified and neither of which existed. `AnswerEventKeys` in `:study` (path + card id,
correct, answered-at) mirrors `DueProjectionKeys`; a **message, not a `DataItem`**, because two
answers to one card are two facts and an item keyed by card id would lose the first. The
`recordReview(card, correct, answeredAt, onDone)` overload flagged back on 08-13 is now built and is
what the listener calls. Tile taps now launch `ReviewActivity` — the click sits on a wrapping `Box`
so it survives the layout changing.

**Compose setup, since AGP 9 made it non-obvious.** `buildFeatures.compose = true` is not enough:
Kotlin 2.0+ needs `org.jetbrains.kotlin.plugin.compose`, which *is* safe to apply here even though
`kotlin.android` is not — AGP 9 registers the Kotlin extension but not the Compose compiler. Compose
BOM is pinned to **2026.06.01, not the newest**: 2026.08.00 pins Compose 1.12, which demands
compileSdk 37, and `:wear` compiles against 36 like everything else.

**Verified on the emulators, not assumed** — seeded two cards (one due an hour ago, one due later
today) and drove the whole loop by hand:
- tile → review screen, "1 of 1" (the end-of-day horizon re-filter working: both cards ship, one
  counts)
- correct answer → repetitions 0→1, easiness 2.5→2.6, `next_review − last_reviewed_at` exactly
  86400000
- wrong answer → easiness 2.5→2.18 (the documented −0.32), repetitions reset, card returns to the
  queue
- **the repeat answer left the DB byte-identical** — `isFirstAnswer` is read before `answer()`
  mutates the queue, so practice does not feed SM-2

**Two bugs of my own, caught by looking rather than by the build.** The screen first collapsed
`null` and empty into one "all caught up", erasing the distinction `DueProjectionClient` documents —
it would have congratulated a watch that had never synced. And the answer buttons were sized in
fixed dp, which clipped "Missed" twice at two different widths; they are icon-only now (✕ / ✓, core
Material icons, label kept as the content description).

**Still not built** from Epic J: `CapabilityClient` discovery and the offline queue-and-drain (the
`recordReview` overload exists for exactly that case but nothing queues yet — an answer sent while
untethered is logged and dropped), and the tile's "Review N" edge button.

## 2026-08-14 (later) — Epic J: deck picker, the publish gap, and items 2/3/4

**A deck picker on the watch**, because "10 cards due" across four notes is a count you cannot act
on. Grouped by **`noteId` and labelled by title**, not grouped by title: two notes can share a name
and an untitled one resolves to a dated fallback that could collide, so title-grouping would show
one deck with a correct count and cards from two places. `DueCard` grew `noteId`/`noteTitle`,
resolved on the phone via `NoteDisplayUtils.resolveTitle` (the fallback needs a Context).

**The bug that cost the most to find**: `DueProjection.trimmed()` is a hand-written copy that
rebuilds each `DueCard`, and it silently dropped the two new fields — every deck collapsed into one
nameless group reading "6 due". Nothing failed to compile; the fields just arrived empty, which
reads as a data problem rather than a copy problem. Two `:study` tests now pin it, which is exactly
what that module is for. Note `noteTitle` is deliberately **not** truncated to `MAX_TEXT_CHARS`:
cutting titles would make two decks with a long shared prefix look identical in the picker.

**"Watch shows all caught up even with flashcards on the phone" was not a sync delay.**
`syncFromNote` — the method that *creates* cards — never published. New cards get
`initialise(card, now)` so they are due immediately, and the only publish triggers were
MainActivity's cold start, `recordReview`, and the daily worker. Now publishes when something
actually changed (tracked, because the method runs on every note save). `deleteForNote` had the
same gap and is worse: a watch holding a deleted deck offers cards whose ids the phone cannot find.
**Unverified on-device** — the seeded test notes have no `content_blob` and zero `note_segments`, so
`reviewableQa()` returns empty for them and they cannot exercise the path at all.

**Items done this round**:
- **Tile edge button** — `textEdgeButton` in `primaryLayout`'s `bottomSlot`, one `Clickable` shared
  with the whole-tile target so the two cannot drift. Only rendered when there is a session to
  open: "Review" under "All caught up" leads to a screen saying the same thing. **Visually
  unverified** — the tile is not in the wiped emulator's carousel and adding it needs the tile
  editor UI, which resisted automation.
- **Voice capture** — `CaptureEventKeys`, `CaptureActivity` (the only launcher entry, because a
  capture is worth opening cold and a session with nothing due is not), `CaptureSender`, and
  `WearCaptureListenerService` on the phone. It appends **through `NoteRepository`, not SQL**: the
  body is one Markdown document that also drives the asset registry, whiteboard links and the
  search index, so a service writing `content_blob` itself would get one right and three wrong.
  The inbox lives outside any collection on purpose — a collection can be locked, and a capture
  must always have somewhere to land.
- **Media controls** — **no code needed.** `setActive(true)`, `MediaStyle.setMediaSession(token)`,
  `PlaybackState` with PLAY/PAUSE/STOP/SEEK_TO, callbacks wired, and `setLocalOnly` appears nowhere
  in the app. The epic's "don't mark it local-only" was already satisfied.

**Two real bugs found by testing rather than by the compiler**: the review screen collapsed `null`
and empty into one "all caught up" (congratulating a watch that had never synced), and
`CaptureActivity` never finished after a successful send, leaving "Saved to Inbox" on the wrist
until dismissed by hand.

**Wear emulator gotchas worth not rediscovering**: the speech recogniser cannot work — Gboard
returns "Oh no! There seems to be a connection issue", so `RecognizerIntent` end-to-end is
untestable there and the phone half had to be proven with a temporary bypass (since removed).
Reading the app DB by copying only `quill.db` gives **stale results**; the journal matters, and two
"the capture did not land" conclusions were my own bad reads.

**Still open in Epic J**: `CapabilityClient` discovery and the offline queue-and-drain — an answer
or capture sent while untethered is still logged and dropped. `wear-remote-interactions` remains a
declared-but-unused dependency now that phase 2 superseded the tap-through-to-phone plan.

## 2026-08-14 (later still) — three tiles: due, dictate, read

**"Quill only has one tile"** was correct and by design — one `TileService` was declared. Now three,
because both new features were a trip through the app list otherwise. `ActionTileService` is the
shared base: both new tiles are the same object with different words, and unlike `DueTileService`
they read nothing from the Data Layer, so nothing on them can be out of date. Separate tiles rather
than buttons on the due tile, which is a glance at a number and would become a menu.

**Both flows needed a note list**, which the Data Layer did not carry. `NoteListKeys` +
`WearNoteListPublisher`, inheriting the projection's rule verbatim: **every locked collection
excluded, open or not** — a picker listing "Therapy notes" has disclosed the thing the encryption
was for without ever opening it. Most-recently-updated first, capped at 25: a watch picker is for
the note you were just working on, not a library. Published on cold start and after every save,
which is the lesson from the flashcard publish gap earlier today.

**Capture now chooses a destination.** `KEY_NOTE_ID` is optional on the wire — absent, or naming a
note that no longer exists, means the inbox. Falling back rather than failing, because the watch
picked from a list that may be minutes old and a thought in the wrong note is recoverable where one
the phone refused to store is not. The inbox row is pinned first and carries no id: it is a
destination, not a note the watch knows about. One consequence worth noting — `onNeedsUnlock` in
the capture service went from unreachable to reachable, since a chosen note's collection can be
locked in the gap between publish and capture.

**`PickerList` extracted** once the third picker appeared. The part worth sharing is not the column
but the two things easy to leave out of one: rotary focus, and enough vertical padding that the
first and last rows are not where a round screen curves away.

**A claim of mine that was wrong, and is now corrected in code**: I wrote that read-aloud controls
would arrive on the watch's media card. They do not. `ReadAloud` is in-process TTS with **no
notification and no `MediaSession`** — the bridged card belongs to `AudioPlaybackService`, which
plays recorded audio and is a different feature. So a reading started from the wrist can only be
stopped in the phone's now-playing bar. The class comment and the confirmation string now say so.
Closing that gap means giving `ReadAloud` a media notification on the phone, which would also hand
the watch transport controls for free.

**Verified**: note list reaches the watch and both pickers render it; picking a note sends
`/quill/read`, the phone's service receives it, and `TextToSpeech: Successfully bound to
com.google.android.tts` — the reading actually starts. Needed a temporary diagnostic log to see it,
since the service only logs failures; it has been removed.

**Emulator trap that cost three false conclusions**: the Wear screen dims on its own and taps then
only wake it. Twice I concluded "the message never arrived" when the pick had simply not
registered. `svc power stayon true` plus `settings put global ambient_enabled 0` is what makes the
screen stay bright enough to drive by adb.

## 2026-08-14 (later still) — Audio, properly: recorded memos, watch transport, one tile

Three complaints, all of them fair, and all of them about the pair of features the previous session
shipped.

**"It saves the transcript, not audio."** Capture used `ACTION_RECOGNIZE_SPEECH`, which was wrong
twice over: what reached the phone was a transcriber's guess rather than the saying of it, and the
recogniser ends the moment you stop making noise — so a pause to find the next word was read as
being finished ("the moment i stop speaking it automatically says Done"). The watch now records
with its own `MediaRecorder` (`MemoRecorder`) and the audio is what lands in the note. AAC/mono/
22.05kHz/32kbps, because every recording crosses the Bluetooth link; five-minute ceiling, because
what that catches is a screen left on in a sleeve.

**The transport is a `DataItem` with an `Asset`, not a message** — the one deliberate reversal of
the old capture's reasoning. A message is capped at 100KB and is *dropped* when no node is
connected, which for a capture is the thought gone. An item sits in the store until the phone next
appears. So "Saved" on the watch means stored, not delivered, and the string says so. Each memo
takes a fresh path (`/quill/audio-capture/<uuid>`) so a second cannot overwrite a first; the phone
deletes each once filed, and *only* once filed — a failure leaves it to be re-offered next sync.
Batches are sorted by `KEY_CAPTURED_AT` before filing, which is the only case where the buffer's
order means nothing and the order they were spoken in means everything.

**"I should be able to pause from the watch."** The gap I documented last session — `ReadAloud` has
no `MediaSession`, so a reading started from the wrist could only be stopped in the phone's
now-playing bar — is closed, though not the way I predicted. Rather than giving `ReadAloud` a media
notification, there are now two contracts: `ReadControlKeys` (a message: toggle, stop) and
`ReadStateKeys` (a `DataItem`: active, playing, title, progress). A toggle rather than explicit
pause/resume, because both ends can disagree about what is happening and a toggle lands correctly
either way. `WearReadStatePublisher` rides `ReadAloud`'s own listener and is attached from
`MainActivity` *and* from the two Wear services — the interesting case is precisely the one where
the phone app was never opened. Progress-only changes are throttled to 5%; anything that changes
what the buttons *say* publishes immediately.

`ReadAloudActivity` now has two lives: a picker when nothing is being read, the controls when
something is — including a reading started on the phone. The buttons act optimistically and are
corrected by the next publish, because a Bluetooth round trip is a visible pause on a control that
should feel instant. `awaitingStart` exists because without it the screen closed itself on open:
the state item still held the *previous* reading's ending, which arrives long before the new one's
beginning.

**One tile, not two.** The old argument — a tile is a glance with one thing on it — was true of the
due tile and not of these: neither had anything to show, both were a word and a door, and two doors
is what a button group is for. `ActionTileService` and its two subclasses are gone;
`AudioTileService` replaces them.

**Mid-session feedback, applied**: the tile's two buttons are stacked, not side by side (a button
group gives each half about a third of the screen, narrow enough that "Record" fills it and says
nothing); the transport is icons — pause/play in primary, stop in `errorContainer`, because stop is
the only control here that cannot be undone and it sits a thumb's width from the one you press
repeatedly; and `PickerList` rows are `filledTonalButtonColors` surfaces instead of bare white text.
That last one was a real bug rather than a preference — on a list of two-line titles the only thing
separating rows was a gap the same size as the gap *inside* a wrapped title. Side margins went 8dp
→ 20dp at the same time: text can run to the curve and stay readable, a filled row gets its corners
sliced off by the bezel and looks like a rendering fault.

**Icons are this module's own vectors** (`ic_play`, `ic_pause`, `ic_stop`, `ic_mic` under
`wear/res/drawable`). `material-icons-core` has a play triangle and neither of the other two, and
pulling the extended set onto a watch to draw a square and two bars is not a trade worth making.

**Verified end to end on the emulators** (5554 phone, 5556 watch): recorded from the watch → 99KB
`.m4a` in the phone's `files/audio` and an `![audio](quill://audio/…)` embed appended to the Inbox
note; started a reading, force-stopped the watch app, reopened it and landed straight on the
controls; pause, resume and stop all took effect on a phone whose app process had been force-stopped
first — which is exactly the scenario the complaint was about. The tile renders and both rows launch.

**Two things worth knowing for next time.** Driving the Wear *tile carousel* by adb is miserable:
the tile editor auto-dismisses after a few seconds, so long-press → tap "+" → rotary-scroll → tap
has to be one unbroken chain with no screenshots between, and `input swipe` inside the "Add new"
list dismisses it rather than scrolling — only `input rotaryencoder scroll --axis SCROLL,-4` moves
it. And the notes on this emulator are all a sentence long, so testing a *pausable* reading meant
temporarily swapping a long body into `n-geo` via `run-as cp` and restoring it after; the
`.db-journal` has to be removed alongside, and the app force-stopped first.

## 2026-08-14 (same session) — Locked collections against the new Wear paths, and clearer pickers

**The question that found two bugs**: what do the new audio features do about locked collections?
The existing answer covers most of it — `WearNoteListPublisher` excludes every encrypted
collection, open or shut, so neither picker ever offers one of its notes, and the projection's
exclusion happens in SQL before a `DueCard` exists. But the two new paths had holes.

**Mine, and the worse one: a memo aimed at a note that got locked in the gap.** The picker cannot
offer such a note, but a memo now *waits in the Data Layer for a phone to appear*, so the gap that
used to be minutes can be hours. `saveNote` refuses with `onNeedsUnlock`; my code counted that as a
failure, which kept the `DataItem` and re-offered the recording on every sync forever — while a
comment two lines above claimed the opposite. It now falls back to the inbox, which is outside every
collection and is the reason `inboxNoteIdSync` refuses to live in one. Retrying is pointless (the
lock will not lift because we asked again) and dropping is worse (no other copy exists), so a third
`Outcome` — `LOCKED`, distinct from `FAILED` — is what carries the decision back.

**A leak I introduced with the read state.** `WearReadStatePublisher` published
`ReadAloud.title()`, and a reading of a private note can be started from the phone's own editor
while the collection is open. The `DataItem` outlives the unlock, so "Therapy notes" would have sat
on the wrist long after the collection shut — a straight violation of the rule
`WearNoteListPublisher` states: the question is not "should this appear on screen" but "should this
leave the device". The publisher now resolves the title on `AppExecutors.diskIO` (single-threaded,
so ordering survives), checks the note's collection with `NoteCrypto.isLocked`, and substitutes
"A private note". Only the *name* is withheld — the controls still work, because a reading the user
deliberately started must be stoppable from the wrist. `ReadAloud.noteId()` was added for this.

**Still true and still out of scope**: media files themselves are plaintext on disk regardless of
the collection's lock — `note_segments.file_path` is unencrypted and always was. A memo recorded on
the watch inherits that, it does not worsen it.

**Pickers now say what a tap does.** `PickerList` grew a `header` (inside the scroll, not pinned —
a watch has no room for permanent furniture) and an `enabled` predicate. "Select a note to record
into", "Select a note to read aloud", "Select a deck to review".

**And the review screen stopped being a dead end.** "All caught up" was doing two jobs and getting
one wrong: on arrival it reads as praise for having done nothing. Now `review_all_done` after a
session, `review_nothing_due` on arrival, and — the useful part — decks with nothing due *yet* are
listed greyed with "Due in 40 min". That data was always on the watch: the projection ships
everything due before local midnight so a card coming due at 17:00 is on the wrist early, and
`dueAt(now)` held it back with no way to say so. `DueSnapshot.upcoming(now)` exposes it; a deck
qualifies only if *none* of its cards is due, since one with two due and five later is a deck you
can review now. Disabled rather than omitted because there is still no "review anyway" on the wrist.

**Verified**: due + upcoming mixed list, the all-upcoming header, and the genuinely-empty message,
each by temporarily rewriting `flashcards.next_review` and re-launching the phone app to republish.
Restored afterwards.

**Emulator note**: the paired watch AVD is `Wear_OS_Large_Round` (454px), not `Quill_Wear` (384px).
Starting the wrong one gives a watch with no `DataItem`s at all, which looks exactly like a sync
failure.

## 2026-08-14 (same session) — Wear sizing, and "New note" from the wrist

**Everything on the watch got a size down.** Type a step (`bodySmall` for row labels, `labelSmall`
for headers and secondary lines), row content padding to 2dp, 4dp between rows, outer padding
40dp → 26dp, transport buttons 56dp → 48dp with 22dp glyphs.

**What did not move, and why**: the row height. Wear's `Button` floors at 52dp and the rows were
already sitting on it — the perceived bulk was type, padding and the 40dp band above the first row,
not the button. The floor below 52 is 48 (`CompactButton`'s), at which point a note title has to
give up its second line. On a device operated by a fingertip belonging to someone walking, that is
the trade to make deliberately or not at all; the transport buttons went to 48 because they are
single glyphs with nothing to wrap.

**A round-screen gotcha**: at 16dp side padding the header clipped to "elect a note to record int".
A filled row is a rounded rectangle whose corners the bezel may graze, but a line of text near the
top of a circle has to fit inside a much shorter chord. The header now carries 22dp of its own
horizontal inset on top of the list's.

**"New note" on the capture picker.** `PickerList` grew a `leadingContent` slot — a slot rather
than another item because the difference is the whole point: every row is a place to put something,
this makes one. Filled primary with an `Icons.Filled.Add`, against tonal rows, so it does not read
as a note somebody already called "New note".

**The phone names it, not the watch.** `AudioCaptureKeys.KEY_NEW_NOTE` is a flag, not a sentinel id
(a sentinel that ever collided with a real id would file a thought into a stranger's note), and the
note is created with an **empty title** — the app's actual convention for untitled, with
`NoteDisplayUtils.resolveTitle` producing "Untitled Note - Aug 14, 2026" in every list. Storing a
literal name like "Voice note" would have made a note that renames itself the moment a title is
typed, and one that sorts and searches unlike every other untitled note. `createNote` is async but
ordered — it and the load inside `appendTo` share the repository's single disk thread — so no latch
is needed around it.

**Three destinations is a type now**, not a nullable id: `CaptureTarget.Inbox` / `NewNote` /
`Existing`. Null used to mean the inbox on its own; with a new-note flag beside it, null would have
meant one of two things depending on a neighbouring field.

**Verified**: tapped New note on the watch, recorded, and the phone created an untitled note
carrying the memo — "Untitled Note - Aug 14, 2026" in the notes list, opening to a 0:34 audio player
with its waveform.

**adb gotcha worth remembering**: `am start` on an activity of a package that already has a
different activity on top prints "intent has been delivered to currently running top-most instance"
and does nothing. Every scripted screen change needs a `force-stop` first, or the taps that follow
land on the previous screen — which is how a capture test ended up part-way through a review session.

## 2026-08-14 (same session) — The tile buttons were the actual problem

**`weight(1f)` was the bug, not the padding.** The audio tile's two rows divided the entire main
slot between them, so on a 454px watch each button was around 90dp tall — a third of the screen
apiece. Fixed at 48dp with `Typography.LABEL_MEDIUM`, and the column no longer expands (an
expanding column around fixed-height children only pushes them apart again). The empty band left
underneath is quieter than two slabs; a tile is glanced at and tapped once.

**The twenty-character ceiling turned out to be a type choice, not a limit.** The old comment said
the main slot "truncates past roughly twenty characters at 384px", which is true of the *display*
face it defaults to and had quietly capped what the tile was allowed to say — hence "Open on phone"
and a bare "All caught up". Now the slot picks its type from its content: a count keeps the display
face, a sentence drops to `BODY_MEDIUM` with `maxLines = 4`. So "All caught up. No pending
flashcards for now" wraps to two lines with room to spare, and `tile_no_phone` got its full
sentence back ("Open Quill on your phone to sync") in the same change.

**Renamed**: `tile_label` "Quill review" → **"Quill Flashcards"**. It labels both the tile and
`ReviewActivity`, so the name in the tile editor and the one in recents now agree.

**Verified** on the watch: audio tile at the new size, the caught-up sentence wrapping without
truncation and with no edge button under it, and "Quill Flashcards" across the top of the tile
editor. Card schedules restored afterwards.

## 2026-08-14 (same session) — The list was right and late, and the inbox stopped being a place

**"The new note isn't there" was a sync latency, not an ordering bug.** `WearNoteListPublisher`
already ordered `updated_at DESC`; the note was correct, published, and simply had not arrived.
The put was deliberately **not** urgent, on a comment that read: "nobody is looking at a number
that is currently wrong. The list only has to be right by the next time a picker opens." That was
true when the only thing that changed a note was the phone. It stopped being true the moment the
watch could create one — the user's next act after recording is to open the picker and look for
what they just made. `setUrgent()`, and the Data Layer stops batching it for minutes.

Measured after the change: recorded into "Geography", which was fifth in the list, and it was
**first within 12 seconds** of the recording stopping. That round trip is four hops — watch puts the
memo (urgent), phone pulls the asset, phone saves and republishes (now urgent), watch reads — so
seconds is about the floor.

**The inbox is no longer a destination the watch offers.** It was pinned to the top of the capture
picker as a special row, which meant any watch that had ever used it listed "Inbox" twice: once as
the sentinel and once as the ordinary note it actually is. The picker is now "New note" plus your
notes, newest first.

The inbox still exists on the phone, demoted from a choice to a **recovery**: it is where a memo
lands when the note it named has been deleted, or locked, since the list was published. That is
still the right answer — better than dropping the only copy — but it is the phone's business now,
so `CaptureTarget` lost its `Inbox` case and the sentinel empty-string id is gone. Nothing on the
wire changed: a memo with no `KEY_NOTE_ID` and no `KEY_NEW_NOTE` still means the inbox, that state
just no longer originates from a tap.

**Emulator state left behind** (all of it test output, all of it deletable in the app): three
"Untitled Note - Aug 14, 2026" notes from the New-note tests, an audio memo appended to the real
"Geography" note from the ordering test, and the older one in "Inbox". Five files in `files/audio`.
Not unpicked by hand — reverting a note body by DB edit would strand its media row and file, which
is a messier state than the clutter.

## 2026-08-14 (same session) — Tile previews, and an emulator dead end

**Both tiles previewed as `@mipmap/ic_launcher`.** In a picker where every other entry shows its own
contents — Favorites shows faces, Contact shows a contact — an app icon is the one card that
answers "whose tile is this" instead of "what is on it". The two tiles now preview as renders of
themselves: screenshots of the live tiles, scaled to 320px, in `drawable-nodpi` (a preview is one
fixed image, not an asset to scale per density). ~13KB and ~19KB.

**Verified in the built APK rather than on screen**, and that was not the plan. `aapt2 dump
resources` confirms both PNGs packaged at `res/drawable-nodpi-v4/`, and `aapt2 dump xmltree`
confirms `DueTileService` → `tile_preview_flashcards` and `AudioTileService` → `tile_preview_audio`.

**What went wrong**: Wear's "Add new" list hides tiles already in the carousel, so to see a preview
I uninstalled the watch app — which emptied the carousel — and then could not re-add the tiles by
scripted input. The carousel navigation on this emulator is not reliably drivable: the same
`input swipe` reaches the tiles one minute and bounces off the watch face the next, the editor
auto-dismisses within a few seconds, and `input rotaryencoder scroll` pulled down the notification
shade instead of scrolling the picker. The phone-side route is closed too — the Wear OS companion
app crashes on launch on this phone emulator ("Google Pixel Watch keeps stopping").

**So the watch emulator's carousel is empty** and needs a few taps by hand: from the watch face,
swipe left-to-right twice to "Add a widget", long-press, "+", then pick Quill Flashcards and Quill
audio. That is also the screen where the new previews appear. The app itself is installed and fine —
every activity still launches, and the real SM-R860 was never touched.

**Worth remembering**: verifying anything that lives in the Wear system UI (tile picker, carousel,
watch-face editor) by adb is a poor bet. Verify the *wiring* in the APK with aapt2 and leave the
system UI to a human.

## 2026-08-15 — A deleted note stayed on the wrist: the publish that was never there

**`WearNoteListPublisher.publishSync` had exactly two callers**: `MainActivity.onCreate` and
`NoteRepository.saveNote`. So the watch's list was rebuilt when a note was *written* and at no other
time. Deleting one left it on the wrist — offered, tappable, and gone by the time a memo aimed at it
reached the phone, which filed it in the inbox instead. That is what the user hit.

Three more callers were missing for the same reason, and one of them is not cosmetic:

- **`deleteNote`** — the reported bug.
- **`assignCollection`** — a note moved into an encrypted collection has to leave the wrist; moved
  out, it may return.
- **`CollectionLockRepository.lock` / `unlock` / `discardUnreadable`** — **locking a collection did
  not withdraw its titles from the watch.** The encryption was applied everywhere except the one
  surface that had already carried the names off the phone, and they stayed there until something
  unrelated happened to republish. Same rule as `WearNoteListPublisher` states: the question is not
  "should this appear on screen" but "should this leave the device".

**And the refresh the user asked for, as belt to those braces.** No push mechanism can promise
delivery, and the watch cannot tell a current list from a stale one by looking — both are the same
`DataItem` with a different number inside. So `NoteListKeys.REFRESH_PATH` is a message the watch
sends as a picker opens, `WearNoteListRefreshListenerService` republishes, and the answer arrives on
the ordinary path.

**Draw first, ask second** (`rememberSyncedNoteList`). The cached list appears immediately, because
it is nearly always right and a picker you watch load is a worse picker. Rows are replaced only if
they genuinely differ — `WatchNoteList.generatedAt` plus a content comparison — since swapping
identical rows under a thumb is a way to lose a tap, and the Data Layer redelivers on reconnect. The
spinner sits in a **fixed-height slot** for the same reason: letting it appear and vanish would move
every row while someone is reaching for one, which is the exact failure the mechanism exists to
prevent. A six-second ceiling stops it spinning forever for a watch with no phone.

**Verified, and partly by the user.** They deleted "Geography" on the phone mid-session to test it,
and the watch's picker dropped it with nothing else touched. My own run deleted an untitled note and
the watch matched the phone's live rows exactly eleven seconds later. What I could *not* catch on
camera was the spinner itself — the round trip finishes faster than `screencap` can cycle. It is the
same indicator already proven on the initial-load path.

**A red herring worth writing down**: `W NoteRepository: notes_fts unavailable, skipping index
update` with a full SQLite stack trace appears on every save and delete on this emulator. It is
pre-existing and deliberate — the build has no FTS5 and both index helpers tolerate its absence —
not a symptom of anything added here.

## 2026-08-15 (later) — Six small fixes, one of which was a process death

**Deleting a whiteboard killed the app, and the reported cause was the smaller half.** Three tables
carry a foreign key onto `whiteboards.id` — `strokes`, `whiteboard_texts` and `note_whiteboards` —
and `deleteWhiteboard` cleared only the first. So the crash fires for a board embedded in any note
*and* for one that merely has a text box on it. `SQLiteConstraintException` on the disk thread is
not a failed delete, it is a dead process: nothing catches it. Reproduced by seeding a board and an
embed straight into the DB, which was far quicker than drawing one.

**A second, latent one found while fixing it.** The embed line stays in the note after the board
goes (deliberately — rewriting somebody's note because they deleted a drawing is a larger liberty
than leaving a marker). But `WhiteboardLinks.replace` re-inserts a link row from that line on the
next save, and **`ON CONFLICT` does not apply to foreign keys in SQLite**, so `CONFLICT_IGNORE` was
never going to save it. The insert is now `INSERT OR IGNORE … SELECT … WHERE EXISTS`, which covers
both the duplicate-embed case (a primary key, which the conflict clause does handle) and the dead
board. Symptom would have been the app dying on saving a note you had only opened.

**The other five**, all small:

- **Collections rename from the top bar**, like notes: `toolbar_title` is an `EditText` now,
  committed on IME Done, focus loss and `onPause`. Empty reverts rather than saving — a nameless
  collection is unreachable in every list that shows one.
- **"Nothing to undo" stacked** because Android *queues* toasts. Held in a field and cancelled
  before each show, so repeating the tap restarts one message instead of lining up five.
- **The gap above the search bar** was `content_sheet_min_height` at 56dp, twice the 28dp corner
  radius it exists to keep visible. Now exactly the radius.
- **Default whiteboard paper is dots.** Only the *fallback* moved — anyone who has picked a paper
  keeps it, and existing boards keep theirs.
- **Pinned-band jitter**: the band was built from a database read, so it appeared a frame or two
  late and shoved the page down. Home now draws placeholder cards from a remembered count before
  the read returns. Placeholders rather than a spinner precisely because a spinner is a different
  height from what replaces it — it would jump too. The count only has to be close: every card is
  a fixed height, so the band's height is right even when the number is briefly wrong.

**One thing fixed in passing**: the "whiteboard was deleted" placeholder rendered its 24dp glyph at
`CENTER_CROP`, blowing it up until it ran off both edges. `CENTER` while it is the fallback;
`loadPreview` still switches to `CENTER_CROP` for a real thumbnail.

**Verified on the emulator**: crash gone (delete completes, process alive, note opens and saves),
the danger-coloured "embedded in 1 note" warning, rename round-tripping to Home and the DB, dots on
a new board, the tighter sheet, and the pinned band already at full height on the first frame back
from a note. Undo confirmed by the user. Test artifacts (seeded board, renamed collection, pinned
note, scratch whiteboard) cleaned out of the emulator DB afterwards.

## 2026-08-14 — Feature implementation: home-screen App Widgets

**Asked:** widget ideas, narrowed to two — a widget for collections/important notes, and one for
whiteboards — then clarified as genuine Android launcher widgets ("like a weather widget"), not
new in-app screens.

**Built:** `CollectionsWidgetProvider` (stacked pinned-notes + collections lists) and
`WhiteboardsWidgetProvider` (thumbnail grid of recent boards), both under `mse.quill.widget`.
Full design and the reasoning behind each piece — the two-`RemoteViewsService` requirement, the
new `WidgetThumbnailCache` bridging `WhiteboardThumbnails`' async-only rendering, the
`WidgetUpdater` push-refresh hooks — now live in [note.md](note.md)'s "Home-screen App Widgets"
section; the checklist is Epic K in [requirements.md](requirements.md) (not originally scoped in
the one-pager, built opportunistically).

**No `adb` in this environment.** Every bug in this feature was reported by the user from a real
device, by symptom only ("doesn't load", "just opens the app"), and diagnosed from code review
rather than logcat. Three real bugs, each confirmed by the user after a code-review fix:

1. **"Problem loading widget"** on the collections widget — a plain `<View>` divider isn't in
   RemoteViews' inflatable-class allowlist. Swapped for a 1dp `TextView`.
2. **Tapping a whiteboard/note/collection opened the app but landed on whatever screen was last
   open**, not the tapped item. Two stacked causes, found one after the other: first,
   `runWhenNavHostReady` waited for `HomeFragment` to resume but nothing was driving the nav host
   back to Home (fixed by forcing `popBackStack(R.id.homeFragment, false)`, matching
   `handleViewIntent`'s existing pattern) — this alone didn't fix it. Second (found after the user
   reported the bug *persisted*): the collection-widget `PendingIntent` templates were
   `FLAG_IMMUTABLE`, which silently blocks the template+`fillInIntent` merge that hands each row's
   tapped-item extras to `MainActivity` — needed `FLAG_MUTABLE` instead.
3. Also fixed on request, unrelated to the widgets: the in-app pinned-notes strip
   (`PinnedNoteCardView`) felt oversized — shrunk `pinned_card_width`/`_height` (164×148dp →
   140×108dp), tighter padding, 1-line title.

**Process note worth keeping**: when the user reports "issue 1 solved, issue 2 still persists"
after a fix was applied, that's a signal there may be a *second*, independent cause behind the
same symptom — don't assume the first fix was wrong, look for what else could produce the same
observed behavior. That's exactly what happened with bug 2 above.

**Verified working end-to-end by the user on-device**, including the pinned-card resize. Not
committed as of end of session — check `git status` before assuming this has landed.

## 2026-08-15 — Feature implementation: widget previews, third widget for flashcards

**Asked:** add real widget-picker previews to the two existing widgets, and build a third
widget for flashcards "in a list way." Clarified before building: the flashcards widget should
show **both** a due-now card list and a deck list stacked (mirroring how the Collections widget
stacks pinned notes + collections), and due cards should show **front text only** — revealing
the answer defeats active recall.

**Previews:** `android:previewLayout` (API 31+, renders the actual widget layout in the picker)
added to all three widgets' `res/xml/widget_*_info.xml`, `previewImage="@mipmap/ic_launcher"`
kept as the fallback below 31 since there's no screenshot asset in the repo to use instead.

**Built:** `FlashcardsWidgetProvider` + `DueCardsRemoteViewsService` +
`FlashcardDecksRemoteViewsService`, following the Collections widget's established shape
exactly (two `ListView`s, two services, one mutable `PendingIntent` template, a `TextView`
divider not a `View`). `FlashcardRepository` gained `loadDecksSync()` (sync twin of the
existing `loadDecks`) and a new `loadDueCardsSync(now, limit)` — there was no prior query
returning individual due cards, only `countDueSync`'s counts, so this is genuinely new query
logic, not an extraction. Both follow `countDueSync`'s locked-collection exclusion pattern.
Tap deep-links via a new `EXTRA_OPEN_FLASHCARD_NOTE_ID`, kept deliberately separate from the
existing `EXTRA_OPEN_NOTE_ID` (opens the note *editor*) since this one opens the flashcard
*review* screen for the same note id — same key name would have collided semantically even
though the extra names differ.

**Follow-up tweak:** due-now vs. decks was initially a fixed-96dp block + weighted remainder
(matching the Collections widget's pinned-notes reasoning — a short, capped list next to a
scrollable one). Asked to change to a 60/40 proportional split instead; swapped both sections
to `layout_weight` (3 and 2) rather than fixed-height + weight-1.

Full design detail lives in [note.md](note.md)'s "Home-screen App Widgets" section (updated
this session); checklist is Epic K in [requirements.md](requirements.md) (also updated).

**Verified:** clean `assembleDebug`, `compileDebugAndroidTestJavaWithJavac`, and
`testDebugUnitTest` all pass — same no-`adb`-in-this-environment constraint as the first two
widgets, so on-device confirmation (previews rendering, due/deck taps landing on the review
screen, the 60/40 split reading right) is the user's to do, not verified here.

**Not committed as of end of session** — check `git status` before assuming any of the widget
work (this session's or 2026-08-14's) has landed.

## 2026-08-16 — Bug fix: widgets vs. collection locking, a four-bug chain

**Reported, in stages, as the repro was narrowed down:** pin a note in a collection, add the
widget, lock the collection — the collection correctly vanished from the widget, but (1) its
pinned note stayed visible and tapping it silently deleted the real note and opened what looked
like a new one, (2) creating a new note while inside a locked collection crashed the app, and
crucially — the app then kept crashing on **every subsequent launch**, widget untouched. Full
technical writeup in [note.md](note.md)'s "Widgets vs. collection locking" subsection; this
entry is the narrative of how it was chased down without `adb` access for most of the session.

**Bug 1, applied defensively but wasn't the actual cause:** none of the six widget
`RemoteViewsFactory` implementations caught exceptions. Those run on a binder thread *inside the
app's own process* — unlike almost anywhere else in Android widget code, an uncaught exception
there crashes the whole app, not just the widget. Added `RuntimeException` guards to all six.
Crash persisted — which was itself useful information: it ruled out the widget rendering path.

**Bug 2, the actual "kept crashing on plain app open" cause:** `MainActivity.onCreate` calls
three Wear OS publishers unconditionally on every launch (`WearProjectionPublisher`,
`WearNoteListPublisher`, `WearReadStatePublisher` — merged in from the parallel watch-companion
branch). Both `publishSync` methods only wrapped their *final* network call in a try/catch; the
DB query and title-resolution code above it, which runs every time regardless of the widget,
was unguarded. Widened both to wrap the whole method, plus the DB-touching lambda in the read
state publisher. This is what actually stopped the repeated crash-on-launch.

**Bugs 3 and 4, found by reading code once the crash was contained, not from a stack trace:**
traced `autoSave()`'s delete-on-empty branch and `NoteRepository.createNote()`'s plaintext write
by hand. `createNote` never encrypted a new note's title even for a locked collection target —
only the *next* `saveNote` call did — so the immediate follow-up autosave's "did this change"
check tried to Base64-decode plaintext as ciphertext and threw an uncaught
`IllegalArgumentException` (`NoteCrypto`'s `decryptTitleOrNull`/`decryptBodyOrNull` only caught
the checked `GeneralSecurityException`, despite both claiming "never throw, return null" as
their whole contract). Separately, `NoteEditorFragment` couldn't tell "note doesn't exist" from
"note exists but its collection is locked" — both came back `null` from `loadNote`, and either
way the fragment marked itself ready to autosave, so a real encrypted note reachable only
because its `note == null` case was mishandled got deleted by the same "empty note, clean it
up" logic that's supposed to only ever fire on genuinely blank notes.

**Bug 5, reported last, after everything above stopped crashing:** the pinned note *still*
stayed visible in the widget after locking (no crash this time, just wrong). Root cause: the
collections list already excluded a `biometricLocked` collection unconditionally, but the
pinned-notes list relied on `NoteRepository.loadPinnedNotesSync`'s normal in-app hiding rule,
which only hides a locked collection if it isn't unlocked *this session* — and `lock()` itself
calls `CollectionLock.markUnlocked` right after locking, by design, so the app doesn't
immediately re-prompt. That's correct for the app (you just authenticated), wrong for a widget
(no session of its own). Fixed by having `PinnedNotesRemoteViewsService` cross-check each note's
collection against the `biometricLocked` flag directly, same as the collections list already
did — a pattern `FlashcardRepository.dueProjectionSync` (the Wear watch projection) had already
gotten right independently, worth checking against next time before re-deriving it.

**Process note, reinforcing the one from 2026-08-14:** "still crashing" after a fix (bug 1 →
still crashed) again meant a second independent cause, not a wrong fix — same lesson, worth
actually internalizing this time. Also: the two most serious bugs here (3 and 4) were real,
previously-undiscovered issues in `NoteRepository`/`NoteEditorFragment` unrelated to the widget
feature itself — the widget was just the first code path to reach a locked-collection note
through a route the in-app screens don't normally allow (a direct id-based deep link, bypassing
the authenticate-before-entering-a-locked-collection flow every other entry point goes through).
Worth remembering if another deep-link-style entry point gets added later: it needs the same
"does this note/collection actually resolve" check before navigating, not after.

**Verified:** clean builds throughout; each fix confirmed by the user on-device in turn, ending
with full end-to-end confirmation ("done") after bug 5's fix. Landed in commit `95859d5`
("Fixing bugs for widget") except for the bug 5 fix (`PinnedNotesRemoteViewsService`), which was
still uncommitted as of this entry — check `git status` before assuming otherwise.

## 2026-08-16 — Read-aloud plays the note's recordings too

**The ask**: reading a note aloud should play its voice recordings as well. They were being
skipped — the one part of a note that is *already* someone talking was the part listening to it
left out.

**The shape of the fix**: read-aloud stopped taking a string. `ReadPlaylist` is the note as the
voice hears it — text runs and recordings, in document order, with consecutive text (separate
segments, and both halves of a Q&A) merged so a recording is the only thing that breaks the
reading into pieces. Built two ways from one assembler: `NoteEditorView.buildReadPlaylist()` walks
the live views (it is asked on every keystroke, so it must not copy spannables), and
`ReadPlaylist.fromSegments` covers the watch's "read note N" path, which has models and no views.
`getPlainText()` and `NoteDocument.toMarkdown → toPlainText` are both gone from that path.

`ReadAloud` is now a sequencer over two engines rather than a wrapper around one: `NoteReader` for
words, a new `ClipReader` for recordings. It owns `active`/`paused` itself, because neither engine
can answer "is a reading going" between items or while a clip plays.

**Why `ClipReader` and not `AudioPlayback`**: `AudioPlayback` *is* the answer to "the user is
playing a recording" — it owns the bar's waveform, the foreground service, the lock-screen card,
and the note's audio cards draw from it. Routing a reading's clip through it would have the bar
flip identity mid-note and its ✕ end something other than what it appears to. Cost of the split:
the note's own audio card doesn't animate while the reading plays that clip. The bar's progress
covers the whole reading, which is the thing being controlled. Audio-focus bookkeeping was pulled
out to `AudioFocus` so both players share it instead of duplicating the version split.

**Progress is weighted, not counted**: items are worth roughly how long they take (a clip's real
duration; text at ~15 chars/sec, an estimate never shown as a time). Counting items equally would
make a two-minute recording worth the same as the word before it, and the bar would jump.

**Falls out of it**: a note that is *only* a recording is now readable — the menu item used to be
hidden for one, since there was no text. Watch-started readings get the recordings too, for free,
because the watch sends an id and the phone builds the playlist.

**Verified on the emulator** (not the phone), on the seeded "Inbox" note (two lines of text + a
0:22 memo): TTS synthesis, then `ClipReader` taking audio focus ~2s later, then focus abandoned 23s
after that — text, recording, end, in order. Pause mid-recording abandons focus and the bar shows
▶; resume re-requests it and carries on. Bar reads "Reading: Inbox" throughout, progress ~30% six
seconds into the clip, which is the weighting working. An audio-only untitled note now offers
"Play aloud" and plays with no synthesis at all. Six unit tests cover playlist assembly.

**Known limit, unchanged from before**: a reading still has no foreground service or notification,
so it is the process's to lose if Android reclaims it — TTS survives that better than a
`MediaPlayer` does. Wiring readings into `AudioPlaybackService` is its own piece of work.

## 2026-08-16 (same session) — The startup crash was a missing table alias

Reported as "crashes as soon as I open", on both the emulator and the phone. Not the read-aloud
work: `WearNoteListPublisher.publishSync` builds its query as `SELECT … FROM notes` and then pastes
in `NoteCrypto.excludeCollectionsClause`, which qualifies its columns as `n.collection_id` — every
*other* caller of that clause (and of `hiddenClause`) has the notes table aliased `n`, this one
never did. Result: `no such column: n.collection_id`, on the background executor MainActivity kicks
off in `onCreate`, so the process died about two seconds into every launch and kept dying.

It only bites once a collection is locked, because the clause is the empty string until then —
which is why it lay dormant. What woke it was the connected-test run reseeding the emulator DB with
a locked "Private" collection; the phone must have had one too. Fix is `FROM notes n` with the
three selected columns qualified to match.

**Verified**: relaunched with the same seeded DB — no crash buffer entries, the same PID alive
across 15 seconds (it was cycling 5282 → 5755 → 5841 before), and the app now reaches its own
biometric "Unlock Quill" prompt. The black screenshots are `FLAG_SECURE`, not a broken screen.

## 2026-08-20 — The widgets were late, and a locked collection could still be on the home screen

**Two complaints, one root**: widgets didn't update when a note was added or a collection's lock was
turned on or off, and locked material could still be showing. `updatePeriodMillis` is 0 for all
three widgets, so a change that isn't pushed is a change that never arrives — "not quick enough"
was, in most of these paths, "not ever".

**What was missing.** `NoteRepository` pushed a refresh only from `pinNote`/`unpinNote`. Creating,
saving, deleting and moving a note all changed something a widget row shows — a collection's note
count, a pinned note's title and preview, whether a pinned note is still allowed to appear — and
said nothing. `CollectionLockRepository` told the collections and flashcards widgets about a lock
but never the whiteboards one. `WhiteboardThumbnails` wrote a fresh thumbnail to
`WidgetThumbnailCache` and left the widget showing the old picture.

**The leak was the more interesting half.** Three of the five factories asked
`hiddenCollectionIds` — locked *and* not opened this session — which is the right question for an
in-app screen and the wrong one for a widget. A widget has no session and no way to ask for one, so
its contents must be a function of the database alone. Locking a collection deliberately leaves it
open in-session (the user just authenticated), so under the old rule a locked collection's
whiteboards, decks and due cards stayed on the home screen until the app was next backgrounded —
and the boards are the sharp case, because the lock migration encrypts note text and nothing else:
the query is the whole of what keeps a private drawing off the launcher. Each of those queries now
has a `*ForWidgetSync` sibling that excludes every collection encrypted at rest, the same stricter
rule `dueProjectionSync` already used for the watch, and for the same reason. The pinned-notes
factory was already doing this correctly but in Java, after the fact — it now happens in SQL, so an
open collection's titles aren't decrypted on the way to being thrown away.

**One backstop**: `MainActivity.onStop` refreshes all three. Leaving the app is the moment the home
screen is about to be looked at, and it is where `CollectionLock.relockAll()` already runs. It is a
net, not the mechanism — every repository still pushes its own change.

**Verified on the emulator**, against the seeded DB (locked "Private", open "Coursework"): both
widgets dragged onto the launcher show only Coursework and only the boards outside the locked
collection — "Private sketch" is gone, the unowned "Scratch pad" stays. A temporary probe in two
factories (removed afterwards) showed both requerying ~1.5s after the app was backgrounded, which
is the push path end to end. The in-app half of the loop — add a note, watch the count move — could
not be driven here: the emulator's app lock wants a PIN, and disabling it was refused. The four
widget queries were also run, old rule against new, over a copy of the emulator's own database:
each strict variant drops exactly the locked collection's rows and nothing else.

## 2026-08-20 (same session) — Deleting a note: the one delete that pushed nothing

**The report**, after the above landed: in the widget, a deleted pinned note stays, but deleting a
collection works fine. That asymmetry is the diagnosis. Both lists live in one widget and are
refreshed by the same call, so the collections half updating proves the push reaches the launcher —
what differed was the sender. `deleteCollection` always pushed; `deleteNote` never did, and it is
the single place that sets `deleted_at`, so all three delete surfaces (Home's note dialog, the
pinned card's dialog, the editor) were silent. Also added: a delete now tells the flashcards widget,
since a trashed note's cards stay in the table and drop out of the deck and due-now queries by
their note's `deleted_at` alone.

**Verified end to end on the emulator**, with the app lock's PIN (1234, from the user): pin a note →
it appears in the widget; delete it → two seconds later the row is gone and the collection reads
"0 notes". Then the other half of the original complaint: a note created inside Coursework took the
count to "2 notes" without leaving the app for anything.

**How to drive the app despite `FLAG_SECURE`**: screenshots of Quill are black, but
`uiautomator dump` gives the full tree, text and bounds included — and the launcher isn't secure, so
the *widget's* contents can be read straight out of the launcher's own accessibility tree. That is a
better check than a screenshot anyway: it reads the strings the widget is actually showing.

**Cleanup**: the deleted note had nowhere to come back from — Quill has no trash surface, so a soft
delete is unrecoverable through the UI. The emulator DB was pulled before any of this and pushed
back afterwards (`run-as mse.quill cp`, journal removed, app force-stopped first), leaving the seed
exactly as it was. Worth knowing for next time: **pull the DB before touching data on the emulator**.
Restoring it also demonstrated the `MainActivity.onStop` backstop — the database changed underneath
the app with no repository push at all, and backgrounding it brought the widget back in step.

**Left on the emulator**: the Collections and Whiteboards widgets are still on the home screen,
deliberately.

## 2026-08-20 (later) — A first screen, and an app with something in it

**The ask**: a new install opens on an empty Home with four tabs and no clue what any of them are
for. So: a welcome screen offering sample content or a way past it, and — if they take it — a
proper account of what was created.

**What it does.** `SplashActivity` now hands off to `WelcomeActivity` instead of `MainActivity` when
this looks like a first install. The screen names the four features (that is the actual complaint —
four things arriving at once with nothing to explain them) and offers "Start with sample content"
or "Start empty". Taking the sample writes a collection, three notes with one pinned, five
flashcards, a quiz and a whiteboard, then swaps the same screen for a report of what landed and
where to find it. Every count in that report comes from the repository callbacks, not from what the
seeder meant to write, so a step that wrote nothing has no row.

**Three decisions worth keeping.**

*The sample is ordinary content, not a demo mode.* It goes in through the repositories, so it is
encrypted if it lands in a locked collection, indexed for search, linked in the whiteboard table,
and pushed to the widgets and the watch — none of which a private INSERT would have done, and all
of which it would keep not doing as features are added. Nothing anywhere else knows the rows came
from here; they are edited, pinned and deleted like anything else.

*Ordering is the disk thread's, not a chain of callbacks.* `AppExecutors.diskIO` is one thread, so
calls issued in order run in order — the note row exists before the save that fills it, the board
before the note that embeds it, and a final marker task cannot run until the rest has. Only the
collection needs a callback, because its id is minted inside the insert.

*"First install" is two questions.* The stored flag says whether this was asked before; the database
says whether anything is here already (`AppDatabase.hasAnyContentSync`). Without the second, every
existing user updating into this build would be greeted with "Welcome to Quill" over their own
notebook. `DataWipe` clears the flag, so a wiped Quill is greeted like a new one.

**The sample content itself** is in `SampleContent`, deliberately not in `strings.xml`: the moment
it is written it is the user's note, and a string resource cannot retranslate a row someone has
since edited. It is also chosen to be worth keeping — the five cards are real study advice, and the
whiteboard is a drawn forgetting curve, which is the subject of the note it is embedded in.

**Verified on a second emulator** (`Quill_Phone_4k`, booted for this and shut down afterwards),
because the first one has content and would correctly refuse to show the screen — which was itself
the first confirmation that the upgrade guard works. Its database was moved aside rather than
`pm clear`ed, so nothing of the user's was destroyed and the original was moved back afterwards.
Both paths run: sample content produces the reported 1/3/5/1/1 and Home shows the pinned welcome
note, the Study Skills collection reading "2 notes · 5 flashcards · 1 quiz", the deck due on the
Flashcards tab, the quiz on Quizzes, and the sketch rendering both in Home's whiteboard row and
inside the note. "Start empty" lands on an empty Home, and a relaunch after either answer goes
straight to the app.

## 2026-08-20 (later still) — "Open with Quill" crashed on launch

**The report**: exported `.quill` and `.quillpack` files import fine from inside the app, but not
when tapped in a file manager. The distinction that mattered turned out to be a different one:
whether Quill was **already running**. With the app alive it worked; with it closed, Quill started,
died, and dropped the user back in the file manager with nothing imported.

**The crash**, from the crash buffer on a cold start:

```
IllegalStateException: Fragment HomeFragment did not return a View from onCreateView()
  at HomeFragment.importBundle(HomeFragment.java:289)      ← Snackbar.make(requireView(), …)
  at MainActivity.deliverPendingImportIfReady(MainActivity.java:279)
  at MainActivity.handleViewIntent(MainActivity.java:251)
  at MainActivity.onCreate(MainActivity.java:87)
```

`deliverPendingImportIfReady` handed the file to Home as soon as a HomeFragment *existed*. On a cold
start the FragmentManager restores that fragment during `onCreate`, so the `instanceof` check passed
while `onCreateView` was still ahead of it — and the first thing Home does with a file is show a
Snackbar, which needs the view it did not yet have. Thrown out of `onCreate`, which is to say the
app crashed on launch. Warm, Home already had a view, so the same code worked, which is exactly the
shape of the user's report.

**The fix is one condition**: wait for `isResumed()`, not for existence. The sibling path for widget
taps (`runWhenNavHostReady`) already did this — its comment even claims it is "the point
deliverPendingImportIfReady already waits for", which was the thing that wasn't true. Nothing is
lost by waiting: `deliverSharedFileWhenHomeIsReady` is registered before the intent is read and
calls back the moment Home resumes.

**A second bug found while there**: the activity's intent outlives the activity, so a rotation
re-ran `handleViewIntent(getIntent())` and imported the same file again — two copies of a note
opened once. Now consumed per intent (`viewIntentConsumed`, reset in `onNewIntent` because a new
intent is a new request) and carried across recreation in `onSaveInstanceState` along with the
pending uri, so an import that arrives in the moment before Home is ready isn't dropped either.

**Verified on the emulator**: exported a note and a collection to Downloads/Quill, then opened each
from the Files app with Quill force-stopped. Before: crash, no import. After: no crash buffer entry,
the note and the collection both land (the app-lock gate comes up first, and the import completes
behind it once unlocked). Rotating after an import leaves the counts unchanged — 8 notes and 3
collections before and after. The seeded database was restored afterwards and the two exported files
deleted.

**Noticed in passing, not fixed**: this emulator's database has no `notes_fts` table, so every
import logs `no such table: notes_fts` from `NoteImporter.indexNoteSync`. It is noise rather than a
failure — `SQLiteDatabase.insert()` swallows the exception and returns -1 — but it means imported
notes on that database are not searchable, and it is worth knowing why the table is missing.

## 2026-08-20 (later still) — Four small things about adding things

**All four are the same complaint in different places**: it isn't obvious how to put something into
an empty Quill.

- **Home's Collections section had no empty state.** Notes and Whiteboards each fall back to a
  message; Collections showed a header with nothing under it, which reads as a section that failed
  to load rather than one waiting to be filled. `collectionsCount()` is now
  `collectionsSectionCount()` like its two siblings, and the empty row's text is picked by a
  three-way rather than the old notes-or-whiteboards two-way.
- **Section headers now add to their own section.** Each carries a trailing "+" (the same
  size-pinning layer-list trick the leading icons use) and a ripple, because a header that does
  something has to look like it does. The three creation actions moved out of the FAB's listener
  bodies into methods, since there are now two ways to reach each.
- **The FAB's options are named after what you get**: Note, Collection, Whiteboard, Import — was
  "Create Note", "Collection", "New Whiteboard", "Import" — each with its icon, Import with the
  download one.
- **The collection screen's split button is gone.** Its two halves ("+" and "▾") were two different
  ways of adding a note dressed as one widget, and the caret read as a menu of the "+" rather than a
  second action. Both now live behind a single "+" that opens a chooser naming them: "New note" and
  "Add existing note". An empty collection gets the same chooser from a button in the middle of the
  screen — and only when it is genuinely empty, not when a filter happens to match nothing, because
  the notes are there and "Add a note" is not the way to see them.

**Verified on the emulator**: all three empty messages (forced by searching for a string that
matches nothing), the Collections header opening the New collection dialog, the four renamed FAB
options with their icons, the collection screen's single "+" and its chooser, and an empty
collection's centred "Add a note" opening the same chooser. The test collection was removed by
restoring the seeded database afterwards.

**Left alone, worth knowing**: those empty messages still say "No notes yet — tap + to create one"
when it is a *search* that matched nothing rather than an empty app. That was already true of notes
and whiteboards; collections now joins them. A filter-aware message would be a separate change.

## 2026-08-20 (later still) — Filters, a hint, and the stroke a back-swipe drew

**"Pinned only" is gone from the filter.** Pinning is capped at three and those three already have a
band of their own at the top of Home, so the filter narrowed the list to something permanently on
screen a few centimetres above it. Out of `NoteFilter`, the dialog and the chip row; the "Other"
section it lived under went too, being empty without it.

**The wrong hint** was the "Add existing notes" picker using the generic `search_hint` ("Search
notes, whiteboards or collections…") for a field that searches notes and nothing else. Now
`search_hint_notes`.

**The back-swipe stroke was a real bug, not gesture tuning.** `WhiteboardView.onTouchEvent` grouped
`ACTION_CANCEL` with `ACTION_UP`:

```java
case MotionEvent.ACTION_UP:
case MotionEvent.ACTION_CANCEL:
    ...
    finishStroke(...);   // commits, and the fragment writes it to the database
```

`ACTION_CANCEL` is exactly what Android sends when the system takes a gesture over — an edge
back-swipe, the shade coming down, a parent view intercepting. Quill read "this gesture is no longer
yours" as "the user lifted their finger" and saved the line the swipe drew. The class already had
`discardCurrentStroke()` for the two-finger case; cancel now goes there too.

**Options considered and declined** (the user asked for suggestions first): claiming the edges with
`setSystemGestureExclusionRects` — costs the back gesture on those strips, and the platform caps
exclusions at 200dp anyway; and discarding very short strokes — a heuristic that would also throw
away deliberate dots, and it ignores the signal the system is already sending.

**And the empty board it left behind.** The row exists from the moment the canvas opens (strokes
carry a foreign key onto it), so every glance at a new board left a blank rectangle on Home.
`WhiteboardFragment.discardIfNeverUsed` now deletes it on the way out — but only a board created for
this visit, and only when nothing was drawn or typed on it, it has no title, and no note points at
it. "Created for this visit" needed a new nav argument (`created_now`, defaulting to false) because
Home's FAB creates the row before navigating, so the screen otherwise cannot tell a board a second
old from one made last week.

**Verified on the emulator, before and after**: with the old build, an edge back-swipe out of
"Sensor diagram" took it from 1 stroke to 2; with the fix it stays at 1. A board created from the
Whiteboards header and immediately swiped away goes 6 → 7 → 6. An *existing* empty board opened and
left stays at 6, which is the scoping doing its job.

**Also**: the centre button now says "Whiteboard centred", because its usual outcome is that nothing
moves and a button that appears to do nothing is one nobody presses twice. Both this and "Nothing to
undo" go through one `showTransientMessage` holding a single Toast it cancels before reshowing —
Android queues toasts, so without it three taps meant three messages waiting each other out. One
field rather than two, so the two messages don't stack against each other either.

## 2026-08-20 (later still) — Joining a session: every failure now says something

**What was wrong.** The joiner took `barcode.getRawValue()` as the session token, unchecked. Any QR
code in the world — a poster, a Wi-Fi card, a vCard — was therefore a valid session to go looking
for, and Nearby's discovery has no deadline of its own, so the search ran until the user gave up
staring at "Connecting…". Every other failure arrived as `onError(String)` carrying whatever Play
Services had said, which is how "Bluetooth is off" and "nobody is hosting that code" ended up
looking identical and equally unactionable.

**Three changes.**

- **`SessionCode`**: the QR now carries `quill://whiteboard/join/v1/<TOKEN>` and a scan is parsed
  before anything is started for it. A bare token still parses, so a phone on this build can join a
  host on an older one, but it has to match the token pattern exactly. Shaped as a URI because that
  is the thing a phone's own camera app could be taught to open later — a naked token offers it
  nothing.
- **A 20-second deadline on joining**, armed before discovery starts and covering the connection
  too: from the outside, finding the host and connecting to it are one act, and a connect that
  stalls half way is no more use than a search that finds nothing.
- **`CollabSession.Failure`** replaces the prose string: RADIO_UNAVAILABLE, CANNOT_HOST,
  CANNOT_SEARCH, SESSION_NOT_FOUND, CONNECT_FAILED. The fragment maps each to its own copy and shows
  a *dialog* rather than a toast — each of these is a dead end the user has to decide something
  about — with "Scan again" offered where retrying is the obvious move. `STATUS_RADIO_ERROR` is
  singled out because it is the one failure the user can go and fix.

Failures now also report **once**: `fail()` is a no-op after `stop()`, so a timeout firing as a
connection result comes back can't produce a second error about a session that no longer exists. And
a failed *incoming* connection no longer ends the host's session — one joiner failing is no reason
to take the QR code away from whoever tries next.

**Verified**: five JVM tests over `SessionCode.parse` (URLs, Wi-Fi payloads, vCards, wrong lengths,
non-hex, a prefix with rubbish after it — all rejected; own code, bare token, lowercase and
whitespace — all accepted). On the emulator: hosting still advertises and shows the QR; and with the
scanner temporarily bypassed (probe removed afterwards) a join against a token nobody advertises
fails at exactly 20s — `join timed out after 20000ms` → `SESSION_NOT_FOUND` → the "Couldn't join"
dialog with "Scan again".

**Still open**: the UX of reaching Join at all — it lives inside a whiteboard, so joining someone
else's board means opening one of your own first. Suggestions given, awaiting a decision.

## 2026-08-20 (later still) — Joining moved out of the whiteboard

Options 1 and 2 of the four suggested, chosen by the user.

**Join is now on Home.** A fifth option on the expanding FAB, "Join session" (ic_scan), which scans
and then opens a *new* board already joined. It was inside the whiteboard screen, which meant
joining someone else's board began by opening one of your own — a session is not something you do to
a board, it is a way of getting one. Order is permissions → scan → board, because each step is a
chance to back out and the board is the only one that leaves anything behind; it is created with
`created_now`, so a refused or failed join takes the empty board with it.

**The QR is a real link now.** `quill://whiteboard/join/v1/<TOKEN>` has its own intent filter, so the
phone's own camera or Lens can open the host's code directly — no in-app scanner, no menu. Its own
`<intent-filter>` rather than another `<data>` in the import one, because data elements there
combine as a cross-product with those mime types. `handleViewIntent` branches on the scheme before
either path is attempted: a quill:// link handed to the importers would be opened, found to contain
no bundle, and reported as a broken file.

**The app lock was the interesting part.** The gate is a view over the window, not a screen of its
own, so Home goes on resuming behind it — a link scanned while Quill was locked would have created a
board, joined a stranger's session and drawn it onto the screen underneath the words "Quill is
locked". The token now waits in `pendingJoinToken` until the gate comes down, delivered from both
`hideLockGate()` and `onResume` (biometric success dismisses a dialog and never resumes the
Activity; the device-credential fallback runs in its own Activity and does), and carried across a
rotation in `onSaveInstanceState`. If the user walks away from the gate, nothing has happened at all.

**Shared rather than copied**: `CollabPermissions` (the version-gated Bluetooth/location/Wi-Fi
ladder) and `SessionScanner` (scan + validate), both now used by Home and the whiteboard screen. Two
copies of a permission list is two chances to update one and not the other.

**Verified on the emulator**: a `quill://whiteboard/join/v1/A1B2C3D4` link fired at a locked Quill
stops at the gate; unlocking opens a fresh board with "Connecting…"; twenty seconds later the
"Couldn't join" dialog; and backing out leaves the board count at 6, unchanged. Home's FAB shows all
five options and "Join session" opens Play Services' scanner. The one thing not verifiable here is
two devices actually drawing together — that needs the second phone.

## 2026-08-20 (later still) — Feature implementation: N-peer whiteboard collaboration

**Asked:** scale live whiteboard collaboration past two devices, and make sessions persist across
leaving the whiteboard screen rather than ending the moment the fragment is torn down. Built on
`feat/multiUserWhiteboard`.

**`CollabSessionHolder` — session ownership moved out of the fragment.** A new static singleton
holds the live `CollabSession` independent of any one `WhiteboardFragment` instance, so the session
survives that fragment being destroyed and recreated (back-and-forth nav, rotation) instead of
dying with the screen that opened it. Fragments talk to it through `attach`/`detach`; events that
arrive with no screen attached (ink drawn while the host had the board closed) are buffered — up to
a generous but bounded queue — and replayed on the next `attach` rather than dropped. Ends only on
an explicit `end()` (host) or `leave()` (joiner), or a real Nearby connection loss.

**This is a deliberate scope reduction from the plan**, not the full redesign: `memory/
whiteboard_collab_redesign_plan.md` called for a bound foreground `Service` (persistent
notification, survives the process itself being backgrounded — Nearby throttles once that
happens). `CollabSessionHolder` gets session persistence across fragment teardown/recreate but
*not* survival once the whole app is backgrounded or killed by the OS. Documented in the holder's
own class doc as the piece of the redesign implemented today, with promoting it into a real
`Service` left as the described next step.

**Protocol grew from "two peers, implicit roles" to a real star topology.** `CollabSession` went
from assuming exactly one peer everywhere to a peer map, unbounded, with the host continuing to
advertise after the first connection so a third (and further) device can still find it. Four new
`CollabMessage` types carry what a two-device version could leave implicit: `TYPE_PEER_INFO` (name
handshake, relayed by the host so every peer's display name converges across the whole star),
`TYPE_HOST_ENDED` (lets a joiner tell "the host ended it" apart from a bare connection drop),
`TYPE_PEER_LEFT` (a joiner's explicit "Leave" relayed to the rest rather than dropping the whole
session), and `TYPE_PRESENCE` (whether a peer is actually looking at the board right now, versus
merely still connected). `SNAPSHOT` — the host's whole board, sent to a newly-joined peer — now
arrives in numbered chunks (`CollabMessage.snapshotChunks`, budgeted at 3/4 of Nearby's
`MAX_BYTES_DATA_SIZE` per chunk) instead of one payload, since a long-lived board full of ink can
exceed the ~1 MB `BYTES` payload cap that a two-peer prototype never had to worry about.

**UI: a people count replaced the per-peer chip row.** `collabPeople`, a small tonal button in the
top bar next to the title, shows the number of people currently viewing the board and opens the
full roster (names) on tap — visible only during a live session. This replaced an earlier
chip-per-collaborator design (`item_collab_chip.xml`, deleted) with something that doesn't grow the
top bar as a session gets bigger and reads at a glance rather than as a row to scan.

**Status:** compiles clean (`:app:compileDebugJavaWithJavac`); **not verified on a real device** —
no adb in this environment, and an N-peer session specifically needs three-plus physical phones to
exercise the star topology, which two-device testing can't cover even where it's available.

## 2026-08-20 — Bug fix: whiteboard collab crash on exit, non-host

**Reported:** while collaborating live on a whiteboard, quitting the collab session as the
non-host crashed the app.

**Root cause:** `WhiteboardFragment.onDestroyView()` unconditionally calls
`endCollabSession()` → `CollabSession.stop()`, which triggers Nearby Connections' async
`onDisconnected` callback. The `collabListener`'s callbacks (`onPeerConnected`,
`onPeerDisconnected`, `onMessage`, `onError`) all called `requireActivity()` unconditionally —
by the time an async callback fired, the fragment could already be detached (screen left,
activity finishing), so `requireActivity()` threw `IllegalStateException`. Hit hardest as the
non-host because leaving the whiteboard is the ordinary way to "quit" collab.

**Fixed:** added `if (!isAdded()) return;` guards before every `requireActivity()` call in
`collabListener`, on `fix/whiteboard`. Confirmed compiling (`compileDebugJavaWithJavac`); user
confirmed done.

## 2026-08-20 — Feature implementation: exit warning during live collab

**Asked:** warn the user before they leave the whiteboard while a collab session is active,
since leaving currently ends the session for everyone (unconditionally, per the bug above).

**Built:** both exit paths — the in-app `back_button` and system/gesture back (intercepted via
a new `OnBackPressedCallback` registered in `onViewCreated`) — now route through a new
`attemptExit()` helper. If `collabSession != null`, it shows a confirmation dialog ("Leave
whiteboard?" / "Leaving this whiteboard will end the session for everyone." / "Leave and end
session" vs. cancel) before navigating up; otherwise it exits immediately as before. Added
strings `collab_exit_warning_title`, `collab_exit_warning_message`, `collab_exit_confirm`.
`onResume()`'s forced navigate-away (board deleted, or its collection re-locked while the app
was backgrounded) deliberately bypasses this warning — that exit isn't a user choice.

**Note:** this is a stopgap, not a fix for the underlying "leaving always ends the session"
behavior — see `memory/whiteboard_collab_redesign_plan.md` for the planned follow-up
(session persists across screen exit; only host-end/peer-leave/real-disconnect end it), which
would make this warning's message stale once implemented (it would no longer be true that
leaving *always* ends the session for everyone).

## 2026-08-21 — "Not enough Q&A blocks" became a picture (Feature implementation)

**The complaint**: choosing "Turn into flashcards" or "Make quiz" on a note without enough Q&A
blocks showed a Snackbar naming the rule, and the rule was never the hard part. It only helps
someone who already knows a Q&A block is a thing you insert, and where from — and the control that
inserts one lives in the formatting bar, which is *not on screen* at that moment: the bar exists
only while the keyboard is up, and both actions are reached from the options menu with the keyboard
down. The message pointed at nothing, for a few seconds, and then took itself away.

**Options weighed**: a screenshot/PNG of the toolbar in a dialog (the user's first suggestion), a
spotlight overlay on the real bar, or a live replica built from the app's own widgets. Chose the
replica. A screenshot would be another asset to re-cut whenever the bar changes, at a fixed density,
frozen at whatever the bar looked like the day it was taken; a spotlight would have to raise the
keyboard first just to have something to point at.

**What was built**: `QaBlockHintDialog` — an M3 dialog with the rule in one short paragraph (plus
"This note has 2 of 5." for the quiz case, because a count is different help from "not enough"), a
card holding a replica of the formatting bar, and a looping zoom: the strip scales 1× → 1.9× about
the Q&A slot's centre while the other icons fade to 20% and slide off the clipped edges. Rest wide,
ease in, hold zoomed — a camera move, not a swelling button. The highlighted icon gets a
`brand_purple` ring; a filled pill alone was invisible against a dialog that is itself faintly
purple (seen on the device, not guessed).

**The decision that keeps it honest**: the bar's items moved into a shared
`FormattingToolbarController.Item` enum, and both the real toolbar and the illustration are built
from it. An item added or reordered moves in the picture too — the illustration cannot go stale.

**"Add a Q&A block"** on the dialog inserts one rather than making the user go hunting for the icon
they were just shown. Two things had to be got right for that: the insert runs from the dialog's
*dismiss*, not its button (`showSoftInput` is discarded while a window that isn't the editor's holds
focus), and `NoteEditorFragment.showKeyboardOnceWindowFocused()` re-asks when window focus actually
arrives. Without both, the block appeared focused, with a caret, and no keyboard — so no formatting
bar, which is the one thing the dialog had spent its whole life pointing at.

**Also**: Import is now the last option on Home's FAB, after "Join session" — it is the only option
there that doesn't bring something into being, so it sits at the bottom of the list.

**Verified on the emulator**: both variants of the dialog, the zoom at rest and at full zoom, and
the add-block path ending with the keyboard up and the real bar showing the Q&A icon in the position
the illustration pointed at. Scratch notes made for the test were deleted afterwards.

## 2026-08-21 (later) — Decks and quizzes can be made from their own tabs (Feature implementation)

**The complaint**: the Flashcards and Quizzes tabs were read-only views of what the note editor had
produced. The screen whose entire subject is flashcards was the one place you couldn't make any —
its empty state described a menu item on another screen and left you to go and find it.

**What was built**: a "+" in each tab's header and a button in each empty state, both opening a note
picker. Rows carry the note's Q&A count ("4 Q&A blocks"), which is the number the question turns on
and also quietly teaches the rule — a note sitting at 4 in a picker that wants 5 explains the minimum
better than a sentence would. Notes that already have a deck/quiz are filtered out rather than shown
and refused. Picking runs the same call the editor runs (`syncFromNote` / `ensureForNote`), so a deck
made here is indistinguishable from one made there.

**Nothing to offer** is three different situations and they are answered differently: no note has a
usable block at all, or none has enough → the Q&A hint dialog with its zooming toolbar picture,
since both end in "write more blocks"; every candidate already has one → a Snackbar, since that ends
in nothing. The dialog grew a teach-only mode (one "Got it", no add button) because no note is open
there. The Quizzes tab gets quiz-worded copy rather than borrowing the flashcards title — a dialog
on that tab announcing that flashcards come from Q&A blocks answers a question nobody asked.

**New**: `NoteRepository.loadQaCandidates` (parses every note's Markdown, off the main thread, only
when a picker opens) and `NoteQaPickerDialog`, following the `WhiteboardPickerDialog` idiom rather
than inventing a fourth picker shape.

## 2026-08-21 (later) — Stale "x due" on the decks list (Bug fix)

**The bug**: add a Q&A block, make flashcards, then empty one half. The review screen correctly said
"No cards yet"; the decks list still said "1 due". Two halves of the app disagreeing about the same
deck.

**The cause**: "a card whose block is gone stops appearing" was implemented as an absence from one
query — `syncFromNote` returns only cards it can match to a live block — while every count was a
`COUNT(*)` over the rows. Nothing recorded the distinction, so nothing else could honour it.

**Options weighed**: delete the orphaned cards (rejected — the repository deliberately keeps review
history, and this case is exactly "the answer field was blank for a minute while I rewrote it"), or
work it out in SQL (impossible — whether a block is usable is a fact about the note's Markdown).
Chose a third: stamp it. New `flashcards.orphaned_at` column (schema v10, additive), set when a note
can no longer produce the card and cleared the moment it can again, schedule untouched. Every count
of "what is there to review" filters on it — decks list, `countForNote`, the reminder's due summary,
the widget, the Wear projection, the collection card's badge. Only `loadBySegmentIdSync` ignores it,
since that is what has to find an orphaned card in order to revive it.

**The part that mattered for the symptom**: the stamp is refreshed from `NoteRepository.saveNote`,
not only from `syncFromNote`. Deliberately *not* by calling `syncFromNote` there — that is the thing
that makes a deck, so running it on every save would turn writing a Q&A block into silently
generating flashcards nobody asked for. `markOrphansOnSaveSync` only ever moves the stamp on rows
that already exist.

**Free side effect**: pre-`source_segment_id` rows (from before that column existed) are stamped too.
They could never be matched to a block, so they were already invisible for review while still being
counted — the same lie by a different route.

**Verified on the emulator**: made a deck from the Flashcards tab's picker; emptied the answer and
backed out — the row vanished from the list without the deck screen ever being reopened; refilled it
— the row came back with its schedule intact. Existing decks survived the v9→v10 migration.

## 2026-08-21 (later still) — Swipe to delete, undo, and swipe between tabs (Feature implementation)

Three quality-of-life asks in one pass.

**Swipe to delete** on full-width rows: notes (Home and inside a collection), flashcard decks, and
quizzes. Not on collection or whiteboard cards — those sit in a 2-column grid in the same list on
Home, and a card sliding out from under a full-width red panel would promise a gesture the layout
doesn't have. The panel and its bin icon are drawn in `onChildDraw` rather than laid out: it
decorates the *gap* the row leaves, nothing can interact with it, and giving every row a
permanently-hidden background view would double the item count for something on screen for a third
of a second. Threshold raised to 70% of the row's width with the escape velocity multiplied by 8 —
the user asked for "more bite" after a small fast flick was enough; raising the distance alone
wouldn't have done it, since ItemTouchHelper also dismisses anything thrown hard enough regardless
of distance.

**Undo** on every delete reachable from a list — note, deck, quiz, whiteboard, collection.
Implemented as *deferred* deletion (`UndoDelete`): the row leaves the list at once, the database is
not touched until the Snackbar goes away. Chosen over delete-then-restore because only notes are
soft-deleted; a quiz takes its attempt history with it and a board takes every stroke, so restoring
would mean capturing and replaying all of that. Deferring fails in the safe direction — if the
process dies mid-window the delete simply never happened. The Snackbar *is* the timer, so there is
no second clock to keep in step; leaving the screen detaches it, which dismisses it, which commits.
The hidden-key set is static, because a rotation rebuilds the list from a database that still holds
the row. Bar is anchored above the bottom nav: Undo sits at the same end as the last tab, so an
overlapping bar meant a tap aimed at Undo a moment too late landed on Profile (hit twice while
testing).

**Swipe between tabs**, decided by the user: the row wins wherever there is a row, the tab gesture
takes everywhere else — headers, the greeting, the card grids, empty space. Watched in
`MainActivity.dispatchTouchEvent` and never consuming the event, so it reads the gesture alongside
whatever the screen is already doing rather than fighting for interception. It stands down when
`SwipeToDelete.isSwipeInProgress()`, and needs a third of the screen's width — several times a
row's commit distance, so by the time it qualifies any row that was going to claim the drag already
has. No wrap-around at the ends.

**Not covered, deliberately**: the two detail screens that delete and immediately navigate away
(the flashcard review screen, quiz detail). They keep their confirmation dialogs; an undo bar on a
screen being left isn't read.

**Verified on the emulator**: 230px flick and 450px drag leave the row; 900px deletes; Undo restores
it; swiping a note row does not change tab; swiping the greeting moves Home → Flashcards and back.
Two rows were lost to timed-out undo bars during testing and were restored from a pulled copy of the
database.

## 2026-08-21 (later still) — Home's header changes with the hour (Feature implementation)

**The ask**: message and colour theme for Home's top bar depending on time of day, from the
`home_morning` / `home_day` / `home_night` frames in the MSE Figma file, with better wording than
the placeholders.

**Taken from Figma** (gradients read top → bottom, ink from the text nodes):
morning `#8E9DCD → #FCB9A3` on `#2D2E37`; day `#FE6C54 → #F5A188` on `#2D2E37`; night
`#0B5786 → #001D2C` on `#B9E8E7`, plus the sparkle in the corner (`ic_stars.png`, which was already
in the repo at exactly the frame's 58×62).

**The decision worth recording**: the look and the words don't divide the day the same way, so they
are two types. `TimeOfDay.Sky` is the three palettes the design actually draws; `TimeOfDay` is five
periods — small hours (0–5), morning (5–12), afternoon (12–17), evening (17–21), night (21–24) —
each pointing at a sky. Prompted by the user seeing "Good evening" at 2am. Evening shares the day
sky deliberately: that coral is a sunset already. Splitting the two is what lets the wording get
more specific without asking the designer for more palettes.

Each period has a greetings array and a subtitles array, picked independently and re-drawn on every
fresh visit to Home (not on resume — a greeting that reshuffled when you closed a note would read as
a glitch). Greetings carry no name; `home_greeting_with_name` appends it, so every line has to work
both ways.

**Two things that were easy to get wrong**: the status-bar scrim is painted with the gradient's *top*
stop (the drawable's `angle=90` puts `startColor` at the bottom, so the colour names say top/bottom
rather than start/end), and the night sky is dark enough that the system's status-bar icons have to
flip pale — set on resume and put back in `onPause`, since the window belongs to the activity and
every other screen wants dark icons.

**A bug introduced and fixed in the same pass**: turning the header row horizontal to make room for
the stars left it `center_vertical`, which centres the text in the full 176dp gradient — but the
content sheet covers its bottom 56dp, so the subtitle ended up pressed against the sheet. Now
`gravity="top"` with a 36dp top pad, which puts the greeting where Figma has it, and the two lines
sit 2dp apart because in the design they are one text block.

**Verified on the emulator**: all three skies, by temporarily pinning `TimeOfDay.now()` for the
screenshots (the emulator is a production image, so `adb` cannot set its clock — `adb root` is
refused). Status-bar glyphs sampled as pure white on `#0B5786` to confirm the flip.

## 2026-08-21 (later still) — Editing Q&A blocks (Feature implementation / Bug fix)

Three complaints about what it's like to work with a Q&A block, all of them about the block being
harder to get rid of or add to than it looks.

**Deleting it.** The only route was a long-press on the card, which you could hardly ever land: the
card is almost entirely covered by two EditTexts whose own long-press is text selection, so the
gesture nearly always produced a copy/paste menu. There is now a cross in the block's corner, and
backspace at the start of the question does the same thing. Both go through one path that asks first
only when there is something to lose — an empty block is nearly always one just inserted by mistake,
and a dialog in front of undoing that is the sort of confirmation nobody reads. Backspace at the
start of the *answer* steps back into the question instead, so the block behaves like the one field
it looks like. Still deliberately not a merge-with-previous: dissolving a question into the prose
above it would strand the answer.

**Backspace needed catching somewhere else.** `setOnKeyListener` only sees real key events, which is
a hardware keyboard. Most IMEs call `deleteSurroundingText`, or route a synthetic DEL through
`sendKeyEvent` — both bypass the view's key listener. `RichTextField` now wraps its InputConnection
and catches all three, which also fixes the pre-existing backspace-to-merge between text segments on
a phone.

**Inserting from inside a block.** The Q&A, image, audio and whiteboard controls used to grey out
whenever the caret was in a Q&A field — a rule about *nesting* stated as if it were a rule about the
caret. A block can't go inside a Q&A block, but it can go after one, so
`splitFocusedTextForBlockInsert` now puts it directly after the block being edited (reusing the text
segment that follows, if there is one, rather than stacking empty paragraphs), and `embedsAllowed`
is simply true wherever the caret is.

**Dimmed controls now answer back.** Only headings are ever unavailable now. `setAvailable(false)`
used to call `setEnabled(false)`, so the tap vanished and the user pressed harder; the button stays
clickable and the toolbar routes those taps to "Heading 1 isn't available inside a Q&A block",
named from the control's own label so the sentence can't drift from the button.

**Verified on the emulator**: the cross deletes; hardware backspace in an empty question deletes the
block; tapping the Q&A control from inside a block produces a second block after it; tapping the
dimmed H1 shows the message. **Not verified on device**: the soft-keyboard `deleteSurroundingText`
path — repeated `adb input keyevent` puts the emulator into hardware-keyboard mode and Gboard stops
rendering, so there was no on-screen backspace left to press.

## 2026-08-21 (later still) — Four epic items: search, global review, media encryption, quiz answers

**Full-text search (Epic F).** `notes_fts` had been kept current on every save for weeks and nothing
ever asked it anything; Home matched a note's title and its *preview*, which is only the first
non-empty line, so a word in the middle of a note could not be found. Two things had to be fixed
before wiring the query was worth anything. The index was only ever created in `onCreate`, so every
database that upgraded from v3 had none — silently, because both the reads and the writes are
guarded for FTS5-less builds and a missing table looks exactly like a missing module. It is now
created and backfilled on the upgrade path (schema v11). And this project's emulator image has no
FTS5 module *at all*, so there is a body-scan fallback: slower, completely reliable, and the
difference between a feature that works everywhere and one that quietly does nothing on some phones.

**Global review session (Epic D).** The same `FlashcardsFragment` with no note id, because flipping,
grading and scheduling don't care which note a card came from. What the note id also buys — deleting
the deck, reconciling blocks on the way in — is switched off rather than reimplemented. Entry point
is a "Review N cards due now" button on the decks list, shown only when something is due: a
permanent button that mostly reports nothing is a button you learn not to read.

**Media encryption (Epic B).** The one part of the security epic never delivered: note text was
encrypted, the images and recordings behind it were not — unreachable through the UI while the
collection was shut, which is not the same property. `MediaFiles` gives each file a `QLM1` header
carrying its collection id, so the file is self-describing and no decode site needs a path → segment
→ note → collection lookup or a `Context` threaded into `BitmapUtils`. Converted on lock, unlock,
move, and on saving media into an already-shut collection. The deferral note's warning — that a
version writing decrypted temp files and forgetting to clean them up would be worse than the current
state — is honoured by there being no temp file at all: images decode from a byte array, audio and
the waveform read through a `MediaDataSource` over plaintext in memory. Exports decrypt on the way
out, since a bundle is plaintext by definition.

*Found while testing*: a lapsed Keystore auth window makes media undecryptable while the app still
considers the collection open (the note text is already in memory by then, so only media notices).
The first version then handed the ciphertext to MediaPlayer, which reported a native decoder error.
Now it refuses and says "Unlock this collection again to play its recordings".

**Quiz attempt answers (Epic E, schema v12).** Built now that something reopens them. Stores the
options **as they were shown**: the generator shuffles per attempt, so a paper rebuilt from the note
would put the same answers under different letters and stop being the paper that was sat. Written
for abandoned attempts too. Tapping any row in a quiz's history reopens its marked paper in a dialog
reusing `QuizResultsAdapter`, so an old paper and a just-finished one are the same view.

**Bug reported mid-session and fixed**: making flashcards then moving the note into a locked
collection made the deck vanish with no warning. The deletion is deliberate and necessary — cards
hold the question and answer as their own plaintext columns, so keeping them would leave a readable
copy of an encrypted note in a table the lock doesn't reach, which is why locking a whole collection
removes them too. But locking *says so* in its confirmation and moving did it in silence. The
destination picker now labels locked collections ("Private · Locked") and confirms before moving
into one, naming what is lost and that the Q&A blocks stay.

**Verified on the emulator**: a word on the second line of a note is now findable; the global
session opens "Due now" with 3 cards from three notes; two recordings went plaintext → `QLM1` on
lock and back to a valid `ftypmp42` on unlock, with waveforms rendering from the encrypted files in
between; a six-question quiz stored its paper (blanks as -1) and reopened it intact. Visual QA items
in Epics H and I ticked off at the user's word.

## 2026-08-21 (later still) — Stale due count, and keeping the review schedule through a lock (Bug fix / Architecture decision)

**Stale "Review N cards due now".** Two decks of five, delete one, the button still said ten. The
count came from its own SQL query, and a swipe-delete is *deferred* — the row leaves the list when
the undo bar appears but nothing is written until the bar goes, so the query kept counting cards the
user had already seen disappear. Now the total is summed from the decks actually rendered, which
cannot disagree with what is on screen, and the commit callback reloads so the screen settles once
the delete lands. The global session filters the same pending-delete keys, or the button and the
session it opens would count differently. The separate `countDueAcrossNotes` query is gone.

**Keeping the schedule through a lock.** User asked whether flashcards could be re-created after
moving a note into a locked collection rather than just deleted. Re-creating was always possible —
`syncFromNote` rebuilds every card from the note's Q&A blocks — so tracking a count would have added
nothing. What the delete actually destroyed was the SM-2 schedule, the one part of a deck that
cannot be regenerated from the note.

The delete was half right. `front` and `back` are copies of the note's text in plaintext columns, so
they genuinely cannot stay behind a locked collection. But `interval`, `repetitions`, `easiness` and
`next_review` are not content: they say nothing about what the note contains. So the row now stays
with its text blanked and `orphaned_at` stamped — which every "what is there to review" query
already filters, since that column was added earlier the same day for the stale-deck bug. The next
sync of the note refills the text from its blocks and clears the stamp, schedule untouched.

One trap found while designing it: the save path also clears `orphaned_at` for live blocks, which
would have brought a blanked card back as an empty one. The revive now requires non-empty text, so
only a real sync — which refills the text first, in the same transaction — can restore a card.

Both confirmation dialogs used to promise the schedule was lost and now say the opposite, correctly.

**Verified on the emulator**: a card seeded at interval=30, repetitions=7, easiness=2.9 came through
lock (text blanked, stamped, schedule intact) and unlock (text refilled, stamp cleared, schedule
intact) — the deck screen showed "All caught up · Next card: in 23 hours" rather than a fresh card
due now. Deleting a deck now drops the button from 3 to 2 immediately and it stays there after the
undo window closes.

## 2026-08-21 (later still) — A name on first launch (Feature implementation)

New installs are given a name like `quill_7692` instead of being greeted by nobody. Home's greeting
has a shape — "Good morning, <name>" — and the nameless version is the lesser half of it; a
generated name is a starting point the same way a new document is called "Untitled", and the Profile
field shows it from the first visit so changing it is one tap.

**Where it happens matters more than what it generates.** `ProfilePreferences.ensureDefaultName` is
called from `WelcomeActivity.openMain`, which both answers to the welcome screen pass through, so it
runs exactly once on a genuinely fresh install. Deliberately *not* from `displayName()` (a getter
that writes is a trap) and not from `Onboarding.markWelcomeSeen`, which also fires on the
upgrade-shortcut path — an existing notebook whose owner never filled the field in has been greeted
plainly for weeks, and an update that started calling them `quill_4821` would be an odd thing to do
to them.

Called synchronously rather than on the disk thread, unlike the welcome flag beside it: Home reads
the name while drawing its greeting, and a name still in flight would leave the first screen of a
new install greeting nobody and then quietly gaining a name on the next resume. Four ASCII digits
via `Locale.US` — `Character.isLetterOrDigit` would accept Arabic-Indic digits too, and a handle
should look the same whatever the device's locale is.

**Verified on the emulator**: reaching the real welcome screen needed the database gone (the flag
alone isn't enough — `shouldShowWelcome` also asks whether there is content) and `WelcomeActivity`
is `exported="false"`, so it can't be launched from adb. Backed up every shared_prefs file and the
database, removed the db and the onboarding flag, took the first run through "Start empty", saw
`quill_7692` written and Home render "Hey there, quill_7692" immediately, then restored everything.
`pm clear` was avoided on purpose: it may take the Keystore entries with it, and the locked
collection's ciphertext would be scrap without its key.

## 2026-08-22 — A name worth having, and a screen that asks for it (Feature implementation)

**`quill_7692` was the wrong shape.** The name's real job is telling people apart in a shared
whiteboard session, and two four-digit handles have to be read digit by digit where "Amber Fox" and
"Quiet Heron" are distinguishable at a glance. It is now an adjective + animal drawn from two
24-item arrays (576 pairs) — no collision check, because there is no server and none is needed for
two people in a room.

That needed one rule change: `DisplayName` didn't allow spaces, which also quietly meant nobody
could type their own full name. Space is now allowed, with `sanitize` collapsing runs and trimming
the ends so the permission can't be used to store a name that is mostly nothing.

**A screen to ask.** After the sample-content question, a third pane: "What should we call you?"
with the generated name already in the field, Continue and "Keep this one". Pre-filled rather than
empty is the whole design — an empty field asks a stranger to invent something before they have
seen the app, most people type nothing, and everyone ends up unnamed, which is the problem the
default was for. Both buttons leave with a name; the suggestion is written on the way *in*, so the
skip path has nothing to do and a process death mid-screen still leaves a named install.

Made friendlier on the user's note that it read as generic: centred rather than pinned to the top
(it is one question, and top-aligned it left most of the screen empty below it), and a large emoji
drawn from a short warm set above the title.

**Collab naming was started and backed out** at the user's request — `deviceLabel()` still sends
`Build.MODEL`. Worth knowing for whenever it resumes: only the *joiner's* label travels, because a
host advertises under the session token and that is the only endpoint-name slot there is. Naming the
host to the joiner needs a message in `CollabMessage`, and neither direction can be checked without
two devices.

**Testing note — first-run flows are dangerous on a live emulator.** Reaching the welcome screen
needs the database gone (the flag alone isn't enough; `shouldShowWelcome` also asks whether there is
content), and one such round left the user's notes missing until they were restored from backup. The
safe way, used for the final screenshot: temporarily flip `WelcomeActivity` to `exported="true"`,
launch it with `am start`, and clear only `profile_prefs.xml` — the pane touches no database at all.
Revert the manifest afterwards.

## 2026-08-22 (later) — Finishing the UX batch: an evening sky, a streak, and taps (Feature implementation)

Picked up an unfinished working tree on `design/ux-improvements` — haptics, the Profile study
section, varied empty states, a softer swipe threshold, the flashcard celebration and the name
shuffle were all written but nothing had been built. It did not compile:
`bg_home_header_evening.xml` referenced `header_evening_top` and `header_evening_bottom`, which had
never been added to `colors.xml`, and aapt2 fails at link on a missing colour.

**The evening palette had to be invented, not restored.** The comments around it — in `TimeOfDay`
and in the drawable — described evening as "the coral of the day header carried down into gold",
which was true when they were written and is not any more: the day palette had since been re-sampled
to an open blue (`#538DCD` → `#BDCFE6`). So evening is now the only warm sky of the four rather than
a warmer version of the one beside it, and that is the reason it exists: five to nine is the stretch
neither the noon blue nor the night navy describes. `#E4796B` → `#F7C77E`, with the top stop kept
about as light as `header_morning_top` so `header_ink_dark` still reads on it. Both stale comments
now say what the palette actually is, and the four colours are commented as one arc — dawn, noon,
sunset, navy — because that is the only thing that makes the set legible to whoever reads it next.

**What could and could not be checked on the device.** The emulator's Quill has App Lock on with no
fingerprints enrolled, so it opens straight onto the PIN sheet; the user supplied the PIN. Study
section: "11 days in a row / 3 cards today", and `StudyCalendarView` measured to 890×312 — exactly
20 columns of 44.5px by 7 rows, so `onMeasure` is deriving height from width as intended. The
haptics row writes `haptics_enabled=false` then `true` in `profile_prefs.xml` and the whole row is
the tap target, not just the switch.

The three empty-state lines were verified by accident and could not have been verified any other
way: this install has content in all three sections, and a stray character typed into the search
field filtered every section empty at once — which is the one screen that shows all three lines
together, three different sentences rather than one template with the noun swapped.

The evening header itself is still unseen. It only draws between 17:00 and 21:00, the image is a
production build so `adb root` is refused and the clock cannot be moved, and FLAG_SECURE makes
screenshots black regardless. The four gradients were rendered to an HTML swatch sheet instead, for
the user to judge the palette on.

**Grading a card meant touching real data, so the database was backed up first.** `quill.db` pulled
via `run-as` with the app force-stopped (so no `-wal` to reconcile), one card graded to reach the
summary panel — "Session complete / Every card right first time.", no crash through `celebrate()` —
then the file copied back and the deck list confirmed returned to "Review 3 cards due now" with all
three decks at "1 new". Worth knowing: opening a deck whose note has no Q&A blocks *deletes* that
deck's cards as a side effect of `syncFromNote`, so the count fell from 3 to 1 just from looking.
The restore covered it, but a read-only-looking action was not read-only.

Two traps for next time. `adb shell run-as pkg sh -c '...'` loses its quoting twice over — the local
shell eats the quotes and the device shell then splits on `;`, so everything after the first
semicolon runs outside `run-as` in `/`. Wrap the whole thing in double quotes for adb. And after
unlocking, the soft keyboard sits over the bottom nav, so tab taps land on the IME and look like
dead taps; send KEYCODE_BACK first.

**Swipe threshold settled at 0.65** after the user tried the 0.6 build: 0.7 was a haul, 0.6 stopped
reading as deliberate. Escape velocity left at 4f — it is a separate way into the same delete (speed
rather than distance) and was not what felt wrong.

## 2026-08-22 (later still) — Making the welcome screen arrive rather than appear (Feature implementation)

The user asked for the feature-list pane and the summary pane after it to be more engaging. What
they got is an entrance and a copy pass, plus one new shared class.

**`Reveal` — the way things arrive.** `FlashcardsFragment.celebrate()` had already hand-rolled a
pop-then-stagger with specific numbers (320ms overshoot(2f) for the badge, 220ms decelerate rises at
60ms intervals). Writing a second one for the welcome screen would have meant two screens inventing
their own timing, which is how an app starts feeling assembled from parts, so the animation moved
into `mse.quill.util.Reveal` — `popIn`, `riseIn`, `stagger`, `staggerChildren` — and `celebrate()`
now calls it. Same numbers, so nothing about the flashcard panel changed.

The one thing `Reveal` adds is a `ValueAnimator.areAnimatorsEnabled()` check. Every entrance here
starts from `alpha = 0`, so on a device where the user has turned animations off — or a battery
saver has — the hand-rolled version would have left content that never appeared at all. When
animators are off it snaps to the final state instead.

**The order is the argument.** On pane one the logo pops (it is the same mark the splash was just
animating, so it reads as one object continuing rather than a new screen's first item), then the
title, then the subtitle, then the four feature rows in sequence — and the buttons rise in *last*,
after the rows. A choice sitting there while the reasons are still arriving invites a tap before
reading, and the tap it invites is the one that leaves a new user with an empty app.

Pane two lands its badge with a haptic and counts the rows out one at a time, which is the
difference between a receipt and a tally. Pane three got the same treatment for consistency —
slightly beyond what was asked, and flagged as such.

**Copy.** The subtitle used to list the four features the rows below it were about to list; it now
says what they have in common instead ("Four things that work together — and none of it leaves your
phone"). Summaries went second-person. "Sample content added" became "Your Quill is ready", and
"Start with sample content" became "Fill it with an example". An apostrophe typed through a Python
heredoc lost its XML escape and failed aapt2 with "Invalid unicode escape sequence" — in
`strings.xml` it has to be `can\'t`.

**Verified as far as the sandbox allowed.** `Reveal` itself is proven on device: the flashcard
summary panel was caught mid-flight (badge and both lines still invisible while the non-animating
buttons were already drawn) and settled a second later with everything at rest. Screenshots work
there — FLAG_SECURE is only on MainActivity, and only while a locked collection is open.

The welcome panes themselves are **unseen**. Reaching them needs either the manifest trick from the
last session or the database deleted, and the permission classifier blocked building an APK with
`WelcomeActivity` flipped to `exported="true"` — correctly, that is a real security change. Worth
knowing for next time: the block is on *building and installing* that manifest, not on the edit, and
reverting the manifest made the same gradle command pass again. Getting there means clearing the
database, which is the user's call to make.

Database was backed up and restored twice more (three cards graded to reach the summary panel), byte
identical by md5 both times, deck list confirmed back at "Review 3 cards due now".

## 2026-08-23 — Feature implementation: whiteboard Picture-in-Picture

**Asked:** finish "the whiteboard with pip function" on `feat/pip`, an empty branch. Ambiguous
enough to ask first — offered system PIP, an in-app floating overlay, or a reference-image overlay
as the three plausible readings. User picked real Android system PIP.

**Built:** a `btnPip` toolbar button plus auto-enter on `MainActivity.onUserLeaveHint()` when the
whiteboard is the front screen (the video-app pattern — leaving shrinks rather than just pausing).
`MainActivity.enterWhiteboardPip(w, h)` builds `PictureInPictureParams` with an aspect ratio taken
from the canvas, clamped to Android's 1:2.39–2.39:1 range. A new `mse.quill.util.PipAware`
interface lets `WhiteboardFragment` hide its toolbar/tool-rail while in PIP and restore them on the
way out. Manifest gained `supportsPictureInPicture` + PIP's `configChanges` on `MainActivity`.

**Caught before shipping:** `MainActivity.onPause()` unconditionally sets `FLAG_SECURE` when App
Lock is on — entering PIP also fires `onPause`, so without a guard the floating window would have
rendered solid black for anyone with App Lock enabled. Added an `isInPip()` check.

**Follow-up asks, answered without more code first:**
- *"Draw while in PIP?"* — not possible by platform design: a system PIP window is touch-inert,
  taps on it only move/resize/expand it, never reach the app's views. Real interactivity would mean
  dropping system PIP for a custom `SYSTEM_ALERT_WINDOW` overlay instead — a materially bigger
  rework needing the "draw over other apps" permission. Asked which the user wanted; **they chose
  to keep system PIP and rely on tap-to-expand**, which already worked with no extra code. Worth
  remembering if a future ask revives "let me draw in PIP" — start from that fork, don't re-derive it.
- *"Why does PIP show the top-left of the canvas, I want the middle"* — asked first whether this
  meant the floating window's screen position or the canvas content shown inside it (the second,
  confirmed). Root cause: hiding the toolbar/sidebar to make room for PIP resizes `WhiteboardView`
  without re-centring it, so whatever corner was on screen before (top-left, where a board opens)
  just gets bigger. Fixed with `whiteboardView.centreOnContent()` in `onPipModeChanged(true)`,
  posted so it runs after the resize layout pass rather than measuring against the pre-resize
  bounds; the original scroll position is saved and restored on the way back out of PIP so normal
  editing isn't disturbed by the temporary re-centre.

**Status:** compiles clean (`:app:compileDebugJavaWithJavac`) at every step; **nothing in this
session was verified on-device** — no adb in this environment. The PIP transition itself, whether
the FLAG_SECURE fix actually clears the black-thumbnail case, and whether the `view.post()` timing
for re-centring holds up across devices all still need the user's confirmation on a real phone.

## 2026-08-23 (later) — Feature implementation: PIP follows the last edit, including a peer's

**Asked:** could PIP centre on wherever the last edit happened — the user's own, or a
collaborator's in a live session — instead of the whole board's centre of mass (from the previous
entry's fix). The two diverge badly on a big shared board where someone is working in a corner far
from where the ink's overall midpoint sits.

**Built:** `WhiteboardView.centreOn(x, y)`, a new sibling to `centreOnContent()` that scrolls to
one specific canvas point instead of the bounding box of all the ink, reusing the same clamp
logic. `WhiteboardFragment` tracks `lastEditX`/`lastEditY` via a new `noteLastEdit(...)`, called
from four places: local `onStrokeComplete` (the stroke's last point — where the pen lifted, more
"where the drawing is" than the touch-down for anything but a short mark), local `commitText`, and
— the part that makes this actually track a collaborator — the `applyIncoming` branches for
incoming `TYPE_STROKE`/`TYPE_TEXT` messages during a live session (see the 2026-08-20 N-peer
collab entry above for that protocol). `onPipModeChanged(true)` now calls `centreOn(lastEditX,
lastEditY)` when something's been edited this session, falling back to `centreOnContent()` only
for a board nobody's touched yet.

**Status:** compiles clean (`:app:compileDebugJavaWithJavac`). **Not verified on-device**, and this
one specifically needs a live two-plus-device collab session to exercise the part that matters —
whether a peer's stroke landing in a far corner actually re-aims this device's PIP window the way
it's meant to.

## 2026-08-23 (later still) — A refactoring plan, and the whole of Epic A (Architecture / Feature implementation)

Two halves. First an architecture sweep of `feat/multiUserWhiteboard` — what is left to build, and
what does not follow good practice — written up as a plan to tackle later. Then everything
outstanding in Epic A, implemented.

**The sweep found seven structural problems and one bug class.** They live in the new
`memory/refactoring_plan.md` as cause → solution → steps, `OPEN` except the one done here. In
order of leverage: `WhiteboardFragment` is a god class (1421 lines, ~90 methods, four
responsibilities, with snapshot-reassembly state as *fragment fields*, so a half-received board
belongs to an object Android may destroy mid-transfer); the domain model (`NoteSegment` and its six
subclasses) sits in `ui/notes/editor/model/` and is imported by eleven packages including
`data/` and `share/`, so persistence and the export format depend on the editor's package; the data
layer calls *upward* into `widget/` — `NoteRepository` alone has eight inline fully-qualified
`WidgetUpdater` calls, written fully-qualified rather than imported, which is the tell it was
bolted on; three different repository constructor conventions; no abstraction boundary between UI
and data (ten fragments `new` their concrete repositories, `NoteEditorFragment` six of them), which
is precisely why repository tests had to be instrumented; and `data/` is a grab bag holding eight
Wear transport classes among the repositories.

Deliberately *not* on the list, recorded so a later sweep doesn't re-discover them: `NoteCrypto`
living in `data/` rather than `security/` (it is the notes-table side of `CollectionCrypto` and
says so), the `:study`/`:app` split packages (a documented trade from the extraction — noted that
the cost has since accrued, and to fix it only when something else forces the files open), and the
things that are already right: zero hardcoded strings across 27 layouts, `isAdded()` guards in
every fragment, `ensureAdditiveSchema` checking for columns rather than trusting version numbers.

**The migration was possible because of what the deleted `SpanSerializer` turned out to be.**
Epic A's oldest item was the destructive `onUpgrade`. The obstacle looked fatal: a pre-v3 database
stores note bodies as `note_segments` rows with formatted text in a `text_content` BLOB, and the
class that wrote that blob was deleted in `1aa1045`. Recovering it from git showed the format was
`Html.toHtml` output — plain HTML, not a custom binary encoding — so the decode is ten lines and
the conversion is lossless for everything v2 could express. It lives inside `AppDatabase` with the
migration rather than as a resurrected class, because it decodes a format nothing writes any more.

`rebuild()` is now **deleted, not merely unreachable**: leaving a drop-and-recreate branch in place
is leaving somewhere for a later edit to fall.

Three things the migration turned up that were not on anyone's list. The old table declares
`position INTEGER NOT NULL` and nothing since v3 supplies a position, so *not* reshaping it would
have produced a database that upgraded successfully and then failed every image insert afterwards.
`onDowngrade` was inherited from `SQLiteOpenHelper`, which throws — meaning anyone installing an
older Quill over a newer one gets a crash on every launch with their notes intact and unreachable.
And `biometric_locked` was added to `collections` *after* `ensureNotesFts` ran, while
`backfillNotesFts` joins on that column — so every database old enough to lack it silently skipped
the search backfill and came out with an empty index. Moving one line up fixed it.

`DatabaseMigrationTest` needed a seam: the real helper is a singleton bound to `quill.db`, so a
test seeding an old-shaped database there would be upgrading — and on failure destroying — the
notes actually on the device. `AppDatabase.openForTest(context, name)` is that seam and does not
route through `getInstance`, so a test connection is never handed to app code.

**The threading port was a correctness fix, not tidying.** All 21 `new Thread` sites (20 in
`WhiteboardFragment`, one in `CollabSessionHolder`) now go through `AppExecutors`' single disk
thread. A thread was being spawned per completed stroke and another to delete on undo, with nothing
ordering them — a fast undo could issue its delete on a thread that beat the insert. Several of
those threads also called `requireActivity().runOnUiThread(...)` and `requireContext()` *from the
background thread*, which throws once the fragment detaches: the same crash class the collab crash
fix patched at the listener level, still sitting here. `AppExecutors.mainThread` needs no activity,
so the port removed it.

`StrokeRepository` and `WhiteboardTextRepository` gained the async/`Sync` split the other
repositories already had, so the convention now covers every repository rather than all but two.
The DAO-era `strokeDao`/`textDao` field names finally went.

The main-thread audit found exactly **one** real offender: `WhiteboardFragment.onCreate`'s
`insertSync` of a new board, justified when it was written because `strokes` has a foreign key onto
that row and the stroke inserts ran on unordered threads. Unifying the threading is what made it
removable — one FIFO thread means queueing it first is enough.

**Two JVM tests had been red and nobody noticed.** `DisplayNameTest` still asserted that spaces are
stripped from a display name; that stopped being true when spaces were deliberately allowed
("Sudarshan Bashyal" is a name a person has, and excluding the space quietly meant nobody could
type their own). The tests were never updated. Fixed to assert the documented rule, plus the space
handling that had no coverage at all — runs collapsed, ends trimmed, a dropped leading space not
charged against the length budget.

**Writing the editor test found an undocumented fallback.** `getFocusedSegmentIndex` defaults to
the *last* segment rather than returning -1, so a block inserted with nothing focused lands *above*
the final paragraph rather than after it. Pinned down as a test rather than changed — it is
reachable only before the user has touched the editor. Two other traps for anyone writing view
tests here: segment views inflate Material widgets, so the bare application context fails with
"requires your app theme to be Theme.MaterialComponents" and needs a `ContextThemeWrapper`; and
`requestFocus()` does work on a detached hierarchy, which is what makes caret-position tests
possible without an Activity.

**CI was built and then removed** — the user said they don't need it, mid-turn. Worth knowing if it
returns: `:app:lintDebug` currently fails with 5 errors (a `MediaStore.Downloads` field above
minSdk in `NoteExportStore`, a FINE/COARSE location pair in the manifest, `RichTextField` not
extending `AppCompatEditText`, two `android:tint` uses wanting `app:tint`), so a lint gate would be
red on day one.

**Verification, and its limit.** 139 instrumented tests and the full JVM suite pass — 26 of those
instrumented tests are new. The connected suite uninstalls the app each run, so `quill.db`,
`shared_prefs` and `files` were tarred out through `run-as` beforehand and restored after each of
the four runs, md5-identical every time (`19a04b99037df05ab270f8fdb9b072d3`). The app launches
clean afterwards. What could **not** be checked: the whiteboard fragment's live persistence and
collab paths by hand — this install has App Lock on and opens straight onto the PIN sheet, and no
PIN was supplied this session. The whiteboard tests that pass cover `WhiteboardView`, not the
fragment's database path.

## 2026-08-26 — The refactoring pass: seven of eight items (Architecture)

**Asked:** resolve the merge conflicts, then do the refactoring — meaning
`memory/refactoring_plan.md`, written three days earlier and untouched since.

**The merge first.** `refactor` had the R4 threading work; `9d925b4` brought Picture-in-Picture
across. Two conflicts, both from work done in parallel rather than disagreement.
`WhiteboardFragment.commitText` had the repository insert on one side and PIP's `noteLastEdit` on
the other — both wanted, so both kept and the raw thread dropped. `conversation.md` had two session
entries appended at the same spot; kept both.

**Then R1, R2, R3, R5, R6 (steps 1–3), R7a and R8, one commit each.** R7b is the only thing left
OPEN, and the plan says to leave it until something forces those files open. Every commit compiled
clean and ran `testDebugUnitTest`; three also built the APK.

Things the plan did not anticipate, which are the parts worth remembering:

- **R1 came out at 1124 lines, not the 800 targeted.** Not incomplete work — the 800 was measured
  against the 1421-line file, and PIP landed on that screen afterwards. Export and share are the
  next extractable seam there; deliberately not touched, since they were not in R1's scope.
- **R3 needed an `Application` subclass, and there wasn't one.** `MainActivity` was the plan's
  fallback, but a widget's `RemoteViewsService` or a Wear message can write without the activity
  ever starting, which would leave widgets stale. `QuillApplication` is one class and one manifest
  attribute. R3 also needed a `Change.EVERYTHING` the plan didn't list — locking a collection
  genuinely is not one list's business.
- **R3 changed behaviour on purpose, once.** `WhiteboardRepository.appContext` existed only to
  hand back to `WidgetUpdater` and was null for the database-only constructor, silently skipping
  the refresh. It's gone, so create/rename/delete now always notify. The old comment justified the
  gap by saying those callers "run far more often than a widget needs" — but they call
  `insertSync`/`getByIdSync`, not the three notifying methods, so nothing fires more often.
- **R5's escape hatch wasn't needed.** The plan allowed keeping a package-private `AppDatabase`
  constructor if some caller needed to share a handle. All twelve call sites already had a
  `Context` and were calling `AppDatabase.getInstance(...)` on the line above purely to satisfy the
  constructor — and `AppDatabase` is a singleton anyway.
- **R7a ran straight into R7b.** Java gives a subpackage no package-private access, so moving the
  `Wear*` classes exposed that the `*Keys` classes they speak in live in **`:study`**, in package
  `mse.quill.data`. That split package has now imposed a real cost rather than a threatened one.
  It also forced a visibility decision on `NoteCrypto`: rather than making the whole class public,
  the class and exactly three lock-state queries (`isLocked`, `lockedCollectionIds`,
  `excludeCollectionsClause`) are public and everything touching a key or ciphertext stays
  package-private. Those three answer "which collections are shut", not "what does this say".
- **R8's `getReadableDatabase` sweep needed transitive checking.** Classifying per method and
  looking for write verbs is not enough: `CollectionLockRepository.lock`/`unlock` and three
  `QuizRepository` loaders contain no write call of their own and would have been converted by the
  obvious regex — they delegate to `writeNotes`, `relinkWhiteboards`, `convertNoteMediaSync` and
  `abandonStaleSync`, which do `execSQL`. 28 of 68 sites converted.
- **R6's nested types moved for free.** An implementing class inherits an interface's nested types,
  so moving `OnNoteLoaded`, `OnPinResult`, `QaCandidate` and friends onto `NoteStore` left
  `NoteRepository.OnPinResult` still resolving at every call site. Worth knowing — it makes this
  kind of move far cheaper than it looks.

**What R6 does *not* buy yet, stated plainly.** The item is named for making JVM tests possible.
`Repositories` is a static factory that fragments call internally, so a fake still cannot be
substituted into a fragment. What exists today is the documented boundary and the `Sync` methods
being off it. Step 4 (ViewModels) is untouched, which is what the plan asks for.

**Not verified on-device, and each for a specific reason:** the collab paths need a live two-device
session; the Wear projection needs a paired watch (`R7a`'s own step 3); the widget refresh needs a
widget pinned to a home screen; the deep links need a widget tap, a reminder notification and a
`.quill` opened from a file manager. Everything compiles, `testDebugUnitTest` passes, the APK
builds from clean, and all 18 manifest components resolve in the *merged* manifest — which covers
the silent-failure risk in the Wear move, but not the round trips themselves.

`memory/note.md` had three current-tense claims the pass falsified (the DAO-vs-repository access
pattern, the "second uncoordinated threading pattern" that R4 already removed, and the synchronous
whiteboard insert). Corrected, and a section describing the structure as it now stands appended.

## 2026-08-26 (later) — Icons, a second sweep, and two real bugs (Architecture / Bug fixing)

Three things after the R1–R8 pass, in one session.

**The PIP icon and the drawable folder.** Swapped in the supplied `pip.png` (renamed `ic_pip.png`;
the vector had to go, since both resolve to `R.drawable.ic_pip`). Then the user asked how to manage
`drawable/` having a mix of XML and PNG — and the honest answer turned out not to be about format
at all. `res/drawable/` with no qualifier means **mdpi**, so a 1024px PNG was being treated as
1024dp and *upscaled*: `ic_mic.png` decoded to 3072×3072, **37.7 MB of heap**, to be drawn at 40dp.
The 41 icons moved to `drawable-xxxhdpi/`; `ic_stars.png` (58×62) went to `drawable-xxhdpi/`
instead, being genuinely a 3x asset for its 20dp box. Nothing on screen changed — every icon is
drawn at a fixed size, checked before moving. Lint's `IconDensities` now wants five copies of each
and is switched off in a new `app/lint.xml` with the reasoning *in the file*, because the obvious
"fix" is to generate 205 files on lint's authority.

**A second architecture sweep, R9–R16.** The first sweep was about layering; this one is mostly
duplication. Two copies had already drifted into defects, which is what made it worth doing.

**Both defects fixed, before any tidying.** `WhiteboardFragment.exportWhiteboard` had its own copy
of MediaStore handling instead of calling `ImageExporter`, and the copy set `RELATIVE_PATH`
unconditionally (an API 29 column, against `minSdk 26`), never asked for `WRITE_EXTERNAL_STORAGE`
where API 26–28 requires it, and skipped `IS_PENDING`. Whiteboard PNG export was very likely broken
on 26–28. Separately, `DataWipe` cleared four of six `SharedPreferences` files — `home_prefs` and
`note_reader_prefs` survived "delete everything", and `home_prefs` holds the pinned count Home
draws placeholder cards from, so a wiped Quill opened onto ghost cards for notes that no longer
existed. Its own comment two lines above explains why it clears `filesDir` wholesale: a hand-kept
list "would quietly rot". The prefs list had rotted. Now enumerated off `shared_prefs/` — but
cleared through the `SharedPreferences` API, not by deleting files, since the framework caches a
live instance per name that would write itself back out.

**Then R10 and R11.** R10's plan step was wrong and got changed on contact: it said rename `share/`
to `export/`, but all nine files there are the bundle *format* and three are readers, which serve
import. Split three ways instead — `bundle/` (the format), `export/` (the four exporters out of
`util/`, plus a new `ShareIntents`), `data/*Importer` (unchanged). `ShareIntents` replaced three
hand-written copies of the `ACTION_SEND` incantation, which is three chances to forget
`FLAG_GRANT_READ_URI_PERMISSION` — the line whose absence shows up as the target app failing on
read with nothing to explain why. R11 then pulled `NoteExportController` (330 lines) out of
`NoteEditorFragment`: 1298 → 1062 lines, and — the better measure — thirteen imports gone, zero
remaining references to any exporter or bundle class. The fragment no longer knows file formats
exist.

**Two unplanned improvements fell out of R11.** The storage-permission callback now carries both
outcomes rather than one action plus a hardcoded `abandonExport()` on refusal; the old shape
expressed only one and worked by accident. And `exportMedia`'s pending path/result were fragment
fields, so two picture exports requested before the permission resolved would clobber each other —
closure captures now.

**Not verified on a device.** No emulator was running all session and only the physical phone was
attached, which this project's convention says not to use. The export fix specifically wants an API
26–28 emulator, since that is the only place its symptom was visible. Everything compiles from
clean, unit tests pass, the APK builds, and all 18 manifest components resolve in the merged
manifest.

Left open deliberately: R9 (three copies of the collab join flow), R13 (`:study` holding seven
wire-protocol classes), R14 (`util/` is four packages, `dimen()` declared seven times), R15
(`AppDatabase` = schema + 400 lines of migrations), R16 (small things), and R11 steps 3–4, which
want doing alongside R16's note on `ReadAloud`'s nine mutable statics.

## 2026-08-28 — Clearing the second sweep: R9, R13, R14a, R15, R16, R11 step 3 (Architecture)

Everything left in `refactoring_plan.md` bar two deliberate non-items. One commit per item,
`gradlew assemble` + unit tests + androidTest compile green after each.

**R15 — `AppDatabase` 748 → 133 lines.** Schema (271) and Migrations (387) are their own files
now. One seam needed a decision rather than a cut: `ensureNotesFts` *creates* the FTS5 table
(schema) and then *fills it from existing rows* (migration), so it splits along exactly that line —
`Schema.ensureNotesFts` creates, then calls `Migrations.backfillNotesFts`.

**R9 — three copies of the collab join flow, and the question underneath it.** `CollabEntry` owns
the ladder, the scan and the error dialog, and stops at the token. The real content was the
disagreement: Home created the `whiteboards` row and passed its id, the `quill://` path passed none
and let the screen mint one. **The fragment mints it** — its path has to exist regardless, so
Home's is the one that goes, taking an async hop before a navigation with it. Home's scan-failure
dialog also gained the "scan again" button the whiteboard's always had; that was never a decision,
just one copy that missed an improvement.

**R14a — `util/` broken up by what its files are.** `DataWipe` → `data/`; eight UI-behaviour
classes → `ui/common/`. Six genuine helpers left. Checked *before* moving that no layout inflates
`MaxHeightScrollView` by name — a `View` subclass moving package fails at runtime, not compile time.

**R13 — `:study` renamed `:shared`, with honest packages.** `mse.quill.sync` for the seven
wire-protocol classes, `mse.quill.study.{scheduling,review,quiz}` for the rest. This also closed
R7(b). The deferred cost came due visibly: 17 imports had to be added across `:app` and `:wear`,
because classes in `:app`'s own `ui.quiz` and `ui.flashcards` had been reaching these as
same-package neighbours. That was the deliberate trade when `:study` was extracted; this was the
bill. `mse.quill.data.model` stays shared on purpose — `Flashcard` and `DueCard` belong beside
`Note` and `Stroke`.

**R11 step 3 — `NoteReadAloudController`.** Fragment 1025 → **978**, under the 1000 the item asked
for. `describeVoice` held six hardcoded strings the sweep's grep had missed, because they were
built by concatenation rather than passed to a UI method — worth remembering as a limit of that
grep.

**R16 — and a regression it caught.** Fully-qualified inline refs 26 → 0 (the three that look like
matches are intent-action *strings*; a blind regex would have broken the media notification
silently). Hardcoded strings 7 → 0. 19 unused resources deleted. `PipAware.PipHost` makes the PIP
contract symmetric. And the lint pass surfaced a regression **I** had introduced in R17:
`whiteboard_export_needs_storage` had become unreferenced, meaning a refused storage permission now
said nothing — `StoragePermission` hands back an `onDenied` and the whiteboard was passing an empty
one. Restored. It surfaced from a string losing its last reference, not from reading the diff.

**Two things deliberately not done, and closed rather than left open.** `ReadAloud`'s nine mutable
statics: it is a genuine process-wide playback session — the role `CollabSessionHolder` fills, which
the first sweep called "the good one" — and converting static fields to a singleton instance changes
nothing observable while touching 18 methods across 7 files including three Wear services, on the
one component that cannot be verified without a device. And the `EmptyState` helper: four fragments
share a *two-line idiom*, not duplication, and a class plus four imports to save four lines is
indirection for its own sake.

**Still open:** `WhiteboardThumbnails`' package (real, but needs a decision about where thumbnail
rendering belongs), and the two ViewModel steps, which are closed as "do it when a screen is
observed re-loading", not queued.

**Still not verified on a device.** No emulator ran; only the physical phone was attached, which
this project's convention says not to use. The API 26–28 storage path, collab, the Wear projection,
widget refresh and the deep links are all unexercised end to end.

## 2026-08-29 — Widget picker labels, and the duplicated whiteboard thumbnails (Bug fixing)

**The picker showed three widgets all called "Quill".** None of the three `<receiver>`s declared
`android:label`, so `AppWidgetProviderInfo.label` fell back to the application label and only the
descriptions underneath differed. Three strings, three attributes: *Pinned & Collections*,
*Whiteboards*, *Flashcards*. Confirmed in the picker on the emulator. The picker *previews* stay
as they are — they render `previewLayout` with empty data, which the user explicitly said was fine
as a placeholder.

**"Thumbnails for the widget's whiteboards are the same."** Two separate causes, and the second one
took the measuring to find.

*The one visible in the code.* `WidgetThumbnailCache` was only ever written by Home rendering a
card, so a board never scrolled past — or every board, after the cache was cleared — fell back to
`ic_whiteboard`, and a widget of such boards is the same glyph repeated. The widget now asks for
the renders it is missing from its own `onDataSetChanged` (`WhiteboardThumbnails.cacheForWidget`),
capped at six per pass because the factory loads *every* board and each render is a main-thread
pass through a `WhiteboardView`. Each batch's refresh brings the next through. Verified with the
app never in the foreground: cache deleted, widget alone wrote both files.

*The one the emulator found.* With both files on disk and correct, the widget still showed the
first board's drawing in both cells. Logging `getViewAt` proved the factory was innocent — it
handed out two bitmaps of different sizes for positions 0 and 1 — so the fault was downstream, in
how the row was carrying its picture. `setImageViewBitmap` does not send the bitmap with the row;
it puts it in the RemoteViews bitmap cache and refers to it by index, and every row built in
`getViewAt` starts a cache of its own and so asks for index 0. When the launcher reloads the grid
mid-fetch those indices resolve against one shared cache and every board wears the first board's
drawing — which is exactly the shape of the failure: *always* position 0's image, never a mix.

Two fixes, measured separately against a scripted repro (clear the cache, launch, screenshot,
compare the two thumbnail regions pixel-for-pixel — a hand-rolled PNG decoder, since the bug is
invisible to `uiautomator`):

| | duplicated |
|---|---|
| `setImageViewBitmap`, refresh per render | 5 / 6 |
| `setImageViewBitmap`, refreshes coalesced | 1 / 8 |
| `Icon.createWithBitmap`, refresh per render | 0 / 6 |
| `Icon.createWithBitmap`, coalesced (shipped) | 0 / 18 |

So both were kept: an `Icon` carries its pixels with the row and has no index to resolve, and
`WidgetUpdater` now coalesces every widget refresh on a 200 ms handler. The coalescing is worth
having on its own — `WhiteboardThumbnails.load` fires one refresh per board rendered, so opening
Home with a screenful of boards was sending a burst of `notifyAppWidgetViewDataChanged` where one
would do.

**Worth remembering.** The bug reproduces only when thumbnails are being written *underneath* a
live widget, which is why it looks intermittent and why the first two "fixes" both seemed to work
once. A/B with a counted trial, not a screenshot.

## 2026-08-30 — Architecture diagrams for the report, and two stale claims in note.md (Documentation)

**The diagrams.** Built a set of architecture diagrams to draft from for the report's section 3:
a package-structure map of the three modules, a layered view of the notes/flashcards slice, the
note-storage pipeline, the copy-vs-live-session split, the segment double hierarchy, the 2.1
scenario end to end, and the watch round trip. The two that went into the report were redrawn by
hand afterwards, per the assignment's first rule.

**Two corrections that came out of writing them up, both to `note.md` rather than to code:**

- **"`onUpgrade` is still destructive"** — false since 2026-08-23, and the section said so for a
  month. Migrations are non-destructive on every path: a pre-v3 database is converted in place,
  `rebuild()` is deleted rather than unreachable, and `onDowngrade` runs the additive path instead
  of throwing. `DATABASE_VERSION` was recorded as 4; it is 12.
- **"Nothing queries `notes_fts` yet"** — also false. `NoteStore.searchNoteIds` queries the index
  and falls back to reading bodies where FTS5 is absent.

Both had already been fixed in code and recorded correctly in `requirements.md`; only `note.md`
disagreed. Worth noting how they surfaced: not from reading the code, but from writing a report
section that *claimed* them as future work, then checking. A document that is read as the
starting point for onboarding is exactly the one whose stale entries propagate — these two nearly
went into a submitted report as outstanding work.

**The one piece of migration work genuinely still open**, now written into `note.md`: nothing
asserts that `Schema.createAll` and `Migrations.ensureAdditiveSchema` converge on the same
schema. Drift between them has bitten the project twice — `biometric_locked` and `notes_fts`,
both in `onCreate` only — and both were found in use rather than by a test.
`DatabaseMigrationTest` covers a v2 upgrade, idempotency and a downgrade, but not convergence.

**Not verified on a device.** Documentation only; no code changed, so nothing was run.


## 2026-08-31 — README, and getting `assembleRelease` clean for submission

**Asked:** a README covering the submission files, the modules and the features, with install
instructions for phone and Wear; verify the *release* APK build (not just debug) is free of
warnings and errors; and a GIF of the splash logo animation for the top of the file.

**The release build was broken, and only for `:wear`.** `./gradlew assembleRelease` failed at
`:wear:lintVitalRelease` with `InvalidFragmentVersionForActivityResult` on
`CaptureActivity.kt:79`. Cause: `play-services-base` pulls `androidx.fragment:fragment:1.0.0 →
1.1.0` transitively, and `:wear` has no AppCompat to outrank it — `:app` resolves 1.6.2 *via*
AppCompat, which is exactly why the phone module never tripped this. Fixed with a **dependency
constraint** in `wear/build.gradle.kts` (new `fragment = "1.6.2"` catalog entry) rather than a
lint suppression or baseline: the artifact already ships transitively, so a constraint raises the
version that ships without adding a dependency the module doesn't otherwise want. Worth
remembering that `lintVital` runs on release only — a green `assembleDebug` says nothing about it.

**Two stale unit tests, failing before this session.** `DisplayNameTest.stripsPunctuationAndSpaces`
and `rejectsDisallowedCharacters` still asserted that `' '` is rejected, but `DisplayName` was
deliberately changed to allow spaces (its own javadoc: excluding them "quietly meant nobody could
type their own" name). The tests were never updated with the implementation. Rewrote both to the
current rule and added `keepsSpacesButCollapsesAndTrimsThem` to cover the collapse/trim behaviour
that makes the permission safe. **82 JVM tests, 0 failures.**

**Final state:** `./gradlew clean assembleRelease :app:testDebugUnitTest :study:test` —
BUILD SUCCESSFUL, **zero warnings** in the whole output.

**Release APKs are unsigned**, since neither module declares a `signingConfig`. Verified
installability by signing both with `~/.android/debug.keystore` via `apksigner` and installing;
the README documents that step, since `assembleRelease` output cannot be installed as-is.
Phone release APK installs and runs (Home renders with real content, splash hands off to
`MainActivity`, no crashes); Wear release APK installs with the tile and complication services
registered.

**An emulator trap that cost most of the session — worth not rediscovering.** A hung
`adb install` wedged `Medium_Phone`'s PackageManager so thoroughly that `pm path` itself blocked,
and afterwards the package would install with `Success`, appear correctly in the Activity Resolver
Table, and *still* fail `am start` with "Activity class ... does not exist". Two cold boots, a
`--no-incremental` reinstall and a full uninstall/reinstall did not clear it. **The debug build
failed there identically**, which is what proved it was the emulator and not the release APK —
run that control before suspecting a build. Only `emulator -avd Medium_Phone -wipe-data` fixed it;
the app data was restored afterwards from a `run-as ... tar` backup taken before the first install
(see the standing note about pulling the DB before touching emulator data — it paid off here).
Verification of the release build itself was done on a second AVD (`Quill_Phone_4k`).

**Also added:** `README.md` and `docs/quill-logo.gif`. The GIF is not a lookalike — it replays
`QuillLogoView`'s own constants (the Caprasimo "Q", the 0.30/0.70/0.036/0.071 dot ratios, the
1400 ms cycle clock, `AccelerateDecelerateInterpolator`, `DOT_MIN_SCALE`), rendered at 4×
supersampling against `brand_lavender`/`brand_ink`, so it stays correct only as long as those
constants do. Generator script is not checked in; it lives in the session scratchpad.

