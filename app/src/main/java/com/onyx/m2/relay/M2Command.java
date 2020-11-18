package com.onyx.m2.relay;

/**
 * A raw M2 command, conforming to the onyx-m2-firmware specification.
 */
public class M2Command {
    public static final byte CMDID_SET_ALL_MSG_FLAGS = 0x01;
    public static final byte CMDID_SET_MSG_FLAGS = 0x02;
    public static final byte CMDID_GET_MSG_LAST_VALUE = 0x03;
    public static final byte CMDID_GET_ALL_MSG_LAST_VALUE = 0x04;
    public static final byte CMDID_TAKE_SNAPSHOT = 0x05;
    public static final byte CMDID_START_LOGGING_TO_CUSTOM_FILE = 0x06;
    public static final byte CMDID_STOP_LOGGING_TO_CUSTOM_FILE = 0x07;

    public static final byte CAN_MSG_FLAG_TRANSMIT = 0x01;
    public static final byte CAN_MSG_FLAG_TRANSMIT_UNMODIFIED = 0x02;
    public static final byte CAN_MSG_FLAG_FULL_RESOLUTION = 0x04;
    public static final byte CAN_MSG_FLAG_IGNORE_COUNTERS = 0x08;

    public M2Command(byte[] data) {
        this.data = data;
        this.cmd = data[0];
    }

    public boolean isEnableAllMessages() {
        return data[0] == CMDID_SET_ALL_MSG_FLAGS && data[1] == CAN_MSG_FLAG_TRANSMIT;
    }

    public boolean isDisableAllMessages() {
        return data[0] == CMDID_SET_ALL_MSG_FLAGS && data[1] == 0;
    }

    public byte[] data;
    public byte cmd;
}
