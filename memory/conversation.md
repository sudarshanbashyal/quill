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

**1. Toolbar stayed disabled after leaving a Q&A block.** State refreshed on content *and selection*
change — but not **focus**. Moving from a Q&A field to the trailing text segment often lands the
caret at an offset it already had, and Android fires no selection callback when the value doesn't
change, so nothing ever re-asked which field was focused. Fixed by reporting focus in its own right,
since focus is what decides *whose* capabilities are on display. While there: `focusEnd()` only
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
"play aloud" and "turn into flashcards"; build cards from Q&A blocks that have *both* halves, show
a message when a note has none, and put a typical review algorithm behind a simple right/wrong
design.

**The prerequisite nobody asked for.** Card→block linking was already designed (Epic D's
`source_segment_id`) and already assumed to be unblocked, because segment ids became stable back in
July. They're stable *within a session* — `BaseSegmentView` mints them — but the Q&A fence didn't
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
*destination* ids — that's what makes tab switching pop back to the start destination instead of
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
every `onViewCreated`. For a note created *during* the session there is no id in the arguments, and
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
the *text* block — of which there is none — and insets its own background (6dp top and bottom, plus
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

**Departure from the plan in note.md**: quizzes read Q&A blocks *directly* rather than the
`flashcards` table. Sharing the rows would have meant "Make quiz" silently generating flashcards as
a side effect, and a quiz's history depending on whether its deck had since been deleted. What the
two features share is the *rule* — `FlashcardRepository.reviewableQa` — not the storage.

**Schema v5** (additive from v4): `quizzes` + `quiz_attempts`. Two columns that weren't in the
sketched shape earned their place: `total` per attempt (a note's block count moves, so "2 / 6" only
means something next to that day's 6) and `answered` (2/12 having answered three questions and 2/12
having answered twelve are not the same afternoon). `answered` was added mid-implementation after a
stub method that returned a hardcoded 0 made the gap obvious — the row wanted to say "Abandoned
after 4 of 12" and nothing stored the 4.

**The attempt row is written at start**, so leaving is recorded rather than rewarded. Normal exits
mark it abandoned on the way out; a killed process leaves it in progress, and a sweep on the next
load retires it — staleness is *computable* here rather than arbitrary, since a quiz can't outlive
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
note with one Q&A block. Letting all six questions time out was the *fastest* way to reach the
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
  (solid = answered) as well as by the ring on the current one, so the current *blank* question and
  the current *answered* one don't look the same.

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
`font/caprasimo_regular.ttf`, OFL in `licenses/`) and derives *everything else* from
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
(logged `textSize=667.5`, `getTextBounds` → 502×563) — the device simply does not *draw* glyphs
above ~272px at the requested size, even though `getTextBounds` reports them honestly. Harmless at
the shipped 96dp, but don't trust `QuillLogoView` (or any large `drawText`) past ~250px without
checking. The 229px-tall render it did produce still beat the 84px glyph in `logo.png`, so the
launcher icons were regenerated from *that* and are now downscaled rather than upscaled.

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
  title pulls the date up and the *contents* sit at a different height inside equal-height cards.
  A weighted spacer pins the tag row to the bottom so tagged and untagged cards agree.
- **Pinned cards get neutral (white/dark) tag chips** via a new `TagChipView.renderNeutral` —
  those cards are a pastel fill, and a tinted chip on a tinted card is colour on colour. Grey note
  rows keep the tag's own colour.
- **Spacing.** New `list_item_gutter` (8dp) is set as each item view's own margin *and* as the
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
autosave arriving *while* creation was in flight `return` outright — so the save `onPause` fires on
the way out was dropped, and with it everything typed since creation started. There was a second,
quieter half: even when it did save, `createNote` and the follow-up `saveNote` were two separate
disk tasks with a main-thread hop between them, so `HomeFragment.onResume`'s `loadNotes` could slot
into the queue *between* them and render the note without its body.

Both go away by **minting the id on the main thread**: `NoteRepository.createNote` now takes the id
(`NoteRepository.newNoteId()`) instead of generating one, `noteId` is valid immediately, and the
insert and the save are enqueued back-to-back on the single disk thread before anything else can
read. The `AtomicBoolean` and `OnNoteCreated` are gone.

**Pinned cards** went back to `setMaxLines(2)` from `setLines(2)` — reserving the second line left
short titles floating above a gap before their date. The fixed card height already keeps the row
even, so the title doesn't have to hold the shape too.

**The bottom bar was charging twice for the gesture inset.** `BottomNavigationView` pads itself by
the bottom system-window inset, and `MainActivity` was *also* padding the root by it — so the bar
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

**The data loss, found while testing the crash.** Rapid open/back had *soft-deleted three seeded
notes*. `loadNote` is async, so a note opened and left before the read returns has an empty title
and no segments — `autoSave`'s `hasContent` is false, and it takes the "user emptied this note"
branch and calls `deleteNote`. A new `contentLoaded` flag (false from when an existing note's id is
known until its read lands, true for a brand-new note) makes `autoSave` a no-op in that window.
Empty fields there mean "not read yet", not "emptied".

**Verified**: 4 runs × 3 rapid open/back cycles → 0 FATALs, 9 alive notes, 0 deleted, 0 emptied.
The identical hammering before the fix gave 1 FATAL and 3 deleted notes.

**Method worth reusing:** timing bugs need input chained inside a *single* `adb shell` call —
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
so the sheet measured 56dp short of the bottom. Charging the same offset to the *header's*
`layout_marginBottom` shortens `totalLength` for real, and the sheet now reaches 2190 exactly.

Worth remembering generally: **a negative margin on a weighted LinearLayout child positions but
does not measure.** Put it on the sibling above instead.

Home's RecyclerView across the whole session: 296dp → 336dp (inset double-count) → 344dp (56dp
bar) → **400dp** (this fix), +35%. The bar itself was only ever part of it.

## 2026-08-02 (same session) — Status bar takes the colour of the screen under it

`MainActivity` was padding its root by the *top* inset, which pushed every screen below the status
bar and left the strip behind the clock showing the window background — white, on Home's purple
header as much as anywhere. The root now applies only the side insets, and each screen hands the
top inset to the view whose paint should run up behind the bar, via a new
`util/WindowInsetsUtils.applyTopInset(View)` (captures the layout's own paddingTop once, so
re-dispatched insets don't compound).

- **Home** → the gradient header (`@+id/home_header`, new id), *not* the root: the root is a
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
its content sits *under* that minimum — so adding the status-bar inset as padding didn't make the
header taller, it pushed the contents down inside a fixed-height box, sliding the subtitle under
the content sheet that overlaps the header's bottom 56dp. `applyTopInset` now grows the view's
`minimumHeight` by the inset as well as its padding. Rule of thumb: **padding a view for an inset
only moves its contents unless the view is free to grow** — check `minHeight` and fixed heights.

**One registration instead of nine call sites.** `MainActivity.applyTopInsetToEveryScreen()`
registers a single `FragmentManager.FragmentLifecycleCallbacks` (with `recursive = true`, because
the screens live in the nav host's *child* fragment manager) that applies the inset in
`onFragmentViewCreated`. Default target is the fragment's own root; a screen needing somewhere else
implements `WindowInsetsUtils.TopInsetHost` and returns the view — only Home (gradient header) and
the note editor (toolbar, since `KeyboardInsetsHandler` owns the root's listener) do. New screens
now get this for free rather than having to remember a call.

Ordering that makes it work: `onFragmentViewCreated` is dispatched *after* the fragment's own
`onViewCreated`, so the editor's `KeyboardInsetsHandler.attach(root, …)` has already run — and
since the editor's target is the toolbar, neither clobbers the other. `NavHostFragment` and
`DialogFragment` are skipped.

**Verified**: Home purple with the subtitle clear of the sheet; editor, flashcards and quizzes
white with their headers clear of the clock.
