package tel.schich.libdatachannel;

import static generated.DatachannelLibrary.INSTANCE;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class PeerConnection implements AutoCloseable {
    private final int peerHandle;
    private Map<Integer, DataChannel> channels = new HashMap<>();

    private PeerConnection(int peerHandle) {
        this.peerHandle = peerHandle;
    }

    private static byte[] iceUrisToCStrings(Collection<URI> uris) {
        if (uris == null || uris.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream iceServers = new ByteArrayOutputStream();
        for (URI server : uris) {
            iceServers.writeBytes(server.toASCIIString().getBytes(StandardCharsets.US_ASCII));
            iceServers.write(0);
        }
        return iceServers.toByteArray();
    }

    /**
     * Creates a Peer Connection.
     *
     * Remember to {@link #close()} when done.
     *
     * @param config the peer configuration
     * @return the peer connection
     */
    public static PeerConnection createPeer(PeerConnectionConfiguration config) {
        int result = LibDataChannelNative.rtcCreatePeerConnection(
                iceUrisToCStrings(config.iceServers),
                config.proxyServer.toASCIIString(),
                config.bindAddress.toString(),
                config.certificateType.state,
                config.iceTransportPolicy.state,
                config.enableIceTcp,
                config.enableIceUdpMux,
                config.disableAutoNegotiation,
                config.forceMediaTransport,
                config.portRangeBegin,
                config.portRangeEnd,
                config.mtu,
                config.maxMessageSize);
        if (result < 0) {
            throw new IllegalStateException("Error Code: " + result); // TODO what are error codes
        }
        return new PeerConnection(result);
    }

    /**
     * If it is not already closed, the Peer Connection is implicitly closed before being deleted. After this function has been called, pc must not be
     * used in a function call anymore. This function will block until all scheduled callbacks of pc return (except the one this function might be
     * called in) and no other callback will be called for pc after it returns.
     */
    @Override
    public void close() {
        // TODO close channels explicitly?

        // Blocks until all callbacks have returned (except a callback calling this)
        int result = LibDataChannelNative.rtcClosePeerConnection(this.peerHandle);
        if (result < 0) {
            throw new IllegalStateException("Error closing peer connection: " + this.peerHandle + " err: " + result);
        }
        result = LibDataChannelNative.rtcDeletePeerConnection(this.peerHandle);
        if (result < 0) {
            throw new IllegalStateException("Error deleting peer connection: " + this.peerHandle + " err: " + result);
        }
    }

    /**
     * Closes all Data Channels.
     */
    public void closeChannels() {
        for (final DataChannel value : channels.values()) {
            value.close();
        }
    }

    public void onLocalDescription(PeerConnectionCallback.LocalDescription cb) {
        INSTANCE.rtcSetLocalDescriptionCallback(this.peerHandle, (pc, sdp, type, ptr) -> {
            cb.handleDescription(this, sdp.getString(0), type.getString(0));
        });
    }

    public void onLocalCandidate(PeerConnectionCallback.LocalCandidate cb) {
        INSTANCE.rtcSetLocalCandidateCallback(this.peerHandle, (pc, cand, mid, ptr) -> {
            cb.handleCandidate(this, cand.getString(0), mid.getString(0));
        });
    }


    /**
     * Initiates the handshake process.
     * <p>
     * Following this call, the local description callback will be called with the local description, which must be sent to the remote peer by the
     * user's method of choice.
     * <p>
     * Note this call is implicit after {@link #setRemoteDescription} and {@link #createDataChannel} or {@link #createDataChannelEx} if
     * disableAutoNegotiation was not set on Peer Connection creation.
     *
     * @param type (optional): type of the description ("offer", "answer", "pranswer", or "rollback") or NULL for autodetection.
     */
    public void setLocalDescription(String type) {
        int result = LibDataChannelNative.rtcSetLocalDescription(peerHandle, type);
        if (result < 0) {
            throw new NativeOperationException(result);
        }
    }

    /**
     * Retrieves the current local description in SDP format.
     *
     * @return the current local description
     */
    public String localDescription() {
        return LibDataChannelNative.rtcGetLocalDescription(peerHandle);
    }

    /**
     * Retrieves the current local description type as string. See {@link #setLocalDescription}.
     *
     * @return the current local description type
     */
    public String localDescriptionType() {
        return LibDataChannelNative.rtcGetLocalDescriptionType(peerHandle);
    }

    /**
     * Sets the remote description received from the remote peer by the user's method of choice. The remote description may have candidates or not.
     * <p>
     * If the remote description is an offer and disableAutoNegotiation was not set in rtcConfiguration, the library will automatically answer by
     * calling rtcSetLocalDescription internally. Otherwise, the user must call it to answer the remote description.
     *
     * @param sdp  the remote Session Description Protocol
     * @param type (optional): type of the description ("offer", "answer", "pranswer", or "rollback") or NULL for autodetection.
     */
    public void setRemoteDescription(String sdp, String type) {
        final int result = LibDataChannelNative.rtcSetRemoteDescription(peerHandle, sdp, type);
        if (result < 0) {
            throw new NativeOperationException(result);
        }
    }

    /**
     * Retrieves the current remote description in SDP format.
     *
     * @return the current remote description
     */
    public String remoteDescription() {
        return LibDataChannelNative.rtcGetRemoteDescription(peerHandle);
    }


    /**
     * Retrieves the current remote description type as string.
     *
     * @return the current remote description type
     */
    public String remoteDescriptionType() {
        return LibDataChannelNative.rtcGetRemoteDescriptionType(this.peerHandle);
    }

    /**
     * Adds a trickled remote candidate received from the remote peer by the user's method of choice.
     * The Peer Connection must have a remote description set.
     *
     * @param candidate a null-terminated SDP string representing the candidate (with or without the "a=" prefix)
     */
    public void addRemoteCandidate(String candidate) {
        var code = INSTANCE.rtcAddRemoteCandidate(this.peerHandle, candidate, null);
    }

    /**
     * Adds a trickled remote candidate received from the remote peer by the user's method of choice.
     * The Peer Connection must have a remote description set.
     *
     * @param candidate a null-terminated SDP string representing the candidate (with or without the "a=" prefix)
     * @param mid       (optional): a null-terminated string representing the mid of the candidate in the remote SDP description or NULL for
     *                  autodetection
     */
    public void addRemoteCandidate(String candidate, String mid) {
        var code = INSTANCE.rtcAddRemoteCandidate(this.peerHandle, candidate, mid);
    }

    private static InetSocketAddress parseAddress(String rawAddress) {
        int colonIndex = rawAddress.lastIndexOf(':');
        String ip = rawAddress.substring(0, colonIndex);
        int port = Integer.parseInt(rawAddress.substring(colonIndex + 1));
        return InetSocketAddress.createUnresolved(ip, port);
    }

    /**
     * Retrieves the current local address, i.e. the network address of the currently selected local candidate. The address will have the format
     * "IP_ADDRESS:PORT", where IP_ADDRESS may be either IPv4 or IPv6. The call might fail if the PeerConnection is not in state RTC_CONNECTED, and
     * the address might change after connection.
     *
     * @return the local address
     */
    public InetSocketAddress localAddress() {
        return parseAddress(LibDataChannelNative.rtcGetLocalAddress(this.peerHandle));
    }

    /**
     * Retrieves the current remote address, i.e. the network address of the currently selected remote candidate. The address will have the format
     * "IP_ADDRESS:PORT", where IP_ADDRESS may be either IPv4 or IPv6. The call may fail if the state is not RTC_CONNECTED, and the address might
     * change after connection.
     *
     * @return the remote address
     */
    public InetSocketAddress remoteAddress() {
        return parseAddress(LibDataChannelNative.rtcGetRemoteAddress(this.peerHandle));
    }

    /**
     * Retrieves the currently selected candidate pair. The call may fail if the state is not RTC_CONNECTED, and the selected candidate pair might
     * change after connection.
     */
    // TODO rtcGetSelectedCandidatePair?


    /**
     * Retrieves the maximum stream ID a Data Channel may use. It is useful to create user-negotiated Data Channels with negotiated=true and
     * manualStream=true. The maximum is negotiated during connection, therefore the final value after connection might be lower than before
     * connection if the remote maximum is lower.
     *
     * @return maximum stream ID
     */
    public int maxDataChannelStream() {
        return INSTANCE.rtcGetMaxDataChannelStream(this.peerHandle);
    }

    /**
     * Retrieves the maximum message size for data channels on the peer connection as negotiated with the remote peer.
     *
     * @return the maximum message size
     */
    public int remoteMaxMessageSize() {
        return INSTANCE.rtcGetRemoteMaxMessageSize(this.peerHandle);
    }

    public void onStateChange(PeerConnectionCallback.StateChange cb) {
        if (cb == null) {
            var code = INSTANCE.rtcSetStateChangeCallback(this.peerHandle, null);
            return;
        }
        var code = INSTANCE.rtcSetStateChangeCallback(this.peerHandle, (pc, state, ptr) -> {
            cb.handleChange(this, PeerState.of(state));
        });
    }

    public void onGatheringStateChange(PeerConnectionCallback.GatheringStateChange cb) {
        INSTANCE.rtcSetGatheringStateChangeCallback(this.peerHandle, (pc, state, ptr) -> {
            cb.handleGatherChange(this, GatheringState.of(state));
        });
    }

    public void onDataChannel(PeerConnectionCallback.DataChannel cb) {
        final var code = INSTANCE.rtcSetDataChannelCallback(this.peerHandle, (pc, dc, ptr) -> {
            cb.handleDC(this, channels.get(dc));
        });
    }

    public void onTrack(PeerConnectionCallback.Track cb) {
        INSTANCE.rtcSetTrackCallback(this.peerHandle, (pc, tr, ptr) -> {
            cb.handleTrack(this, tr);
        });
    }


    public void setAnswer(String sdp) {
        this.setRemoteDescription(sdp, "answer");
    }


    /**
     * Adds a Data Channel on a Peer Connection. The Peer Connection does not need to be connected, however, the Data Channel will be open only when
     * the Peer Connection is connected.
     * <p>
     * rtcDataChannel() is equivalent to rtcDataChannelEx() with settings set to ordered, reliable, non-negotiated, with automatic stream ID selection
     * (all flags set to false), and protocol set to an empty string.
     *
     * @param label a user-defined UTF-8 string representing the Data Channel name
     * @return the created data channel
     */
    public DataChannel createDataChannel(String label) {
        final var dc = INSTANCE.rtcCreateDataChannel(this.peerHandle, label);
        if (dc < 0) {
            throw new IllegalStateException("Error: " + dc);
        }
        final var channel = new DataChannel(this, dc);
        this.channels.put(dc, channel);
        return channel;
    }

    /**
     * Adds a Data Channel on a Peer Connection. The Peer Connection does not need to be connected, however, the Data Channel will be open only when
     * the Peer Connection is connected.
     *
     * @param label a user-defined UTF-8 string representing the Data Channel name
     * @param init  a structure of initialization settings
     * @return the created data channel
     */
    public DataChannel createDataChannelEx(String label, DataChannelInitSettings init) {

        final var dc = INSTANCE.rtcCreateDataChannelEx(this.peerHandle, label, init.innerInit);
        if (dc < 0) {
            throw new IllegalStateException("Error: " + dc);
        }
        final var channel = new DataChannel(this, dc);
        this.channels.put(dc, channel);
        return channel;
    }

//    public boolean isClosed() {
//        return this.peerHandle == null;
//    }

}
