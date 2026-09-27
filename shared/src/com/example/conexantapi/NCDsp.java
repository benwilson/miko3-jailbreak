package com.example.conexantapi;

/**
 * JNI stub for the vendor's "NC" DSP backend in libconexant_dsp_lib.so
 * (meeting plan U1, KTD4; docs/hardware/voice-mic.md section 3): the newer
 * chip, driven over a UART node (createUART(500, "/dev/ttyMT2") then
 * initNCUART) rather than the Conexant chip protocol. Same rule as
 * ConexantDSP: the package, class and method names are what the library's
 * exported symbols resolve against, so they are the vendor's, unchanged
 * (tools/serviceexam_jadx/sources/com/example/conexantapi/NCDsp.java).
 *
 * getCurrentDOAStatus(handle) is -1 while direction reporting is off
 * (toggleDOA turns it on) and the angle otherwise; see VoiceDirection.
 */
public class NCDsp {
    public native long createUART(int i, String str);

    public native int freeUART(long j);

    public native int getAECStatus(long j);

    public native int getCurrentChannel(long j);

    public native int getCurrentDOAStatus(long j);

    public native int getCurrentVOIPStatus(long j);

    public native int getDigitalGain(long j);

    public native String getFWVersion(long j);

    public native int getFactoryModeStatus(long j);

    public native int getGain(long j);

    public native int getLeftAECStatus(long j);

    public native int getLeftNSStatus(long j);

    public native int getNSStatus(long j);

    public native int initNCUART(long j);

    public native int setDigitalGain(long j, int i);

    public native int setGain(long j, int i);

    public native int toggleAEC(long j);

    public native int toggleChannel(long j);

    public native int toggleDOA(long j);

    public native int toggleFactoryMode(long j);

    public native int toggleLeftAEC(long j);

    public native int toggleLeftNS(long j);

    public native int toggleNS(long j);

    public native int toggleVOIP(long j);

    static {
        System.loadLibrary("conexant_dsp_lib");
    }
}
