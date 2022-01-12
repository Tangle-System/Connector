package com.tangle.connector;


import java.util.ArrayList;
import java.util.Date;

public class Functions {

    private Functions(){}

    public static byte getTimelineFlag(int timelineIndex, int timelinePaused) {
        byte timeline_index = (byte) (timelineIndex & 0b00001111);
        byte timeline_paused = (byte) ((timelinePaused << 4) & 0b00010000);
        return (byte) (timeline_paused | timeline_index);
    }

    public static byte[] integerToBytes(int value, int byteCount) {
        byte[] result = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            result[i] = (byte) (value & 0xFF);
            value >>= Byte.SIZE;
        }
        return result;
    }

    public static byte[] longToBytes(long value, int byteCount) {
        byte[] result = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            result[i] = (byte) (value & 0xFF);
            value >>= Byte.SIZE;
        }
        return result;
    }

    public static byte[] doubleToBytes(long value, int byteCount) {
        byte[] result = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            result[i] = (byte) (value & 0xFF);
            value >>= Byte.SIZE;
        }
        return result;
    }

    public static byte[] labelToBytes(String label) {
        byte[] result = new byte[5];
        if (label.length() > 5) {
            for (int i = 0; i < 5; i++) {
                result[i] = (byte) label.charAt(i);
            }
        } else {
            for (int i = 0; i < label.length(); i++) {
                result[i] = (byte) label.charAt(i);
            }
        }
        return result;
    }

    public static byte[] colorToBytes(String color_hex_code) {
        if (!color_hex_code.matches("#([0-9a-f][0-9a-f])([0-9a-f][0-9a-f])([0-9a-f][0-9a-f])")) {
            return new byte[]{0, 0, 0};
        }
        color_hex_code = color_hex_code.substring(1);
        byte r = (byte) (Integer.decode("#" + color_hex_code.substring(0, 2)) & 0xFF);
        byte g = (byte) (Integer.decode("#" + color_hex_code.substring(2, 4)) & 0xFF);
        byte b = (byte) (Integer.decode("#" + color_hex_code.substring(4, 6)) & 0xFF);
        return new byte[]{r, g, b};
    }

    public static byte[] percentageToBytes(float percentage) {
        int value = (int) mapValue(percentage, -100.0F, 100.0F, -2147483647F, 2147483647F);
        return integerToBytes(value,4);
    }

    public static ArrayList<Integer> logBytes(byte[] data) {
        ArrayList<Integer> bytes = new ArrayList<Integer>(data.length);
        if (data.length > 0) {
            for (byte datum : data) {
                bytes.add(datum & 0xFF);
            }
        }
        return bytes;
    }

    public static int getClockTimestamp() {
        return (int) (new Date().getTime() % Long.decode("0x7fffffff"));
    }

    public static float mapValue(float x,float in_min,float in_max,float out_min,float  out_max) {
        if (in_min == in_max) {
            return out_min / 2 + out_max / 2;
        }

        float minimum = Math.min(in_min, in_max);
        float maximum = Math.max(in_min, in_max);

        if (x < minimum) {
            x = minimum;
        } else if (x > maximum) {
            x = maximum;
        }

        float result = ((x - in_min) * (out_max - out_min)) / (in_max - in_min) + out_min;

        minimum = Math.min(out_min, out_max);
        maximum = Math.max(out_min, out_max);

        if (result < minimum){
            result = minimum;
        } else if (result > maximum){
            result = maximum;
        }

        return result;
    }
}
