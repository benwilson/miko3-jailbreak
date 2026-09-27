package com.example.conexantapi;

/**
 * JNI stub for the vendor's Conexant DSP backend in libconexant_dsp_lib.so
 * (meeting plan U1, KTD4; docs/hardware/voice-mic.md section 3). The library
 * binds its natives by fully qualified class and method name
 * ("Java_com_example_conexantapi_ConexantDSP_getDSPRawDOA", confirmed with
 * nm -D), so this class must live at exactly this package and name and carry
 * the vendor's declarations unchanged
 * (tools/serviceexam_jadx/sources/com/example/conexantapi/ConexantDSP.java).
 * Hand-written, like emotix.com.drivers.SensorModule; nothing here is called
 * directly by our code except through com.miko3.shared.VoiceDirection.
 *
 * getDSPRawDOA() answers the direction of arrival in degrees on units whose
 * motor controller answered the vendor's <LOOPBACK_TEST_BYTE> at boot; units
 * answering <LOOPBACK_TEST_BYTE_GD> use NCDsp instead.
 */
public class ConexantDSP {
    public static int MicLeft = 192;
    public static int MicRight = 449;
    public int MODE_ZALX = 1;
    public int MODE_ZMP6 = 2;
    public int MODE_ZMP8 = 3;
    public int MODE_ZRWD = 4;
    public int MODE_ZSBW = 5;
    public int MODE_ZSLX = 6;
    public int MODE_ZSTB = 7;
    public int MODE_ZSW4 = 8;
    public int MODE_ZVS2 = 9;
    public int MODE_ZWV1 = 10;

    public native int flashDSPFirmware(String str, String str2);

    public native String getDSPFirmwareVersion();

    public native int getDSPInputGain();

    public native int getDSPMode();

    public native int getDSPOutputGain();

    public native float getDSPRawDOA();

    public native int getDSPStreams();

    public native int initDSPComm();

    public native int setDSPInputGain(int i);

    public native int setDSPMode(int i);

    public native int setDSPOutputGain(int i);

    public native int setDSPStreams(int i, int i2);

    static {
        // Our own bundled copy (scripts/build-custom-launcher.py stages it next to
        // libmiko_drivers.so): the /system/lib64 original sits behind the linker
        // namespace, as SensorModule's comment records.
        System.loadLibrary("conexant_dsp_lib");
    }
}
