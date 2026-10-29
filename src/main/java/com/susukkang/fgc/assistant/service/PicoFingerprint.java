package com.susukkang.fgc.assistant.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class PicoFingerprint {
    private PicoFingerprint() {}
    static String of(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String of(String value) { return of(value.getBytes(StandardCharsets.UTF_8)); }
}
