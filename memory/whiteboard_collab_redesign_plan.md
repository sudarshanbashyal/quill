# Whiteboard collab: session-persistence + multi-user redesign — implementation plan

Status as of 2026-08-20: crash fix landed on `fix/whiteboard` (see "Crash fix" below,
already shipped — this doc is the *next* piece of work, not a description of what's done).
Everything else in this file is unimplemented, planned only.

## Context

`WhiteboardFragment` currently owns the live collab session directly:
`collabSession` / `isCollabHost` are Fragment fields, and `onDestroyView` unconditionally
calls `endCollabSession()`. That means leaving the whiteboard screen for *any* reason
(back nav, rotation that recreates the fragment, backgrounding) tears the session down —
there's no way today for a session to survive the screen that started it. The user wants:
a session to persist until the host explicitly ends it or a user explicitly leaves — not
merely because the whiteboard fragment view was destroyed — and the whole thing needs to
scale past 2 devices in the next iteration.

Relevant files: `app/src/main/java/mse/quill/collab/CollabSession.java`,
`CollabMessage.java`, `mse/quill/ui/whiteboard/WhiteboardFragment.java` (search
`// ── Live collaboration (Epic C) ──`), `CollabDialogs.java`.

## Crash fix (already landed, for context)

`onPeerConnected` / `onPeerDisconnected` / `onMessage` / `onError` in
`WhiteboardFragment`'s `collabListener` called `requireActivity()` unconditionally. Nearby's
callbacks are async, so if the fragment had already been detached (screen left, activity
finishing) by the time a callback fired, `requireActivity()` threw and crashed the app —
most reliably triggered as the non-host, since leaving the whiteboard is the common "quit"
action and calls `endCollabSession()` → `collabSession.stop()` → async `onDisconnected`.
Fixed by adding `if (!isAdded()) return;` guards before every `requireActivity()` call in
that listener. This is a narrow fix — it does not change *when* a session ends, only makes
the existing "screen death ends the session" behavior crash-free.

## Goal for this plan

1. A collab session survives fragment destroy/recreate (rotation, back-and-forth nav).
2. It ends only on: host taps "End session" (kills it for everyone), a joiner taps "Leave"
   (removes just that joiner), or a real connection loss (distinguishable from the above two
   in the UI).
3. Works with more than one joiner (host + N).

## Step 1 — Move session ownership into a bound Service

- New `mse.quill.collab.CollabConnectionService` (foreground service; Nearby Connections is
  throttled/killed in the background otherwise, and a persistent notification is the honest
  way to tell the user "you are still connected to another device").
- Owns: the `CollabSession`, `isCollabHost`, current peer set, and the last-known board
  state needed to answer a rejoining Fragment's "what did I miss" question.
- Exposes a bound interface (`LocalBinder` pattern) with: `hostSession()`, `joinSession(token)`,
  `endSession()` (host-only, explicit), `leaveSession()` (joiner-only, explicit),
  `isSessionActive()`, `addListener`/`removeListener` (Fragment attaches/detaches its UI
  callback here instead of owning `CollabSession.Listener` itself).
- `WhiteboardFragment.onStart()` binds; `onStop()`/`onDestroyView()` just unbinds (removes
  its listener) — **must not** call anything that stops the session. Only the explicit
  "End session" / "Leave" dialog actions (already wired to buttons in
  `showCollabEntry()`/`CollabDialogs`) call `endSession()`/`leaveSession()`.
- Re-entering the whiteboard screen while a session is alive: Fragment binds, asks the
  service for current state, applies it like a snapshot (reuse `applySnapshot`-style logic)
  rather than re-hosting or re-joining.
- `onTaskRemoved` in the service: treat like an explicit end/leave (send the message below)
  rather than letting Nearby produce a bare disconnect on the peer's side.

## Step 2 — Distinguish explicit end/leave from real disconnects (protocol change)

`CollabMessage` currently has `TYPE_SNAPSHOT/STROKE/TEXT/RETRACT/CLEAR`. Add two more:

- `TYPE_HOST_ENDED` — host broadcasts this *before* tearing down when it explicitly ends the
  session. Joiners show "Host ended the session" and tear down without ambiguity.
- `TYPE_PEER_LEFT` (carries the leaving endpoint's id, once peers are keyed by id — see Step
  3) — a joiner sends this to the host before disconnecting when it explicitly leaves. Host
  drops that one peer, keeps the session alive for everyone else, and forwards a
  "so-and-so left" notice to remaining joiners.
- A bare `onDisconnected` with neither message having preceded it = real connection loss
  (Wi-Fi/Bluetooth drop, peer app killed without hitting `onTaskRemoved` in time) — keep
  today's generic "Disconnected" toast for that path only.

Update `CollabSession.Listener` to add `onSessionEndedByHost()` and `onPeerLeft(String
peerId)` alongside the existing `onPeerDisconnected()` (real-loss case), so the Service/UI
layer doesn't have to sniff message types itself — `CollabSession` interprets the protocol
and the layers above just react.

## Step 3 — Generalize `CollabSession` to N peers

Currently: `private String peerEndpointId;` (singular), `send()` targets it directly,
`isConnected()` null-checks it, `host` boolean check in `onConnectionResult` just stops
advertising once *a* connection lands (already tolerates multiple `onConnectionResult`
calls, but nothing tracks them individually after that).

Changes needed:
- Replace `peerEndpointId` with `Map<String, PeerInfo> peers` (id → whatever per-peer state
  you end up wanting, e.g. device label from `ConnectionInfo.getEndpointName()` — currently
  discarded).
- `send(message)` → keep as "send to all peers" (host broadcasting to joiners); add
  `sendTo(peerId, message)` and `sendToAllExcept(peerId, message)` for the relay case below.
- `isConnected()` → `hasAnyPeer()` or similar; UI that currently gates on "am I connected"
  doesn't change much, but anything assuming exactly one peer (e.g. a "connected to X" status
  string in `CollabDialogs`) needs to become "connected to N devices."
- `host` stays advertising-until-first-connect today (`if (host) client.stopAdvertising()`
  in `onConnectionResult`) — for >2 users this has to keep advertising after the first
  joiner connects, so later joiners can still find it. Only stop advertising when the host
  explicitly closes joining (new explicit action — decide whether "start session" always
  stays open to new joiners, or add a host-side "lock session" toggle; the user only asked
  for >2 *users*, not for an explicit lock, so default to "always open until host ends it"
  unless told otherwise).

## Step 4 — Relay gap (silently broken today for a 3rd device)

`Strategy.P2P_STAR` is a star topology: joiners connect only to the host, never to each
other. Today, `WhiteboardFragment.applyIncoming()` applies an incoming STROKE/TEXT/RETRACT
to the local board and re-persists it, but never re-sends it anywhere. With exactly one
joiner this is harmless (the host is the only other party, and joiner→joiner traffic doesn't
exist). With two joiners, a stroke from joiner A reaches the host, gets applied and saved,
but is **never forwarded to joiner B** — B's board silently diverges from the host's.

Fix: on the host side only, after applying an incoming STROKE/TEXT/RETRACT/CLEAR from peer
X, call `session.sendToAllExcept(X, sameMessage)`. `TYPE_SNAPSHOT` stays host→one-joiner-only
(sent on that joiner's connect), doesn't need relaying itself, but a newly-joined 3rd device
still needs the state contributed by joiner A *before* it connected — snapshot already reads
fresh off disk (`strokeDao.getByWhiteboard`/`textDao.getByWhiteboard`), so this is naturally
correct as long as A's strokes were actually persisted (they are, via `applyIncoming`'s
existing `new Thread(() -> strokeDao.insertStroke(...))`).

## Suggested order of work

1. Step 3 (peer map + relay-capable `send`) first — it's the load-bearing data structure
   change and easiest to unit-reason-about in isolation from Service/lifecycle concerns.
2. Step 4 (relay in `applyIncoming`) — small, layers directly on Step 3.
3. Step 2 (protocol messages + `CollabSession.Listener` additions) — also fairly contained.
4. Step 1 (Service extraction) last — the biggest lifecycle/plumbing change, and easiest to
   test once the session logic underneath it is already multi-peer-correct.

## Open questions to resolve with the user before/at start of implementation

- Does "more than 2 users" have an intended cap, or unbounded (practically bounded by
  Nearby's own star-topology limits)?
- Should a host be able to lock a session against new joiners, or does it stay open until
  explicitly ended (this plan defaults to "stays open")?
- Foreground-service notification copy/UX — does the user want to review that before it's
  built, given it's new user-facing surface (a persistent notification while collaborating)?
