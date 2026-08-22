package mse.quill.collab;

import android.content.Context;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import mse.quill.data.AppDatabase;
import mse.quill.data.StrokeRepository;
import mse.quill.data.WhiteboardTextRepository;
import mse.quill.data.model.Stroke;
import mse.quill.data.model.WhiteboardText;

/**
 * Owns the live {@link CollabSession} outside any one {@code WhiteboardFragment} instance, so the
 * session survives that fragment being destroyed and recreated (back nav, rotation) instead of
 * being torn down the moment the screen that started it goes away. Ends only when {@link #end()}
 * (host) or {@link #leave()} (joiner) is called explicitly, or Nearby reports a real connection
 * loss.
 *
 * <p>Everything a session owes the other devices happens here rather than on the screen, because
 * the screen is the part that comes and goes: sending a new joiner the board it is joining (see
 * {@link #sendBoardTo}), and relaying one joiner's edits to the others (the host is the hub of a
 * star — see {@code internalListener.onMessage}). Callbacks that arrive with no screen attached
 * are buffered and replayed on the next {@link #attach} rather than dropped, so ink drawn while
 * the host had the whiteboard closed still lands.
 *
 * <p>A bound foreground {@code Service} (persistent notification, survives full backgrounding —
 * Nearby is throttled once the process itself is backgrounded) is the eventual home for this, per
 * {@code memory/whiteboard_collab_redesign_plan.md}. This holder is the piece of that redesign
 * that's implemented today: session ownership decoupled from the fragment. Promoting it into a
 * real {@code Service} later is a matter of moving this class's state into one, since fragments
 * already talk to it through {@link #attach}/{@link #detach} rather than a raw field.
 */
public final class CollabSessionHolder {

    public interface RosterListener {
        void onPeerConnected(String peerId);
        void onPeerDisconnected(String peerId);
        void onPeerInfoUpdated(String peerId, String displayName);
        void onPeerLeft(String peerId);
        void onSessionEndedByHost();
        void onMessage(String peerId, CollabMessage message);
        /** @param detail the underlying message, for logs rather than for the screen. */
        void onError(CollabSession.Failure failure, String detail);
    }

    private static final String TAG = "CollabSessionHolder";

    /**
     * How many events are held for a screen that isn't there.
     *
     * <p>Generous, because the thing being held is usually ink: every stroke a joiner draws while
     * the host has the whiteboard closed is one of these, and dropping one loses it from the
     * host's board and from every other joiner's. Bounded all the same — a session left running
     * for an afternoon should not grow without limit.
     */
    private static final int MAX_PENDING_EVENTS = 500;

    private static CollabSession session;
    private static boolean host;
    private static RosterListener activeListener;
    /** Application context and board id for the live session, so the session can answer for the
     *  board with no screen in the picture — see {@link #sendBoardTo}. */
    private static Context appContext;
    private static String boardId;
    /** Events that arrived while nothing was attached, replayed in order by {@link #attach}.
     *  Guarded by its own monitor: Nearby's callbacks and a fragment's lifecycle are not
     *  guaranteed to be the same thread, and this is the one piece of state both touch. */
    private static final Deque<Event> pending = new ArrayDeque<>();

    /** One callback, kept as a value so it can be either delivered now or replayed later. */
    private interface Event {
        void deliverTo(RosterListener listener);
    }

    private CollabSessionHolder() {}

    public static boolean isActive() {
        return session != null;
    }

    public static boolean isHost() {
        return host;
    }

    public static CollabSession session() {
        return session;
    }

    /** A fragment calls this once it's ready to receive callbacks (e.g. {@code onStart()}). Only
     *  one screen is ever showing the whiteboard at a time, so a single active listener slot is
     *  enough — no need for a list. */
    public static void attach(RosterListener listener) {
        activeListener = listener;
        // Everything that happened while the screen was away, in the order it happened — a peer
        // that connected, the strokes they drew, the fact that they left. Replayed rather than
        // dropped, since for a host the alternative is losing a joiner's ink outright. Drained
        // first, then delivered: a callback is free to come back in here without deadlocking.
        List<Event> replay;
        synchronized (pending) {
            replay = new ArrayList<>(pending);
            pending.clear();
        }
        for (Event event : replay) event.deliverTo(listener);
    }

    /** Call from {@code onStop()}/{@code onDestroyView()} — this does not touch the session
     *  itself, only stops routing callbacks to a screen that's going away. */
    public static void detach(RosterListener listener) {
        if (activeListener == listener) activeListener = null;
    }

    /** @param whiteboardId the board being shared — the host answers new joiners out of its own
     *                      copy of it, so the session has to know which one it is. */
    public static CollabSession host(Context context, String myDisplayName, String whiteboardId) {
        appContext = context.getApplicationContext();
        boardId = whiteboardId;
        synchronized (pending) {
            pending.clear();
        }
        session = CollabSession.host(appContext, internalListener, myDisplayName);
        host = true;
        return session;
    }

    public static CollabSession join(Context context, String token, String myDisplayName, String whiteboardId) {
        appContext = context.getApplicationContext();
        boardId = whiteboardId;
        synchronized (pending) {
            pending.clear();
        }
        session = CollabSession.join(appContext, token, internalListener, myDisplayName);
        host = false;
        return session;
    }

    /** Host-only: ends the session for everyone. */
    public static void end() {
        if (session != null) session.endSession();
        clear();
    }

    /** Joiner-only: leaves without ending it for the others. */
    public static void leave() {
        if (session != null) session.leaveSession();
        clear();
    }

    /** Ends this device's part in the session. Deliberately leaves {@link #pending} alone: the
     *  event that ended it may itself be waiting there for a screen to come back and read it.
     *  A new session clears the buffer when it starts. */
    private static void clear() {
        session = null;
        activeListener = null;
        appContext = null;
        boardId = null;
    }

    /**
     * Hands a device that has just joined the whole board, read from the host's own storage.
     *
     * <p>The session's job, not the screen's. A host can leave the whiteboard with the session
     * still running — that is the entire point of this class — and while this lived in
     * {@code WhiteboardFragment}, anyone who joined during that time received nothing at all: the
     * callback that triggers it had no attached listener to reach, so the board was never sent and
     * the joiner sat looking at an empty canvas.
     */
    private static void sendBoardTo(String peerId) {
        final CollabSession live = session;
        final Context context = appContext;
        final String id = boardId;
        if (live == null || context == null || id == null) return;
        new Thread(() -> {
            AppDatabase db = AppDatabase.getInstance(context);
            List<Stroke> strokes = new StrokeRepository(db).getByWhiteboard(id);
            List<WhiteboardText> texts = new WhiteboardTextRepository(db).getByWhiteboard(id);
            List<CollabMessage> chunks = CollabMessage.snapshotChunks(strokes, texts);
            Log.i(TAG, "sending board to " + peerId + ": " + strokes.size() + " strokes, "
                    + texts.size() + " texts, in " + chunks.size() + " chunk(s)");
            for (CollabMessage chunk : chunks) live.sendTo(peerId, chunk);
        }).start();
    }

    /** Delivers one callback to the attached screen, or keeps it until there is one. */
    private static void deliver(Event event) {
        RosterListener listener = activeListener;
        if (listener != null) {
            event.deliverTo(listener);
            return;
        }
        synchronized (pending) {
            if (pending.size() >= MAX_PENDING_EVENTS) {
                Log.w(TAG, "buffer full at " + MAX_PENDING_EVENTS + " events — dropping the oldest");
                pending.pollFirst();
            }
            pending.addLast(event);
        }
    }

    private static final CollabSession.Listener internalListener = new CollabSession.Listener() {
        @Override
        public void onPeerConnected(String peerId) {
            // Before anything reaches a screen: a joiner's first need is the board itself, and
            // that must not depend on the host having the whiteboard open.
            if (host) sendBoardTo(peerId);
            deliver(listener -> listener.onPeerConnected(peerId));
        }

        @Override
        public void onPeerDisconnected(String peerId) {
            deliver(listener -> listener.onPeerDisconnected(peerId));
            if (session != null && !session.hasAnyPeer() && !host) clear();
        }

        @Override
        public void onPeerInfoUpdated(String peerId, String displayName) {
            deliver(listener -> listener.onPeerInfoUpdated(peerId, displayName));
        }

        @Override
        public void onPeerLeft(String peerId) {
            deliver(listener -> listener.onPeerLeft(peerId));
        }

        @Override
        public void onSessionEndedByHost() {
            deliver(listener -> listener.onSessionEndedByHost());
            clear();
        }

        @Override
        public void onMessage(String peerId, CollabMessage message) {
            // The host is the hub of a P2P_STAR: joiners never see each other, so one joiner's
            // stroke only reaches the others by being passed on here. Relayed at this level rather
            // than from the screen, for the same reason the board is sent from here — a host with
            // the whiteboard closed is still the only route between two joiners.
            if (host && session != null) session.sendToAllExcept(peerId, message);
            deliver(listener -> listener.onMessage(peerId, message));
        }

        @Override
        public void onError(CollabSession.Failure failure, String detail) {
            deliver(listener -> listener.onError(failure, detail));
            clear();
        }
    };
}
