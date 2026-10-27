package tel.schich.libdatachannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;

class NativeAllocatorTest {
    private static native int allocationCoverage(ByteBuffer applicationBuffer);

    @Test
    void routesDesktopNativeAllocationsThroughThePrivateSecureAllocator() {
        LibDataChannel.initialize();
        assertTrue(LibDataChannel.getInnerAllocator().startsWith("mimalloc-secure+"));
        ByteBuffer applicationBuffer = ByteBuffer.allocateDirect(8192);
        applicationBuffer.putInt(0, 0x12345678);
        assertEquals(2047, allocationCoverage(applicationBuffer),
                "Coverage bits: JNI C=1, C++ new=2, RTC message=4, C++ runtime=8, juice=16, SCTP=32, OpenSSL=64, secure build=128, application heap separate=256, C++ allocation failure=512, exception storage=1024");
        assertEquals(0x12345678, applicationBuffer.getInt(0));
    }
}
