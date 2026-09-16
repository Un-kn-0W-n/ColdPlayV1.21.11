package cn.timer.coldplay.client.alt;

import com.sun.jna.platform.win32.Crypt32Util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

final class SecureCredentialStorage {
    private final boolean supported;

    SecureCredentialStorage() {
        this(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    SecureCredentialStorage(boolean supported) {
        this.supported = supported;
    }

    boolean supported() {
        return supported;
    }

    String protect(String secret) {
        if (!supported || secret == null || secret.isEmpty()) {
            return null;
        }
        byte[] protectedBytes = Crypt32Util.cryptProtectData(secret.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(protectedBytes);
    }

    String unprotect(String protectedSecret) {
        if (!supported || protectedSecret == null || protectedSecret.isEmpty()) {
            return null;
        }
        byte[] clearBytes = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(protectedSecret));
        return new String(clearBytes, StandardCharsets.UTF_8);
    }
}
