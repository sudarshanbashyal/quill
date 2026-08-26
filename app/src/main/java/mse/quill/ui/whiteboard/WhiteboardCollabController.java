package mse.quill.ui.whiteboard;

import android.graphics.Bitmap;
import android.util.Log;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mse.quill.R;
import mse.quill.collab.CollabMessage;
import mse.quill.collab.CollabSession;
import mse.quill.collab.CollabSessionHolder;
import mse.quill.collab.QrCodes;
import mse.quill.collab.SessionCode;
import mse.quill.collab.SessionScanner;
import mse.quill.data.model.Stroke;
import mse.quill.data.model.WhiteboardText;
import mse.quill.ui.profile.ProfilePreferences;

/**
 * The whole live-collaboration surface of the whiteboard screen: hosting, joining by scan, the
 * roster, snapshot reassembly, and turning a {@link CollabSession.Failure} into something a person
 * can read.
 *
 * <p>Split out of {@code WhiteboardFragment}, which had all of it inline and was 1500 lines because
 * of it. The division is: this class knows about the network and nothing about the canvas or the
 * database; the fragment knows about those and nothing about {@link CollabMessage}. Everything this
 * class needs done to the board goes through {@link WhiteboardCanvas}, and everything it needs from
 * the screen around the board goes through {@link Host}.
 *
 * <p>The session itself is still owned by {@link CollabSessionHolder} — it outlives this controller
 * exactly as it outlived the fragment, which is what lets a rotation or a trip to Home keep a
 * session alive. What this class holds is a <em>mirror</em> of that, re-synced in {@link #onStart}.
 *
 * <p>One piece of real state does live here: a snapshot being reassembled from its chunks. That is
 * a deliberate move off the fragment — a half-received board used to be owned by a UI object
 * Android may destroy mid-transfer.
 */
final class WhiteboardCollabController {

    private static final String TAG = "WhiteboardCollab";

    /** The QR code's edge, in dp. Big enough to scan across a table. */
    private static final int QR_SIZE_DP = 220;

    /**
     * What this controller needs from the screen hosting it, beyond the canvas operations in
     * {@link WhiteboardCanvas}. Every one of these is something only a {@code Fragment} can do:
     * own a permission launcher, own view state, or navigate.
     */
    interface Host extends WhiteboardCanvas {

        /** Whether there is anything on the board worth warning about before a join replaces it. */
        boolean hasBoardContent();

        /** Duplicates this board onto Home, then runs {@code then} on the main thread. */
        void saveCopyOfBoard(Runnable then);

        /** Runs {@code onGranted} once the Nearby permission ladder is satisfied, or not at all. */
        void requestCollabPermissions(Runnable onGranted);

        /**
         * Who is at the board right now, this device first — or an empty list when there is no
         * session. The controller derives this fresh on every roster event and hands it over
         * whole; the screen decides how to show it.
         */
        void showRoster(List<String> names);

        /** Clear is host-only, and the collab button reads "end session" once there is one. */
        void applyCollabRole(boolean inSession, boolean isHost);

        /** Leaves the whiteboard screen. */
        void navigateUp();
    }

    private final Fragment fragment;
    private final Host host;
    private final String whiteboardId;

    /** Mirrors {@code CollabSessionHolder.session()} — see the class comment. */
    private CollabSession session;
    private boolean isHost;
    private CollabDialogs.StatusDialog statusDialog;

    /** A snapshot being reassembled from its chunks — see {@link #applySnapshot}. Empty except in
     *  the moment between a joiner connecting and the host's board arriving in full. */
    private final Set<Integer> pendingSnapshotChunks = new HashSet<>();
    private final List<Stroke> pendingSnapshotStrokes = new ArrayList<>();
    private final List<WhiteboardText> pendingSnapshotTexts = new ArrayList<>();
    private int pendingSnapshotCount;

    WhiteboardCollabController(Fragment fragment, String whiteboardId, Host host) {
        this.fragment = fragment;
        this.whiteboardId = whiteboardId;
        this.host = host;
    }

    // ── State the screen asks about ───────────────────────────────────────────

    boolean isInSession() {
        return session != null;
    }

    boolean isSessionHost() {
        return isHost;
    }

    // ── Sending this device's own edits ───────────────────────────────────────

    void sendStroke(Stroke stroke) {
        send(CollabMessage.stroke(stroke));
    }

    void sendText(WhiteboardText text) {
        send(CollabMessage.text(text));
    }

    /** Undo only ever pops something *this device* added — received items are never pushed onto
     *  the fragment's undo stack — so this is always "retract my own last item". */
    void sendRetract(String id, boolean isText) {
        send(CollabMessage.retract(id, isText));
    }

    /** Clear is destructive to everyone, so only the host can get here — a joiner's button is
     *  disabled (see {@link Host#applyCollabRole}) — and the host tells every peer rather than
     *  each side clearing independently. */
    void sendClear() {
        send(CollabMessage.clear());
    }

    private void send(CollabMessage message) {
        if (session != null && session.isConnected()) session.send(message);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Picks up whatever session is already alive (it survives the fragment being torn down and
     *  recreated) rather than assuming there is none — see {@link CollabSessionHolder}. */
    void onStart() {
        // A session belongs to the board it was started on, and only to that board. Without this
        // check, opening any whiteboard while one was alive adopted it — a brand-new board would
        // open already in someone else's session, drawing on top of their work. A session started
        // from this screen attaches at that point instead; see startHosting.
        if (!CollabSessionHolder.isFor(whiteboardId)) return;

        // Role and roster first, listener second: attaching replays whatever happened while this
        // screen was away, and those callbacks read isHost — a host that hadn't re-synced yet
        // would handle its own replayed events as if it were a joiner.
        session = CollabSessionHolder.session();
        isHost = CollabSessionHolder.isHost();
        applyRole();
        CollabSessionHolder.attach(rosterListener);
        // Back on the board — say so, since the session may have carried on without this screen.
        CollabSessionHolder.setViewing(true);
    }

    /** Only stops routing callbacks here — the session itself lives on in
     *  {@link CollabSessionHolder} until explicitly ended/left. */
    void onStop() {
        // Still in the session, no longer at the board: the others should stop counting this
        // device among the people they are drawing with until it comes back. Only for the board
        // the session is actually on — another board's screen has nothing to say about it.
        if (CollabSessionHolder.isFor(whiteboardId)) CollabSessionHolder.setViewing(false);
        CollabSessionHolder.detach(rosterListener);
    }

    void onDestroyView() {
        dismissStatusDialog();
    }

    // ── Entry: host or join ───────────────────────────────────────────────────

    /** "Host a session" / "Join a session" — the entry point for the whole feature. */
    void onCollabButtonClicked() {
        if (!fragment.isAdded()) return;
        if (session != null) {
            // Already in a session: the button becomes "end session"/"leave" instead of opening
            // the choice again. Only a host ending it is destructive to everyone else — a joiner
            // leaving just removes themself, so the two get separate copy.
            if (isHost) {
                // "Show code" rather than end-or-nothing: the session keeps accepting joiners for
                // as long as it is up, so the host needs a way back to the QR after putting it
                // away — otherwise a third device has no way in.
                new MaterialAlertDialogBuilder(fragment.requireContext())
                        .setTitle(R.string.collab_end_session)
                        .setMessage(R.string.collab_leaving_locks_others_out)
                        .setPositiveButton(R.string.collab_end_session, (d, w) -> endSession())
                        .setNeutralButton(R.string.collab_show_code, (d, w) -> showHostInvite())
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            } else {
                new MaterialAlertDialogBuilder(fragment.requireContext())
                        .setTitle(R.string.collab_leave_session)
                        .setPositiveButton(R.string.collab_leave_session, (d, w) -> endSession())
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }
            return;
        }
        CollabDialogs.showEntryDialog(fragment.requireContext(), new CollabDialogs.EntryListener() {
            @Override public void onHostChosen() { host.requestCollabPermissions(WhiteboardCollabController.this::startHosting); }
            @Override public void onJoinChosen() { host.requestCollabPermissions(WhiteboardCollabController.this::startJoinByScan); }
        });
    }

    /** The name shown to every other participant. Never the device model — see
     *  {@link ProfilePreferences#collabDisplayName}. */
    private String myDisplayName() {
        return ProfilePreferences.collabDisplayName(fragment.requireContext());
    }

    private void startHosting() {
        if (!fragment.isAdded()) return;
        isHost = true;
        session = CollabSessionHolder.host(fragment.requireContext(), myDisplayName(), whiteboardId);
        // Attached here as well as in onStart: a session started from this screen begins after
        // onStart has already been and gone, and an unattached screen hears nothing at all.
        CollabSessionHolder.attach(rosterListener);
        showHostInvite();
    }

    /** Shows (or re-shows) the QR code for the session this device is hosting. Re-encoded from the
     *  live token each time rather than held onto, so there is one source of truth for it. */
    private void showHostInvite() {
        if (session == null || !fragment.isAdded()) return;
        dismissStatusDialog();
        Bitmap qr = QrCodes.encode(SessionCode.encode(session.token()), dp(QR_SIZE_DP));
        CollabDialogs.StatusDialog shown =
                CollabDialogs.showHostDialog(fragment.requireContext(), qr, this::endSession);
        // "Done" puts the code away without ending anything, so the reference has to go with it —
        // otherwise the next roster change would be writing status into a dialog nobody can see.
        shown.dialog.setOnDismissListener(d -> {
            if (statusDialog == shown) statusDialog = null;
        });
        statusDialog = shown;
        updateHostInviteStatus();
    }

    /** Keeps the hosting dialog's status line honest about who has already joined, since it now
     *  stays on screen while people arrive and leave. */
    private void updateHostInviteStatus() {
        if (statusDialog == null || !isHost || !fragment.isAdded()) return;
        int connected = session == null ? 0 : session.currentPeers().size();
        statusDialog.setStatus(connected == 0
                ? fragment.getString(R.string.collab_hosting_waiting)
                : fragment.getResources().getQuantityString(
                        R.plurals.collab_hosting_connected, connected, connected));
    }

    /**
     * Joining replaces this board with the host's, so anything already drawn here is about to go.
     *
     * <p>Offered as a choice rather than a warning to click past, because both answers are real
     * ones: the sketch was scrap, or it was work — and only the person who drew it knows which.
     * An empty board asks nothing; there is nothing to lose and no decision to make.
     */
    private void startJoinByScan() {
        if (!fragment.isAdded()) return;
        if (!host.hasBoardContent()) {
            scanForSession();
            return;
        }
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.collab_join_replaces_title)
                .setMessage(R.string.collab_join_replaces_message)
                .setPositiveButton(R.string.collab_join_save_copy,
                        (d, w) -> host.saveCopyOfBoard(this::scanForSession))
                .setNeutralButton(R.string.collab_join_discard, (d, w) -> scanForSession())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void scanForSession() {
        if (!fragment.isAdded()) return;
        SessionScanner.scan(fragment.requireContext(), new SessionScanner.Listener() {
            @Override public void onToken(String token) {
                joinWithToken(token);
            }

            @Override public void onCancelled() {
                // See HomeFragment: leaving the scanner is not a failure to report.
            }

            @Override public void onFailed(boolean notASession) {
                if (!fragment.isAdded()) return;
                showError(notASession
                        ? R.string.collab_error_not_a_session
                        : R.string.collab_error_scanner, notASession);
            }
        });
    }

    /** Starts joining a session whose token is already in hand — from a scan here, from Home's
     *  own scan, or from a {@code quill://} link the phone's camera opened. */
    void joinWithToken(String token) {
        if (!fragment.isAdded()) return;
        isHost = false;
        statusDialog = CollabDialogs.showJoiningDialog(fragment.requireContext(), this::endSession);
        session = CollabSessionHolder.join(
                fragment.requireContext(), token, myDisplayName(), whiteboardId);
        // See startHosting: joining from Home happens before onStart, joining from this screen
        // happens long after it, and either way this listener has to be the one attached.
        CollabSessionHolder.attach(rosterListener);
    }

    // ── Exit ──────────────────────────────────────────────────────────────────

    /**
     * The back button / system back. Leaving the board leaves the session with it.
     *
     * <p>The session can technically outlive this screen, and briefly does — backgrounding the app
     * keeps it up, which is what {@code setViewing} is for. But walking out of the whiteboard is
     * not backgrounding: it is the gesture that means "I'm done here", and a session that quietly
     * kept running behind Home was one nobody could see, leave, or avoid being dragged back into
     * the next time they opened any board at all.
     *
     * <p>What survives is the board itself — every stroke received is already on this device, so
     * leaving keeps a copy rather than losing the work, and scanning the code again rejoins.
     */
    void attemptExit() {
        if (session == null || !fragment.isAdded()) {
            host.navigateUp();
            return;
        }
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.collab_exit_warning_title)
                .setMessage(isHost
                        ? R.string.collab_exit_host_message
                        : R.string.collab_exit_joiner_message)
                .setPositiveButton(isHost
                        ? R.string.collab_exit_confirm
                        : R.string.collab_leave_session, (d, w) -> {
                    endSession();
                    host.navigateUp();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Explicit exit from the session: ends it for everyone if this device is the host, or just
     *  removes this device if it's a joiner — see {@link CollabSessionHolder#end()}/
     *  {@link CollabSessionHolder#leave()}. */
    void endSession() {
        if (isHost) CollabSessionHolder.end();
        else CollabSessionHolder.leave();
        clearLocalState();
    }

    private void clearLocalState() {
        session = null;
        pendingSnapshotChunks.clear();
        pendingSnapshotStrokes.clear();
        pendingSnapshotTexts.clear();
        pendingSnapshotCount = 0;
        dismissStatusDialog();
        applyRole();
    }

    private void dismissStatusDialog() {
        if (statusDialog == null) return;
        statusDialog.dismiss();
        statusDialog = null;
    }

    // ── Roster ────────────────────────────────────────────────────────────────

    private void applyRole() {
        host.applyCollabRole(session != null, isHost);
        publishRoster();
    }

    /**
     * Re-derives the roster and hands it to the screen. Called on every roster event, cheaply —
     * there are at most a handful of peers, and deriving it each time is what keeps this screen
     * from having a second, staler idea of who is in the session.
     *
     * <p>Only those actually looking at the board: a session survives its screen, so someone who
     * backed out is still connected and still relaying, but they are not there to draw with, and
     * listing them would be the roster telling a small lie every time.
     */
    private void publishRoster() {
        if (!fragment.isAdded()) return;
        List<String> names = new ArrayList<>();
        if (session == null) {
            host.showRoster(names);
            return;
        }
        names.add(fragment.getString(R.string.collab_you, myDisplayName()));
        for (CollabSession.PeerInfo peer : session.currentPeers()) {
            if (!peer.viewing) continue;
            names.add(peer.displayName != null
                    ? peer.displayName : fragment.getString(R.string.collab_peer_connecting));
        }
        host.showRoster(names);
    }

    // ── Session events ────────────────────────────────────────────────────────

    private final CollabSessionHolder.RosterListener rosterListener =
            new CollabSessionHolder.RosterListener() {
        @Override
        public void onPeerConnected(String peerId) {
            onMainThread(() -> {
                toast(R.string.collab_connected);
                applyRole();
                if (isHost) {
                    // The code stays up: this session accepts joiners for as long as it runs, and
                    // dismissing it here is what used to make the first joiner the only one.
                    // Sending the board to this joiner is the session's job, not this screen's —
                    // see CollabSessionHolder.sendBoardTo.
                    updateHostInviteStatus();
                } else if (statusDialog != null) {
                    // The joiner's dialog is a progress report, and the progress is over.
                    statusDialog.setStatus(fragment.getString(R.string.collab_connected));
                    dismissStatusDialog();
                }
            });
        }

        @Override
        public void onPeerDisconnected(String peerId) {
            onMainThread(() -> {
                toast(R.string.collab_disconnected);
                publishRoster();
                // A host whose last joiner dropped keeps hosting — the code is still on screen and
                // still valid. Only a joiner has nothing left once the host is gone.
                if (isHost) updateHostInviteStatus();
                else if (session == null || !session.hasAnyPeer()) endSession();
                else applyRole();
            });
        }

        @Override
        public void onPeerInfoUpdated(String peerId, String displayName) {
            onMainThread(WhiteboardCollabController.this::publishRoster);
        }

        @Override
        public void onPeerPresenceChanged(String peerId, boolean viewing) {
            onMainThread(WhiteboardCollabController.this::publishRoster);
        }

        @Override
        public void onPeerLeft(String peerId) {
            onMainThread(() -> {
                toast(R.string.collab_peer_left);
                applyRole();
                updateHostInviteStatus();
            });
        }

        @Override
        public void onSessionEndedByHost() {
            onMainThread(() -> {
                toast(R.string.collab_host_ended);
                clearLocalState();
            });
        }

        @Override
        public void onMessage(String peerId, CollabMessage message) {
            onMainThread(() -> applyIncoming(message));
        }

        @Override
        public void onError(CollabSession.Failure failure, String detail) {
            Log.w(TAG, "collab failed: " + failure + " (" + detail + ")");
            onMainThread(() -> {
                boolean offerRetry = !isHost && failure != CollabSession.Failure.RADIO_UNAVAILABLE;
                clearLocalState();
                showError(messageFor(failure), offerRetry);
            });
        }
    };

    /** Session callbacks arrive on whatever thread the transport used. Checked on both sides of
     *  the hop: the fragment can detach while the post is in flight, and touching a detached
     *  fragment throws. */
    private void onMainThread(Runnable action) {
        if (!fragment.isAdded()) return;
        fragment.requireActivity().runOnUiThread(() -> {
            if (!fragment.isAdded()) return;
            action.run();
        });
    }

    private void toast(int textRes) {
        Toast.makeText(fragment.requireContext(), textRes, Toast.LENGTH_SHORT).show();
    }

    // ── Incoming messages ─────────────────────────────────────────────────────

    /** Applies one message from a peer. Passing it on to the other peers is the host's job and
     *  happens in {@link CollabSessionHolder} before this is ever called, so that a host who has
     *  left the whiteboard is still the route between two joiners. */
    private void applyIncoming(CollabMessage message) {
        switch (message.type) {
            case CollabMessage.TYPE_SNAPSHOT:
                if (!isHost) applySnapshot(message);
                break;
            case CollabMessage.TYPE_STROKE:
                host.applyStroke(message.stroke);
                break;
            case CollabMessage.TYPE_TEXT:
                host.applyText(message.text);
                break;
            case CollabMessage.TYPE_RETRACT:
                host.retract(message.retractId, message.retractIsText);
                break;
            case CollabMessage.TYPE_CLEAR:
                if (!isHost) host.clearAll();
                break;
        }
    }

    /**
     * The host's whole board, replacing whatever this device had — the host is ground truth for a
     * session, so a joiner starts from exactly what the host sees rather than merging.
     *
     * <p>Arrives in numbered chunks (see {@link CollabMessage#snapshotChunks}), so nothing is
     * handed on until all of them are in: a half-applied snapshot is a board missing strokes,
     * which is worse than one that appears a moment later. Chunks are collected by index rather
     * than by arrival order, and a chunk announcing a different total means a fresh snapshot
     * started — the older, incomplete one is abandoned.
     */
    private void applySnapshot(CollabMessage message) {
        if (message.snapshotCount != pendingSnapshotCount) {
            pendingSnapshotChunks.clear();
            pendingSnapshotStrokes.clear();
            pendingSnapshotTexts.clear();
            pendingSnapshotCount = message.snapshotCount;
        }
        if (!pendingSnapshotChunks.add(message.snapshotIndex)) return; // a repeat; already have it
        pendingSnapshotStrokes.addAll(message.strokes);
        pendingSnapshotTexts.addAll(message.texts);
        if (pendingSnapshotChunks.size() < pendingSnapshotCount) return;

        List<Stroke> strokes = new ArrayList<>(pendingSnapshotStrokes);
        List<WhiteboardText> texts = new ArrayList<>(pendingSnapshotTexts);
        pendingSnapshotChunks.clear();
        pendingSnapshotStrokes.clear();
        pendingSnapshotTexts.clear();
        pendingSnapshotCount = 0;

        // Chunks can be reassembled in any order, so draw order is restored from the timestamps
        // rather than inherited from the wire — ink laid down later belongs on top.
        Collections.sort(strokes, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        Collections.sort(texts, (a, b) -> Long.compare(a.createdAt, b.createdAt));

        host.replaceAll(strokes, texts);
    }

    // ── Errors ────────────────────────────────────────────────────────────────

    /** One message per way this can go wrong — see {@link CollabSession.Failure}. */
    private int messageFor(CollabSession.Failure failure) {
        switch (failure) {
            case RADIO_UNAVAILABLE: return R.string.collab_error_radios;
            case CANNOT_HOST:       return R.string.collab_error_cannot_host;
            case CANNOT_SEARCH:     return R.string.collab_error_cannot_search;
            case SESSION_NOT_FOUND: return R.string.collab_error_not_found;
            case CONNECT_FAILED:
            default:                return R.string.collab_error_connect_failed;
        }
    }

    /**
     * A dialog rather than a toast: every one of these is a dead end the user has to decide
     * something about, and a message that fades after two seconds is not that.
     *
     * @param offerScanAgain adds a second button straight back to the scanner, for the failures
     *                       where trying the same code again is the obvious next move.
     */
    private void showError(int messageRes, boolean offerScanAgain) {
        if (!fragment.isAdded()) return;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.collab_error_title)
                .setMessage(messageRes)
                .setPositiveButton(android.R.string.ok, null);
        if (offerScanAgain) {
            builder.setNeutralButton(R.string.collab_scan_again, (d, w) -> startJoinByScan());
        }
        builder.show();
    }

    private int dp(int value) {
        return (int) (value * fragment.getResources().getDisplayMetrics().density);
    }
}
