package tel.schich.libdatachannel;

import static tel.schich.libdatachannel.Util.wrapError;

import java.util.Map;
import java.util.Objects;


// Tracks are not implemented yet!
@Deprecated
public class Track implements AutoCloseable {

    private final PeerConnection peer;
    private final int trackHandle;

    public Track(final PeerConnection peer, final int trackHandle) {
        this.peer = peer;
        this.trackHandle = trackHandle;
    }

    public PeerConnection peer() {
        return peer;
    }

    // After this function has been called, tr must not be used in a function call anymore. This function will block until all scheduled callbacks
    // of tr return (except the one this function might be called in) and no other callback will be called for tr after it returns.
    public String description() {
        return LibDataChannelNative.rtcGetTrackDescription(trackHandle);
    }

    // Retrieves the mid (media indentifier) of a Track.
    public String mediaId() {
        return LibDataChannelNative.rtcGetTrackMid(trackHandle);
    }

    // Retrieves the direction of a Track.
    public Direction direction() {
        return Direction.of(LibDataChannelNative.rtcGetTrackDirection(trackHandle));
    }

    @Override
    public void close() {
        wrapError(LibDataChannelNative.rtcDeleteTrack(trackHandle));
        peer.dropTrackState(trackHandle);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Track)) return false;
        Track track = (Track) o;
        return trackHandle == track.trackHandle;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(trackHandle);
    }

    public enum Direction {
        RTC_DIRECTION_UNKNOWN(0),
        RTC_DIRECTION_SENDONLY(1),
        RTC_DIRECTION_RECVONLY(2),
        RTC_DIRECTION_SENDRECV(3),
        RTC_DIRECTION_INACTIVE(4),
        ;

        public static final Direction DEFAULT = RTC_DIRECTION_UNKNOWN;

        private static final Map<Integer, Direction> MAP = Util.mappedEnum(Direction.values(), s -> s.direction);
        final int direction;

        Direction(int direction) {
            this.direction = direction;
        }

        static Direction of(final int direction) {
            return MAP.get(direction);
        }
    }

    public enum Codec {
        RTC_CODEC_H264(0),
        RTC_CODEC_VP8(1),
        RTC_CODEC_VP9(2),
        RTC_CODEC_H265(3),
        RTC_CODEC_AV1(4),
        RTC_CODEC_OPUS(128),
        RTC_CODEC_PCMU(129),
        RTC_CODEC_PCMA(130),
        RTC_CODEC_AAC(131),
        ;

        public static final Codec DEFAULT = RTC_CODEC_H264;

        private static final Map<Integer, Codec> MAP = Util.mappedEnum(Codec.values(), s -> s.codec);
        final int codec;

        Codec(int codec) {
            this.codec = codec;
        }

        static Codec of(final int codec) {
            return MAP.get(codec);
        }
    }
}
