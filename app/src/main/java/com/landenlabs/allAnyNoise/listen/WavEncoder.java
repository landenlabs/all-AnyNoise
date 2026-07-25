// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.listen;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Wraps raw 16-bit PCM samples in a minimal WAV (RIFF) header. */
final class WavEncoder {

    private WavEncoder() {
    }

    static byte[] encode(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataLength = pcm.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLength);
        writeString(out, "RIFF");
        writeInt(out, 36 + dataLength);
        writeString(out, "WAVE");
        writeString(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, (short) 1); // PCM
        writeShort(out, (short) channels);
        writeInt(out, sampleRate);
        writeInt(out, byteRate);
        writeShort(out, (short) blockAlign);
        writeShort(out, (short) bitsPerSample);
        writeString(out, "data");
        writeInt(out, dataLength);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        out.write(s.getBytes(StandardCharsets.US_ASCII), 0, s.length());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, short value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
