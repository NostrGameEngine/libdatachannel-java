package generated;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;

import java.util.Arrays;
import java.util.List;
/**
 * <i>native declaration : /home/phillip/Code/libdatachannel-java/libdatachannel/include/rtc/rtc.h:120</i><br>
 * This file was autogenerated by <a href="http://jnaerator.googlecode.com/">JNAerator</a>,<br>
 * a tool written by <a href="http://ochafik.com/">Olivier Chafik</a> that <a href="http://code.google.com/p/jnaerator/wiki/CreditsAndLicense">uses a few opensource projects.</a>.<br>
 * For help, please visit <a href="http://nativelibs4java.googlecode.com/">NativeLibs4Java</a> , <a href="http://rococoa.dev.java.net/">Rococoa</a>, or <a href="http://jna.dev.java.net/">JNA</a>.
 */
public class rtcConfiguration extends Structure {
	/** C type : const char** */
	public PointerByReference iceServers;
	public int iceServersCount;
	/**
	 * libnice only<br>
	 * C type : const char*
	 */
	public Pointer proxyServer;
	/**
	 * libjuice only, NULL means any<br>
	 * C type : const char*
	 */
	public Pointer bindAddress;
	/**
	 * @see rtcCertificateType<br>
	 * C type : rtcCertificateType
	 */
	public int certificateType;
	/**
	 * @see rtcTransportPolicy<br>
	 * C type : rtcTransportPolicy
	 */
	public int iceTransportPolicy;
	/** libnice only */
	public byte enableIceTcp;
	/** libjuice only */
	public byte enableIceUdpMux;
	public byte disableAutoNegotiation;
	public byte forceMediaTransport;
	/** 0 means automatic */
	public short portRangeBegin;
	/** 0 means automatic */
	public short portRangeEnd;
	/** <= 0 means automatic */
	public int mtu;
	/** <= 0 means default */
	public int maxMessageSize;
	public rtcConfiguration() {
		super();
	}
	protected List<String> getFieldOrder() {
		return Arrays.asList("iceServers", "iceServersCount", "proxyServer", "bindAddress", "certificateType", "iceTransportPolicy", "enableIceTcp", "enableIceUdpMux", "disableAutoNegotiation", "forceMediaTransport", "portRangeBegin", "portRangeEnd", "mtu", "maxMessageSize");
	}
	public rtcConfiguration(Pointer peer) {
		super(peer);
	}
	public static class ByReference extends rtcConfiguration implements Structure.ByReference {
		
	};
	public static class ByValue extends rtcConfiguration implements Structure.ByValue {
		
	};
}
