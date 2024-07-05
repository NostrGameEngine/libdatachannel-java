package tel.schich.libdatachannel;

import tel.schich.libdatachannel.util.Util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PeerConnectionConfiguration {
    public static final int AUTO_MTU = 0;
    public static final PeerConnectionConfiguration DEFAULT = new PeerConnectionConfiguration(Collections.emptyList(),
            null,
            null,
    CertificateType.RTC_CERTIFICATE_DEFAULT,
    IceTransportPolicy.RTC_TRANSPORT_POLICY_ALL,
            false,
            false,
            false,
            false,
            (short) 0,
            (short) 0,
    AUTO_MTU,
            0);

    final List<URI> iceServers;
    final URI proxyServer;
    final InetAddress bindAddress;
    final CertificateType certificateType;
    final IceTransportPolicy iceTransportPolicy;
    final boolean enableIceTcp;
    final boolean enableIceUdpMux;
    final boolean disableAutoNegotiation;
    final boolean forceMediaTransport;
    final short portRangeBegin;
    final short portRangeEnd;
    final int mtu;
    final int maxMessageSize;

    private PeerConnectionConfiguration(List<URI> iceServers, URI proxyServer, InetAddress bindAddress, CertificateType certificateType, IceTransportPolicy iceTransportPolicy, boolean enableIceTcp, boolean enableIceUdpMux, boolean disableAutoNegotiation, boolean forceMediaTransport, short portRangeBegin, short portRangeEnd, int mtu, int maxMessageSize) {
        this.iceServers = iceServers;
        this.proxyServer = proxyServer;
        this.bindAddress = bindAddress;
        this.certificateType = certificateType;
        this.iceTransportPolicy = iceTransportPolicy;
        this.enableIceTcp = enableIceTcp;
        this.enableIceUdpMux = enableIceUdpMux;
        this.disableAutoNegotiation = disableAutoNegotiation;
        this.forceMediaTransport = forceMediaTransport;
        this.portRangeBegin = portRangeBegin;
        this.portRangeEnd = portRangeEnd;
        this.mtu = mtu;
        this.maxMessageSize = maxMessageSize;
    }

    /**
     * (optional): an array of pointers on null-terminated ICE server URIs (NULL if unused)
     *
     * Each entry in iceServers must match the format [("stun"|"turn"|"turns") (":"|"://")][username ":" password "@"]hostname[":" port]["?transport=" ("udp"|"tcp"|"tls")]. The default scheme is STUN, the default port is 3478 (5349 over TLS), and the default transport is UDP. For instance, a STUN server URI could be mystunserver.org, and a TURN server URI could be turn:myuser:12345678@turnserver.org. Note transports TCP and TLS are only available for a TURN server with libnice as ICE backend and govern only the TURN control connection, meaning relaying is always performed over UDP.
     */
    public PeerConnectionConfiguration withIceServers(Collection<URI> iceServers) {
        return new PeerConnectionConfiguration(new ArrayList<>(iceServers), proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public List<URI> iceServers() {
        return this.iceServers;
    }

    /**
     * (optional): if non-NULL, specifies the proxy server URI to use for TURN relaying (ignored with libjuice as ICE backend)
     * TODO docs
     * The proxyServer URI, if present, must match the format [("http"|"socks5") (":"|"://")][username ":" password "@"]hostname["    :" port]. The default scheme is HTTP, and the default port is 3128 for HTTP or 1080 for SOCKS5.
     *
     * If the username or password of an URI contains reserved special characters, they must be percent-encoded. In particular, ":" must be encoded as "%3A" and "@" must by encoded as "%40".
     */
    public PeerConnectionConfiguration withProxyServer(URI proxy) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public Optional<URI> proxyServer() {
        return Optional.of(proxyServer);
    }

    /**
     * (optional): if non-NULL, bind only to the given local address (ignored with libnice as ICE backend)
     */
    public PeerConnectionConfiguration withBindAddress(InetAddress bindAddress) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public Optional<InetAddress> bindAddress() {
        return Optional.of(bindAddress);
    }

    /**
     * (optional): certificate type, either RTC_CERTIFICATE_ECDSA or RTC_CERTIFICATE_RSA (0 or RTC_CERTIFICATE_DEFAULT if default)
     */
    public PeerConnectionConfiguration withCertificateType(CertificateType certificateType) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public CertificateType certificateType() {
        return certificateType;
    }

    /**
     * (optional): ICE transport policy, if set to RTC_TRANSPORT_POLICY_RELAY, the PeerConnection will emit only relayed candidates (0 or
     * RTC_TRANSPORT_POLICY_ALL if default)
     */
    public PeerConnectionConfiguration iceTransportPolicy(IceTransportPolicy policy) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public IceTransportPolicy iceTransportPolicy() {
        return iceTransportPolicy;
    }

    /**
     * if true, generate TCP candidates for ICE (ignored with libjuice as ICE backend)
     */
    public PeerConnectionConfiguration withEnableIceTcp(boolean enableIceTcp) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public boolean enableIceTcp() {
        return this.enableIceTcp;
    }

    /**
     * if true, connections are multiplexed on the same UDP port (should be combined with portRangeBegin and portRangeEnd, ignored with libnice as ICE
     * backend)
     */
    public PeerConnectionConfiguration withEnableIceUdpMux(boolean enableIceUdpMux) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public boolean enableIceUdpMux() {
        return enableIceUdpMux;
    }

    /**
     * if true, the user is responsible for calling rtcSetLocalDescription after creating a Data Channel and after setting the remote description
     */
    public PeerConnectionConfiguration withDisableAutoNegotiation(boolean disableAutoNegotiation) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public boolean disableAutoNegotiation() {
        return disableAutoNegotiation;
    }

    /**
     * if true, the connection allocates the SRTP media transport even if no tracks are present (necessary to add tracks during later renegotiation)
     */
    public PeerConnectionConfiguration withForceMediaTransport(boolean forceMediaTransport) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public boolean forceMediaTransport() {
        return forceMediaTransport;
    }


    /**
     * (optional): first port (included) of the allowed local port range (0 if unused)
     */
    public PeerConnectionConfiguration withPortRangeBegin(short portRangeBegin) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public short portRangeBegin() {
        return portRangeBegin;
    }


    /**
     * (optional): last port (included) of the allowed local port (0 if unused)
     */
    public PeerConnectionConfiguration withPortRangeEnd(short portRangeEnd) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public short portRangeEnd() {
        return portRangeEnd;
    }

    /**
     * (optional): manually set the Maximum Transfer Unit (MTU) for the connection (0 if automatic)
     */
    public PeerConnectionConfiguration withMtu(int mtu) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public int mtu() {
        return mtu;
    }

    /**
     * (optional): manually set the local maximum message size for Data Channels (0 if default)
     */
    public PeerConnectionConfiguration withMaxMessageSize(int maxMessageSize) {
        return new PeerConnectionConfiguration(iceServers, proxyServer, bindAddress, certificateType, iceTransportPolicy, enableIceTcp, enableIceUdpMux, disableAutoNegotiation, forceMediaTransport, portRangeBegin, portRangeEnd, mtu, maxMessageSize);
    }

    public int maxMessageSize() {
        return maxMessageSize;
    }

    public static Collection<URI> uris(String... uris) {
        try {
            List<URI> out = new ArrayList<>(uris.length);
            for (String uri : uris) {
                out.add(new URI(uri));
            }
            return out;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public enum CertificateType {
        RTC_CERTIFICATE_DEFAULT(0), // ECDSA
        RTC_CERTIFICATE_ECDSA(1),
        RTC_CERTIFICATE_RSA(2);

        private static final Map<Integer, CertificateType> MAP = Util.mappedEnum(CertificateType.values(), s -> s.state);
        final int state;

        CertificateType(int state) {
            this.state = state;
        }

        public static CertificateType of(final int state) {
            return MAP.get(state);
        }
    }

    public enum IceTransportPolicy {
        RTC_TRANSPORT_POLICY_ALL(0),
        RTC_TRANSPORT_POLICY_RELAY(1),
        ;
        private static final Map<Integer, IceTransportPolicy> MAP = Util.mappedEnum(IceTransportPolicy.values(), s -> s.state);
        final int state;

        IceTransportPolicy(int state) {
            this.state = state;
        }

        public static IceTransportPolicy of(final int state) {
            return MAP.get(state);
        }

    }
}
