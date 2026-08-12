package mse.quill.collab;

import android.content.Context;
import android.util.Log;

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

    public interface Listener {
        /** The other device is connected and ready to exchange messages. */
        void onPeerConnected();
        /** The session ended, whether by choice or by losing the connection. */
        void onPeerDisconnected();
        void onMessage(CollabMessage message);
        void onError(String reason);
    }

    private final ConnectionsClient client;
    private final Listener listener;
    private final boolean host;
    private String peerEndpointId;
    private boolean stopped;

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
                    listener.onError("Could not start hosting: " + e.getMessage());
                });
    }

    private void startDiscovery(String wantedToken) {
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
                            listener.onError("Could not connect: " + e.getMessage());
                        });
            }

            @Override
            public void onEndpointLost(String endpointId) {
                // Nothing to do: either we already connected, or the host went away before we did.
            }
        }, options).addOnFailureListener(e -> {
            Log.e(TAG, "startDiscovery failed", e);
            listener.onError("Could not search for a session: " + e.getMessage());
        });
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
                peerEndpointId = endpointId;
                if (host) client.stopAdvertising();
                listener.onPeerConnected();
            } else if (result.getStatus().getStatusCode()
                    != ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                listener.onError("Connection failed");
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
        client.stopAllEndpoints();
        client.stopAdvertising();
        client.stopDiscovery();
    }
}
