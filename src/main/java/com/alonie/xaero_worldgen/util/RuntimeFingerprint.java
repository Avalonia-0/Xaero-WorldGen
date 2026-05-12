package com.alonie.xaero_worldgen.util;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class RuntimeFingerprint {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault());

    private RuntimeFingerprint() {
    }

    public static String describe(Class<?> anchorClass, String version) {
        String runtimeVersion = valueOrDefault(version, "unknown");
        String codeSource = "unknown";
        String modified = "unknown";
        String sha256 = "unknown";

        try {
            URL locationUrl = anchorClass.getProtectionDomain().getCodeSource().getLocation();
            URI locationUri = locationUrl.toURI();
            Path path = Path.of(locationUri);
            codeSource = path.getFileName() != null ? path.getFileName().toString() : path.toString();

            if (Files.exists(path)) {
                modified = TIME_FORMAT.format(Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()));
                sha256 = shortSha256(path);
            }
        } catch (Exception ignored) {
        }

        return "version="
            + runtimeVersion
            + ",codeSource="
            + codeSource
            + ",modified="
            + modified
            + ",sha256="
            + sha256;
    }

    private static String shortSha256(Path path) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest()).substring(0, 12);
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
