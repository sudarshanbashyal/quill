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

import java.util.UUID;

/**
 * One live whiteboard session over Nearby Connections, host or joiner. Per requirements.md Epic
 * C, "the token is the interface" — a Nearby {@code endpointId} is assigned locally by the
 * discovering device, so it can't be handed over out of band. What travels instead is a random
 * session token (carried by a QR code today, an NFC tap later) that the host advertises under and
 * the joiner matches during discovery. Because only someone who has actually seen that token can
 * find the right advertisement, the host accepts every incoming connection request without a
 * separate approval dialog.
 *
 * <p>{@code SERVICE_ID} is fixed and app-wide; the token is what disambiguates one session from
 * every other Quill user advertising nearby.
 */
public class CollabSession {

    private static final String TAG = "CollabSession";
    private static final String SERVICE_ID = "mse.quill.whiteboard";
    private static final Strategy STRATEGY = Strategy.P2P_STAR;

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
        /** The other device is connected and ready to exchange messages. */
        void onPeerConnected();
        /** The session ended, whether by choice or by losing the connection. */
        void onPeerDisconnected();
        void onMessage(CollabMessage message);
        /** @param detail the underlying message, for logs rather than for the screen. */
        void onError(Failure failure, String detail);
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
    private String peerEndpointId;
    private boolean stopped;

    /** Runs {@link #JOIN_TIMEOUT_MS} after a join starts, cancelled the moment anything happens. */
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable joinTimeout;

    private CollabSession(Context context, Listener listener, boolean host) {
        this.client = Nearby.getConnectionsClient(context.getApplicationContext());
        this.listener = listener;
        this.host = host;
    }

    /** Starts advertising under a freshly minted token and returns it, for a QR code to carry. */
    public static CollabSession host(Context context, Listener listener) {
        CollabSession session = new CollabSession(context, listener, true);
        String token = UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        session.startAdvertising(token);
        return session;
    }

    public static CollabSession join(Context context, String token, Listener listener) {
        CollabSession session = new CollabSession(context, listener, false);
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
                peerEndpointId = endpointId;
                if (host) client.stopAdvertising();
                listener.onPeerConnected();
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
            if (endpointId.equals(peerEndpointId)) {
                peerEndpointId = null;
                listener.onPeerDisconnected();
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
                listener.onMessage(CollabMessage.fromBytes(bytes));
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

    public boolean isConnected() {
        return peerEndpointId != null;
    }

    public void send(CollabMessage message) {
        if (peerEndpointId == null) return;
        client.sendPayload(peerEndpointId, Payload.fromBytes(message.toBytes()));
    }

    /** Ends the session on this device. The peer sees this as a normal disconnect. */
    public void stop() {
        stopped = true;
        cancelJoinTimeout();
        client.stopAllEndpoints();
        client.stopAdvertising();
        client.stopDiscovery();
    }
}
