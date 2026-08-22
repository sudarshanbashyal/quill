package mse.quill.collab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One live whiteboard session over Nearby Connections, host or any number of joiners. Per
 * requirements.md Epic C, "the token is the interface" — a Nearby {@code endpointId} is assigned
 * locally by the discovering device, so it can't be handed over out of band. What travels instead
 * is a random session token (carried by a QR code today, an NFC tap later) that the host
 * advertises under and the joiner matches during discovery. Because only someone who has actually
 * seen that token can find the right advertisement, the host accepts every incoming connection
 * request without a separate approval dialog.
 *
 * <p>{@code SERVICE_ID} is fixed and app-wide; the token is what disambiguates one session from
 * every other Quill user advertising nearby.
 *
 * <p>Topology is {@link Strategy#P2P_STAR}: joiners only ever connect to the host, never to each
 * other. That asymmetry is why {@code peers} means two different things depending on role: for
 * the host, it's both the physical connection set (Nearby endpoint ids) and the visible roster —
 * they're the same thing. For a joiner, there is exactly one physical connection (to the host,
 * tracked separately in {@link #hostConnectionEndpointId}), and {@code peers} is a purely logical
 * roster — every participant's canonical id + name, learned by relay through the host, used only
 * to render "who else is here." A joiner never sends anything anywhere except to the host; the
 * host is the one that relays board edits ({@link #sendToAllExcept}) and peer-info announcements.
 */
public class CollabSession {

    private static final String TAG = "CollabSession";
    private static final String SERVICE_ID = "mse.quill.whiteboard";
    private static final Strategy STRATEGY = Strategy.P2P_STAR;
    /** Canonical id every joiner uses for the host in its roster — the host has no Nearby
     *  endpoint id of its own (endpoint ids are the *remote* party as seen by the local device),
     *  so a fixed sentinel stands in for it instead. */
    private static final String HOST_PEER_ID = "host";

    /**
     * The ways a session can fail, as things the user can be told apart from one another.
     *
     * <p>A single {@code onError(String)} used to carry whatever Play Services said, which is how
     * "Bluetooth is off" and "nobody is hosting that code" arrived looking identical — both as a
     * sentence the user could do nothing with. The caller maps these to its own copy; the raw text
     * survives as {@code detail}, for the log.
     */
    public enum Failure {
        /** The radios refused, or the app is missing a permission it needs. */
        RADIO_UNAVAILABLE,
        /** Advertising wouldn't start — the host's side of {@link #RADIO_UNAVAILABLE}. */
        CANNOT_HOST,
        /** Discovery wouldn't start. */
        CANNOT_SEARCH,
        /** Nothing was advertising that token before the deadline: wrong code, host gone, or out
         *  of range. This is the one that used to be an indefinite wait. */
        SESSION_NOT_FOUND,
        /** The session was found and then the connection itself failed or was rejected. */
        CONNECT_FAILED
    }

    public interface Listener {
        /** A device just connected and is ready to exchange messages (its name isn't known yet).
         *  {@code peerId} is canonical: a Nearby endpoint id if this device is the host, or
         *  {@link #HOST_PEER_ID} if this device just joined. */
        void onPeerConnected(String peerId);
        /** A peer's connection was lost without an explicit "Leave"/"End session" preceding it. */
        void onPeerDisconnected(String peerId);
        /** A peer's display name became known or changed. */
        void onPeerInfoUpdated(String peerId, String displayName);
        /** A peer opened or left the whiteboard screen without leaving the session. */
        void onPeerPresenceChanged(String peerId, boolean viewing);
        /** A peer explicitly left; the session continues for everyone else. */
        void onPeerLeft(String peerId);
        /** The host explicitly ended the session for everyone. */
        void onSessionEndedByHost();
        void onMessage(String peerId, CollabMessage message);
        /** @param detail the underlying message, for logs rather than for the screen. */
        void onError(Failure failure, String detail);
    }

    /** What this device knows about one participant (see class doc for what "peers" means per
     *  role). */
    public static final class PeerInfo {
        public final String id;
        public String displayName;
        /**
         * Whether this peer has the whiteboard in front of them right now.
         *
         * <p>Connected and present are not the same thing here: a session outlives the screen that
         * started it (see {@code CollabSessionHolder}), so someone can be reachable, relaying, and
         * nowhere near the board. Starts true because a peer joins from the board itself, and is
         * corrected by the first {@link CollabMessage#TYPE_PRESENCE} either way.
         */
        public boolean viewing = true;

        PeerInfo(String id) {
            this.id = id;
        }
    }

    /**
     * How long a joiner looks before giving up, in ms.
     *
     * <p>Nearby's discovery has no deadline of its own — it searches until it is told to stop — so
     * a mistyped, stale or simply wrong code left the joining dialog spinning for as long as the
     * user was willing to watch it. Twenty seconds is comfortably longer than a working join takes
     * over Bluetooth (a couple of seconds once both radios are up) and short enough that a failure
     * is still obviously a failure.
     */
    private static final long JOIN_TIMEOUT_MS = 20_000L;

    private final ConnectionsClient client;
    private final Listener listener;
    private final boolean host;
    private final String myDisplayName;
    /** Host: physical connections, keyed by Nearby endpoint id (== canonical peer id).
     *  Joiner: logical roster, keyed by canonical peer id (host + every other joiner), learned
     *  entirely through relay — see class doc. */
    private final Map<String, PeerInfo> peers = new LinkedHashMap<>();
    /** Joiner only: the one physical connection, to the host. */
    private String hostConnectionEndpointId;
    private boolean stopped;
    /** Whether this device currently has the board open — announced to the others, and re-sent to
     *  each new peer as part of the introduction so a late joiner isn't told a stale story. */
    private boolean viewing = true;

    /** Runs {@link #JOIN_TIMEOUT_MS} after a join starts, cancelled the moment anything happens. */
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable joinTimeout;

    private CollabSession(Context context, Listener listener, boolean host, String myDisplayName) {
        this.client = Nearby.getConnectionsClient(context.getApplicationContext());
        this.listener = listener;
        this.host = host;
        this.myDisplayName = myDisplayName;
    }

    /** Starts advertising under a freshly minted token and returns it, for a QR code to carry. */
    public static CollabSession host(Context context, Listener listener, String myDisplayName) {
        CollabSession session = new CollabSession(context, listener, true, myDisplayName);
        String token = UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        session.startAdvertising(token);
        return session;
    }

    public static CollabSession join(Context context, String token, Listener listener, String myDisplayName) {
        CollabSession session = new CollabSession(context, listener, false, myDisplayName);
        session.startDiscovery(token);
        return session;
    }

    private String advertisedToken;

    public String token() {
        return advertisedToken;
    }

    private void startAdvertising(String token) {
        this.advertisedToken = token;
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        client.startAdvertising(token, SERVICE_ID, connectionLifecycleCallback, options)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "startAdvertising failed", e);
                    fail(radioProblem(e) ? Failure.RADIO_UNAVAILABLE : Failure.CANNOT_HOST,
                            e.getMessage());
                });
    }

    private void startDiscovery(String wantedToken) {
        // Armed before discovery rather than after, so a search that never even starts is still on
        // a clock. Covers the whole join — finding the host *and* connecting to it — because from
        // the outside those are one act, and a connection that stalls half way through is as
        // unhelpful as one that never found anything.
        joinTimeout = () -> {
            joinTimeout = null;
            Log.w(TAG, "join timed out after " + JOIN_TIMEOUT_MS + "ms");
            fail(Failure.SESSION_NOT_FOUND, "timeout");
        };
        timeoutHandler.postDelayed(joinTimeout, JOIN_TIMEOUT_MS);

        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        client.startDiscovery(SERVICE_ID, new EndpointDiscoveryCallback() {
            @Override
            public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
                if (stopped) return;
                // The token is the endpoint name the host advertised under — only the matching
                // one is the session this device was told to join.
                if (!wantedToken.equals(info.getEndpointName())) return;
                client.stopDiscovery();
                client.requestConnection(deviceLabel(), endpointId, connectionLifecycleCallback)
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "requestConnection failed", e);
                            fail(Failure.CONNECT_FAILED, e.getMessage());
                        });
            }

            @Override
            public void onEndpointLost(String endpointId) {
                // Nothing to do: either we already connected, or the host went away before we did.
            }
        }, options).addOnFailureListener(e -> {
            Log.e(TAG, "startDiscovery failed", e);
            fail(radioProblem(e) ? Failure.RADIO_UNAVAILABLE : Failure.CANNOT_SEARCH, e.getMessage());
        });
    }

    /**
     * Reports a failure once and shuts the session down.
     *
     * <p>Once, because several of these can arrive together — a timeout firing as a connection
     * result comes back, say — and the second one would be an error message about a session that
     * has already been torn down. {@link #stop()} sets {@code stopped}, which is what makes this
     * idempotent, and it also cancels the deadline.
     */
    private void fail(Failure failure, String detail) {
        if (stopped) return;
        stop();
        listener.onError(failure, detail);
    }

    /**
     * Whether Play Services is telling us the radios are the problem, rather than anything about
     * this session. {@code STATUS_RADIO_ERROR} is what it answers with when Bluetooth or Wi-Fi is
     * off, or when a permission the radios need was refused — which is worth saying plainly,
     * because it is the one failure here the user can go and fix.
     */
    private static boolean radioProblem(Exception e) {
        return e instanceof ApiException
                && ((ApiException) e).getStatusCode() == ConnectionsStatusCodes.STATUS_RADIO_ERROR;
    }

    /** Stops the join clock — the peer arrived, or the session is being torn down. */
    private void cancelJoinTimeout() {
        if (joinTimeout != null) {
            timeoutHandler.removeCallbacks(joinTimeout);
            joinTimeout = null;
        }
    }

    private String deviceLabel() {
        return android.os.Build.MODEL != null ? android.os.Build.MODEL : "Quill";
    }

    private final ConnectionLifecycleCallback connectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            // Reaching this point already proves the other side knew the token (a host only
            // advertises under it, a joiner only discovers by matching it), so no extra
            // accept/reject prompt is shown — the QR scan itself was the authorisation.
            client.acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                cancelJoinTimeout();
                if (host) {
                    peers.put(endpointId, new PeerInfo(endpointId));
                    // Stays open to new joiners for the life of the session (no "lock joining"
                    // toggle) — only an explicit "End session" stops advertising, so keep
                    // advertising here even after the first connection lands.
                    listener.onPeerConnected(endpointId);
                    sendToPhysical(endpointId, CollabMessage.peerInfo(HOST_PEER_ID, myDisplayName));
                } else {
                    hostConnectionEndpointId = endpointId;
                    peers.put(HOST_PEER_ID, new PeerInfo(HOST_PEER_ID));
                    listener.onPeerConnected(HOST_PEER_ID);
                }
                // Both directions introduce themselves; the host also relays what it already
                // knows once this arrives (see handleIncoming).
                send(CollabMessage.peerInfo(host ? HOST_PEER_ID : "unused", myDisplayName));
                if (!viewing) send(CollabMessage.presence(host ? HOST_PEER_ID : "unused", false));
            } else if (result.getStatus().getStatusCode()
                    != ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                // The host end keeps waiting — one joiner failing to connect is not a reason to
                // take the QR code away from whoever tries next. The joiner has nothing left to
                // wait for, so for it this is the end of the attempt.
                if (host) {
                    Log.w(TAG, "incoming connection failed: " + result.getStatus());
                } else {
                    fail(Failure.CONNECT_FAILED, String.valueOf(result.getStatus()));
                }
            }
        }

        @Override
        public void onDisconnected(String endpointId) {
            if (host) {
                // An explicit "Leave" already removed this peer from the map (see
                // handleIncoming), so reaching here with the peer still present means the loss
                // was a real one.
                if (peers.remove(endpointId) != null) {
                    listener.onPeerDisconnected(endpointId);
                }
            } else if (endpointId.equals(hostConnectionEndpointId)) {
                hostConnectionEndpointId = null;
                boolean stillHadHost = peers.remove(HOST_PEER_ID) != null;
                peers.clear(); // the whole roster was only ever known through this connection
                if (stillHadHost) listener.onPeerDisconnected(HOST_PEER_ID);
            }
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() != Payload.Type.BYTES) return;
            byte[] bytes = payload.asBytes();
            if (bytes == null) return;
            try {
                handleIncoming(endpointId, CollabMessage.fromBytes(bytes));
            } catch (JSONException e) {
                Log.e(TAG, "Malformed collab message, dropped", e);
            }
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
            // Every message is one small BYTES payload, so there is no partial-progress case to
            // react to.
        }
    };

    /** Intercepts the protocol-level messages (peer info, explicit end/leave) before anything
     *  else sees them; everything else is forwarded to the listener untouched. {@code endpointId}
     *  is always the physical sender — the host's one connection to that peer, or (on a joiner)
     *  always the host itself. */
    private void handleIncoming(String endpointId, CollabMessage message) {
        switch (message.type) {
            case CollabMessage.TYPE_PEER_INFO:
                if (host) {
                    PeerInfo info = peers.get(endpointId);
                    if (info == null) return;
                    info.displayName = message.peerDisplayName;
                    listener.onPeerInfoUpdated(endpointId, message.peerDisplayName);
                    // Converge the whole star: tell the newcomer about every peer already known,
                    // and tell every existing peer about the newcomer.
                    for (Map.Entry<String, PeerInfo> entry : peers.entrySet()) {
                        if (entry.getKey().equals(endpointId) || entry.getValue().displayName == null) continue;
                        sendToPhysical(endpointId, CollabMessage.peerInfo(entry.getKey(), entry.getValue().displayName));
                        // Only the exceptions: PeerInfo starts out present, so saying so again
                        // would be a message that changes nothing.
                        if (!entry.getValue().viewing) {
                            sendToPhysical(endpointId, CollabMessage.presence(entry.getKey(), false));
                        }
                    }
                    sendToAllExcept(endpointId, CollabMessage.peerInfo(endpointId, message.peerDisplayName));
                } else {
                    // Relayed roster entry: peerId is canonical (HOST_PEER_ID or another
                    // joiner's host-assigned id), not necessarily this device's one connection.
                    PeerInfo info = peers.get(message.peerId);
                    if (info == null) {
                        info = new PeerInfo(message.peerId);
                        peers.put(message.peerId, info);
                    }
                    info.displayName = message.peerDisplayName;
                    listener.onPeerInfoUpdated(message.peerId, message.peerDisplayName);
                }
                break;
            case CollabMessage.TYPE_PRESENCE:
                if (host) {
                    // The sender speaks only for itself, so its own endpoint id is the canonical
                    // one regardless of what the message says — same rule as PEER_LEFT.
                    PeerInfo present = peers.get(endpointId);
                    if (present == null) return;
                    present.viewing = message.viewing;
                    listener.onPeerPresenceChanged(endpointId, message.viewing);
                    sendToAllExcept(endpointId, CollabMessage.presence(endpointId, message.viewing));
                } else {
                    PeerInfo present = peers.get(message.peerId);
                    if (present == null) {
                        present = new PeerInfo(message.peerId);
                        peers.put(message.peerId, present);
                    }
                    present.viewing = message.viewing;
                    listener.onPeerPresenceChanged(message.peerId, message.viewing);
                }
                break;
            case CollabMessage.TYPE_HOST_ENDED:
                peers.clear();
                listener.onSessionEndedByHost();
                break;
            case CollabMessage.TYPE_PEER_LEFT:
                if (host) {
                    // The sender itself is leaving; its own physical endpoint id is the
                    // canonical id to remove and to announce, regardless of message content.
                    peers.remove(endpointId);
                    sendToAllExcept(endpointId, CollabMessage.peerLeft(endpointId));
                    client.disconnectFromEndpoint(endpointId);
                    listener.onPeerLeft(endpointId);
                } else {
                    peers.remove(message.peerId);
                    listener.onPeerLeft(message.peerId);
                }
                break;
            default:
                listener.onMessage(endpointId, message);
                break;
        }
    }

    /**
     * Says whether this device has the board open, to everyone in the session.
     *
     * <p>The roster is a list of people to draw with, and someone who has closed the whiteboard is
     * not one — they are merely still connected. Kept as state rather than only sent, so a peer
     * who connects while this device is away is told the truth on arrival.
     */
    public void setViewing(boolean nowViewing) {
        if (viewing == nowViewing) return;
        viewing = nowViewing;
        send(CollabMessage.presence(host ? HOST_PEER_ID : "unused", nowViewing));
    }

    public boolean isConnected() {
        return hasAnyPeer();
    }

    public boolean hasAnyPeer() {
        return !peers.isEmpty();
    }

    public Collection<PeerInfo> currentPeers() {
        return new ArrayList<>(peers.values());
    }

    /** Broadcasts to every connected peer: on the host, every physical connection; on a joiner,
     *  the single connection to the host (there is never anywhere else to send). */
    public void send(CollabMessage message) {
        if (host) {
            for (String endpointId : peers.keySet()) sendToPhysical(endpointId, message);
        } else if (hostConnectionEndpointId != null) {
            sendToPhysical(hostConnectionEndpointId, message);
        }
    }

    /** Host-only: relay to one specific physically-connected peer. */
    public void sendTo(String peerId, CollabMessage message) {
        if (!host || !peers.containsKey(peerId)) return;
        sendToPhysical(peerId, message);
    }

    /** Host-only: relay to every physically-connected peer except one — the P2P_STAR relay this
     *  session needs since joiners never see each other directly. */
    public void sendToAllExcept(String excludePeerId, CollabMessage message) {
        if (!host) return;
        for (String endpointId : peers.keySet()) {
            if (endpointId.equals(excludePeerId)) continue;
            sendToPhysical(endpointId, message);
        }
    }

    private void sendToPhysical(String endpointId, CollabMessage message) {
        byte[] bytes = message.toBytes();
        // Nearby drops an oversized BYTES payload on the floor and tells the sender nothing, so
        // say it here rather than letting the peer silently never receive it. SNAPSHOT is chunked
        // to stay under this (see CollabMessage.snapshotChunks); anything else reaching the limit
        // is a single item large enough to be worth knowing about.
        if (bytes.length > ConnectionsClient.MAX_BYTES_DATA_SIZE) {
            Log.e(TAG, "message type " + message.type + " is " + bytes.length + " bytes, over "
                    + "Nearby's " + ConnectionsClient.MAX_BYTES_DATA_SIZE + " limit — not sent");
            return;
        }
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnFailureListener(e ->
                        Log.e(TAG, "sendPayload failed (type " + message.type + ")", e));
    }

    /** Host-only: ends the session for everyone, telling joiners explicitly rather than letting
     *  them see a bare disconnect. */
    public void endSession() {
        if (host) send(CollabMessage.hostEnded());
        stop();
    }

    /** Joiner-only: leaves this session without ending it for the others. */
    public void leaveSession() {
        if (!host) send(CollabMessage.peerLeft(HOST_PEER_ID));
        stop();
    }

    /** Ends the session on this device. The peer sees this as a normal disconnect — prefer
     *  {@link #endSession()}/{@link #leaveSession()} for the explicit-exit case so peers get an
     *  unambiguous reason instead. */
    public void stop() {
        stopped = true;
        cancelJoinTimeout();
        client.stopAllEndpoints();
        client.stopAdvertising();
        client.stopDiscovery();
        peers.clear();
        hostConnectionEndpointId = null;
    }
}
