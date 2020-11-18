package com.onyx.m2.relay;

/**
 * A raw M2 message, conforming to the onyx-m2-firmware specification.
 *
 * The "interesting" part of copying to the integer array is due to Java not
 * having unsigned support. If this isn't done, the Javascript side with receive
 * negative number for the data bytes.
 */
public class M2Message {
    public M2Message(byte[] msg) {
        this.ts = (msg[0] & 0xFF) | ((msg[1] & 0xFF) << 8) | ((msg[2] & 0xFF) << 16) | ((msg[3]  & 0xFF) << 24);
        this.bus = msg[4] & 0xFF;
        this.id = (msg[5] & 0xFF) | ((msg[6] & 0xFF) << 8);
        int len = msg[7] & 0xFF;
        this.data = new int[len];
        for (int i = 0; i < len; i++) {
            this.data[i] = msg[8 + i] & 0xFF;
        }
    }
    public final int ts;
    public final int bus;
    public final int id;
    public final int[] data;
}
