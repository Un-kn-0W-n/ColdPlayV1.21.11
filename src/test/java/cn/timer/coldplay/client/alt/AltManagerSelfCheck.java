package cn.timer.coldplay.client.alt;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.User;
import net.lenni0451.commons.httpclient.requests.impl.GetRequest;
import io.netty.handler.proxy.Socks5ProxyHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.io.IOException;

public final class AltManagerSelfCheck {
    private static final String TOKEN_ONE = "self-check-access-secret";
    private static final String TOKEN_TWO = "self-check-rotated-secret";
    private static final String SECOND_TOKEN = "self-check-second-account-secret";
    private static final String PASSWORD = "self-check-proxy-password";

    private AltManagerSelfCheck() {
    }

    public static void main(String[] args) {
        proxyParsing();
        malformedProxyInputs();
        proxyClientInitialization();
        fixedErrors();
        redaction();
        repositoryRoundTrip();
        dpapiRoundTrip();
    }

    private static void proxyParsing() {
        assert endpoint("proxy.example", "8080").equals(new ProxyEndpoint("proxy.example", 8080));
        assert endpoint("proxy.example:1080", "").equals(new ProxyEndpoint("proxy.example", 1080));
        assert endpoint("proxy.example:1080", "1080").equals(new ProxyEndpoint("proxy.example", 1080));
        assert endpoint("[2001:db8::1]:9050", "").equals(new ProxyEndpoint("2001:db8::1", 9050));
    }

    private static void malformedProxyInputs() {
        rejects("https://proxy.example", "8080", "", "");
        rejects("user:pass@proxy.example", "8080", "", "");
        rejects("proxy.example:8080", "1080", "", "");
        rejects("proxy.example", "0", "", "");
        rejects("proxy.example", "65536", "", "");
        rejects("proxy.example", "not-a-port", "", "");
        rejects("proxy.example", "8080", "only-user", "");
        rejects("proxy.example", "8080", "", "only-password");
        rejects("[2001:db8::1", "8080", "", "");
        rejects("2001:db8::1", "8080", "", "");
        rejects("proxy.example:", "8080", "", "");
        rejects("proxy.example", "+80", "", "");
        rejects("proxy.example", "", "", "");
    }

    private static void proxyClientInitialization() {
        ProxySnapshot proxy = new ProxySnapshot(true, false, ProxyProtocol.SOCKS5,
                "127.0.0.1", "1", "proxy-user", PASSWORD);
        try {
            new ProxyManager().authClient(proxy).execute(new GetRequest("https://api.minecraftservices.com/"));
            throw new AssertionError("Closed self-check proxy unexpectedly accepted a request");
        } catch (IOException expected) {
            // Reaching an I/O failure proves the scoped Reactor/Netty proxy executor linked successfully.
        }
        ProxyManager manager = new ProxyManager();
        manager.update(new ProxySnapshot(false, true, ProxyProtocol.HTTP,
                "127.0.0.1", "8080", "", ""));
        assert manager.snapshot().protocol == ProxyProtocol.SOCKS5;
        assert !manager.snapshot().authEnabled;
        assert manager.snapshot().serverEnabled;
        assert manager.serverHandler() instanceof Socks5ProxyHandler;
        manager.update(new ProxySnapshot(false, false, ProxyProtocol.SOCKS5,
                "127.0.0.1:1080", "", "proxy-user", PASSWORD));
        assert !manager.snapshot().serverEnabled;
        assert manager.serverHandler() == null;
        manager.update(new ProxySnapshot(false, true, ProxyProtocol.SOCKS5,
                "127.0.0.1:1080", "", "proxy-user", PASSWORD));
        assert manager.serverHandler() instanceof Socks5ProxyHandler;
    }

    private static void fixedErrors() {
        assert AuthService.mapStatus(401) == AltError.INVALID_TOKEN;
        assert AuthService.mapStatus(404) == AltError.PROFILE_MISSING;
        assert AuthService.mapStatus(407) == AltError.PROXY_AUTH_FAILED;
        assert AuthService.mapStatus(503) == AltError.SERVICES_UNAVAILABLE;
        assert AuthService.mapStatus(418) == AltError.UNKNOWN;
        assert AuthService.validClientId("00000000402b5328");
        assert AuthService.validClientId("123e4567-e89b-12d3-a456-426614174000");
        assert !AuthService.validClientId("123e4567e89b12d3a456426614174000");
        assert new LoginRequest(TokenType.ACCESS_TOKEN, TOKEN_ONE,
                "123e4567-e89b-12d3-a456-426614174000", null).clientId().isEmpty();
    }

    private static void redaction() {
        UUID uuid = UUID.randomUUID();
        ProxySnapshot proxy = new ProxySnapshot(true, true, ProxyProtocol.HTTP,
                "user:embedded@host", "8080", "proxy-user", PASSWORD);
        AltAccount account = new AltAccount(uuid, "Player", TokenType.ACCESS_TOKEN, "",
                Instant.EPOCH, TOKEN_ONE);
        LoginRequest request = new LoginRequest(TokenType.ACCESS_TOKEN, TOKEN_ONE, "", uuid);
        User user = new User("Player", uuid, TOKEN_ONE, Optional.empty(), Optional.empty());
        AuthResult result = AuthResult.success(user, new GameProfile(uuid, "Player"), TOKEN_TWO);
        String output = proxy + " " + account + " " + request + " " + result;
        assert !output.contains(TOKEN_ONE);
        assert !output.contains(TOKEN_TWO);
        assert !output.contains(PASSWORD);
        assert !output.contains("embedded");
        assert !output.contains("proxy-user");
    }

    private static void repositoryRoundTrip() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("coldplay-alt-self-check-");
            Path file = directory.resolve("coldplay-alts.json");
            SecureCredentialStorage storage = new SecureCredentialStorage();
            AltAccountRepository repository = new AltAccountRepository(file, storage);
            UUID firstUuid = UUID.randomUUID();
            UUID secondUuid = UUID.randomUUID();
            repository.setProxy(new ProxySnapshot(true, false, ProxyProtocol.SOCKS5,
                    "127.0.0.1", "1080", "proxy-user", PASSWORD));
            repository.upsert(new AltAccount(firstUuid, "SameName", TokenType.ACCESS_TOKEN, "",
                    Instant.parse("2026-01-01T00:00:00Z"), TOKEN_ONE));
            repository.upsert(new AltAccount(secondUuid, "SameName", TokenType.ACCESS_TOKEN, "",
                    Instant.parse("2026-02-01T00:00:00Z"), SECOND_TOKEN));
            repository.upsertAndSave(new AltAccount(firstUuid, "Rotated", TokenType.REFRESH_TOKEN,
                    "00000000402b5328", Instant.parse("2026-03-01T00:00:00Z"), TOKEN_TWO));

            List<AltAccount> inMemory = repository.accounts();
            assert inMemory.size() == 2;
            assert inMemory.get(0).uuid.equals(firstUuid);
            assert inMemory.get(0).credential.equals(TOKEN_TWO);
            assert inMemory.get(0).tokenType == TokenType.REFRESH_TOKEN;

            String json = Files.readString(file);
            assert !json.contains(TOKEN_ONE);
            assert !json.contains(TOKEN_TWO);
            assert !json.contains(SECOND_TOKEN);
            assert !json.contains(PASSWORD);
            try (var files = Files.list(directory)) {
                assert files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp"));
            }

            AltAccountRepository restored = new AltAccountRepository(file, storage);
            restored.load();
            assert restored.accounts().size() == 2;
            assert restored.accounts().get(0).uuid.equals(firstUuid);
            assert restored.accounts().get(0).username.equals("Rotated");
            assert restored.proxy().serverEnabled == false;
            assert restored.proxy().authEnabled;
            if (storage.supported()) {
                assert restored.accounts().get(0).credential.equals(TOKEN_TWO);
                assert restored.proxy().password.equals(PASSWORD);
            } else {
                assert !restored.accounts().get(0).hasCredential();
                assert restored.proxy().password.isEmpty();
            }
            Files.delete(file);
            Files.delete(directory);
            directory = null;
        } catch (Exception exception) {
            throw new AssertionError("Alt Manager repository self-check failed", exception);
        } finally {
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory.resolve("coldplay-alts.json"));
                    Files.deleteIfExists(directory);
                } catch (Exception ignored) {
                    // The assertion above remains the useful failure.
                }
            }
        }
    }

    private static void dpapiRoundTrip() {
        SecureCredentialStorage storage = new SecureCredentialStorage();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        assert storage.supported() == windows;
        if (windows) {
            String protectedSecret = storage.protect(TOKEN_ONE);
            assert protectedSecret != null;
            assert !protectedSecret.contains(TOKEN_ONE);
            assert storage.unprotect(protectedSecret).equals(TOKEN_ONE);
        } else {
            assert storage.protect(TOKEN_ONE) == null;
        }
    }

    private static ProxyEndpoint endpoint(String address, String port) {
        return new ProxySnapshot(true, false, ProxyProtocol.HTTP, address, port, "", "").endpoint();
    }

    private static void rejects(String address, String port, String username, String password) {
        try {
            new ProxySnapshot(true, false, ProxyProtocol.HTTP, address, port, username, password).endpoint();
            throw new AssertionError("Malformed proxy input was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
