package mse.quill.collab;

import android.content.Context;

/**
 * Owns the live {@link CollabSession} outside any one {@code WhiteboardFragment} instance, so the
 * session survives that fragment being destroyed and recreated (back nav, rotation) instead of
 * being torn down the moment the screen that started it goes away. Ends only when {@link #end()}
 * (host) or {@link #leave()} (joiner) is called explicitly, or Nearby reports a real connection
 * loss.
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
        void onError(String reason);
    }

    private static CollabSession session;
    private static boolean host;
    private static RosterListener activeListener;

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
    }

    /** Call from {@code onStop()}/{@code onDestroyView()} — this does not touch the session
     *  itself, only stops routing callbacks to a screen that's going away. */
    public static void detach(RosterListener listener) {
        if (activeListener == listener) activeListener = null;
    }

    public static CollabSession host(Context context, String myDisplayName) {
        session = CollabSession.host(context, internalListener, myDisplayName);
        host = true;
        return session;
    }

    public static CollabSession join(Context context, String token, String myDisplayName) {
        session = CollabSession.join(context, token, internalListener, myDisplayName);
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

    private static void clear() {
        session = null;
        activeListener = null;
    }

    private static final CollabSession.Listener internalListener = new CollabSession.Listener() {
        @Override
        public void onPeerConnected(String peerId) {
            if (activeListener != null) activeListener.onPeerConnected(peerId);
        }

        @Override
        public void onPeerDisconnected(String peerId) {
            if (activeListener != null) activeListener.onPeerDisconnected(peerId);
            if (session != null && !session.hasAnyPeer() && !host) clear();
        }

        @Override
        public void onPeerInfoUpdated(String peerId, String displayName) {
            if (activeListener != null) activeListener.onPeerInfoUpdated(peerId, displayName);
        }

        @Override
        public void onPeerLeft(String peerId) {
            if (activeListener != null) activeListener.onPeerLeft(peerId);
        }

        @Override
        public void onSessionEndedByHost() {
            if (activeListener != null) activeListener.onSessionEndedByHost();
            clear();
        }

        @Override
        public void onMessage(String peerId, CollabMessage message) {
            if (activeListener != null) activeListener.onMessage(peerId, message);
        }

        @Override
        public void onError(String reason) {
            if (activeListener != null) activeListener.onError(reason);
            clear();
        }
    };
}
