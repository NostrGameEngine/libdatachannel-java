package tel.schich.libdatachannel;

import tel.schich.jniaccess.JNIAccess;

import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

class PeerConnectionListener {
    private static final Logger LOGGER = Logger.getLogger(PeerConnectionListener.class.getName());

    private final PeerConnection peer;

    public PeerConnectionListener(final PeerConnection peer) {
        this.peer = peer;
    }

    @JNIAccess
    void onLocalDescription(String sdp, String type) {
        final SessionDescriptionType mappedType = SessionDescriptionType.of(type);
        if (mappedType == null) {
            LOGGER.log(Level.SEVERE, "Unknown SDP type {0}!", type);
            return;
        }
        peer.onLocalDescription.invoke(h -> h.handleDescription(peer, sdp, mappedType));
    }

    @JNIAccess
    void onLocalCandidate(String candidate, String mediaId) {
        peer.onLocalCandidate.invoke(h -> h.handleCandidate(peer, candidate, mediaId));
    }

    @JNIAccess
    void onStateChange(int state) {
        final PeerState mappedState = PeerState.of(state);
        if (mappedState == null) {
            LOGGER.log(Level.SEVERE, "Unknown state {0}!", state);
            return;
        }
        peer.onStateChange.invoke(h -> h.handleChange(peer, mappedState));
    }

    @JNIAccess
    void onIceStateChange(int iceState) {
        final IceState mappedState = IceState.of(iceState);
        if (mappedState == null) {
            LOGGER.log(Level.SEVERE, "Unknown ICE state {0}!", iceState);
            return;
        }
        peer.onIceStateChange.invoke(h -> h.handleChange(peer, mappedState));
    }

    @JNIAccess
    void onGatheringStateChange(int gatheringState) {
        final GatheringState mappedState = GatheringState.of(gatheringState);
        if (mappedState == null) {
            LOGGER.log(Level.SEVERE, "Unknown gathering state {0}!", gatheringState);
            return;
        }
        peer.onGatheringStateChange.invoke(h -> h.handleChange(peer, mappedState));
    }

    @JNIAccess
    void onSignalingStateChange(int signalingState) {
        final SignalingState mappedState = SignalingState.of(signalingState);
        if (mappedState == null) {
            LOGGER.log(Level.SEVERE, "Unknown signaling state {0}!", signalingState);
            return;
        }
        peer.onSignalingStateChange.invoke(h -> h.handleChange(peer, mappedState));
    }

    @JNIAccess
    void onDataChannel(int channelHandle) {
        final DataChannel channel = peer.newChannel(channelHandle);
        peer.onDataChannel.invoke(h -> h.handleChannel(peer, channel));
    }

    @JNIAccess
    void onTrack(int trackHandle) {
        final Track state = peer.newTrack(trackHandle);
        peer.onTrack.invoke(h -> h.handleTrack(peer, state));
    }

    private <T> void invokeWithChannel(int handle, Function<DataChannel, EventListenerContainer<T>> listeners, BiConsumer<T, DataChannel> consumer) {
        final DataChannel channel = peer.channel(handle);
        if (channel == null) {
            LOGGER.log(Level.WARNING, "Received event for unknown data channel {0}!", handle);
            return;
        }
        listeners.apply(channel).invoke(h -> consumer.accept(h, channel));
    }

    @JNIAccess
    void onChannelOpen(int channelHandle) {
        invokeWithChannel(channelHandle, s -> s.onOpen, DataChannelCallback.Open::onOpen);
    }

    @JNIAccess
    void onChannelClosed(int channelHandle) {
        invokeWithChannel(channelHandle, s -> s.onClosed, DataChannelCallback.Closed::onClosed);
    }

    @JNIAccess
    void onChannelError(int channelHandle, String error) {
        invokeWithChannel(channelHandle, s -> s.onError, (h, ch) -> h.onError(ch, error));
    }

    @JNIAccess
    void onChannelTextMessage(int channelHandle, String message) {
        invokeWithChannel(channelHandle, s -> s.onMessage, (h, ch) -> h.onText(ch, message));
    }

    @JNIAccess
    void onChannelBinaryMessage(int channelHandle, ByteBuffer message) {
        invokeWithChannel(channelHandle, s -> s.onMessage, (h, ch) -> h.onBinary(ch, message));
    }

    @JNIAccess
    void onChannelBufferedAmountLow(int channelHandle) {
        invokeWithChannel(channelHandle, s -> s.onBufferedAmountLow, DataChannelCallback.BufferedAmountLow::onBufferedAmountLow);
    }

    @JNIAccess
    void onChannelAvailable(int channelHandle) {
        invokeWithChannel(channelHandle, s -> s.onAvailable, DataChannelCallback.Available::onAvailable);
    }
}
