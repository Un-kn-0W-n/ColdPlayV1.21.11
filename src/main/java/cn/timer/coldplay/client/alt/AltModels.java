package cn.timer.coldplay.client.alt;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.User;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

enum TokenType {
    ACCESS_TOKEN("Access token"),
    REFRESH_TOKEN("Refresh token");

    private final String label;

    TokenType(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    TokenType next() {
        return this == ACCESS_TOKEN ? REFRESH_TOKEN : ACCESS_TOKEN;
    }
}

enum ProxyProtocol {
    HTTP,
    SOCKS5;

    ProxyProtocol next() {
        return this == HTTP ? SOCKS5 : HTTP;
    }
}

enum AccountStatus {
    ACTIVE("Active"),
    SAVED("Saved"),
    NEEDS_TOKEN("Needs Token"),
    FAILED("Failed");

    private final String label;

    AccountStatus(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

enum AltError {
    NONE("Ready"),
    TOKEN_REQUIRED("Enter a token or select an account with a saved credential."),
    CLIENT_ID_REQUIRED("A matching OAuth client ID is required for refresh tokens."),
    INVALID_CLIENT_ID("Use a 16-hex title ID or a dashed UUID application ID."),
    INVALID_TOKEN("The token is invalid or expired."),
    CLIENT_ID_MISMATCH("The refresh token does not match that OAuth client ID."),
    SERVICES_UNAVAILABLE("Minecraft authentication services are unavailable."),
    NETWORK_UNAVAILABLE("The authentication service could not be reached."),
    PROXY_CONNECTION_FAILED("The proxy could not be reached."),
    PROXY_AUTH_FAILED("The proxy rejected its username or password."),
    MALFORMED_PROXY("The proxy configuration is malformed."),
    PROFILE_MISSING("This Microsoft account does not own a Minecraft profile."),
    STORAGE_FAILED("The account was authenticated, but could not be saved."),
    UNKNOWN("Authentication failed for an unknown reason.");

    private final String message;

    AltError(String message) {
        this.message = message;
    }

    String message() {
        return message;
    }
}

record ProxyEndpoint(String host, int port) {
}

record ActionResult(boolean success, String message, AltError error) {
    static ActionResult success(String message) {
        return new ActionResult(true, message, AltError.NONE);
    }

    static ActionResult failure(AltError error) {
        return new ActionResult(false, error.message(), error);
    }
}

final class ProxySnapshot {
    static final ProxySnapshot EMPTY = new ProxySnapshot(false, false, ProxyProtocol.HTTP, "", "", "", "");

    final boolean authEnabled;
    final boolean serverEnabled;
    final ProxyProtocol protocol;
    final String address;
    final String port;
    final String username;
    final String password;

    ProxySnapshot(boolean authEnabled, boolean serverEnabled, ProxyProtocol protocol, String address,
                  String port, String username, String password) {
        this.authEnabled = authEnabled;
        this.serverEnabled = serverEnabled;
        this.protocol = protocol == null ? ProxyProtocol.HTTP : protocol;
        this.address = clean(address);
        this.port = clean(port);
        this.username = clean(username);
        this.password = password == null ? "" : password;
    }

    ProxyEndpoint endpoint() {
        if (username.isEmpty() != password.isEmpty()) {
            throw new IllegalArgumentException("proxy credentials must be complete");
        }

        String raw = address.trim();
        String separatePort = port.trim();
        if (raw.isEmpty() || raw.contains("://") || raw.contains("@") || raw.contains("/")
                || raw.contains("?") || raw.contains("#") || hasControlOrSpace(raw)) {
            throw new IllegalArgumentException("invalid proxy address");
        }

        String host;
        String embeddedPort = "";
        if (raw.startsWith("[")) {
            int close = raw.indexOf(']');
            if (close <= 1 || raw.indexOf('[', 1) >= 0 || raw.indexOf(']', close + 1) >= 0) {
                throw new IllegalArgumentException("invalid bracketed proxy address");
            }
            host = raw.substring(1, close);
            String suffix = raw.substring(close + 1);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":") || suffix.length() == 1) {
                    throw new IllegalArgumentException("invalid bracketed proxy port");
                }
                embeddedPort = suffix.substring(1);
            }
        } else {
            int firstColon = raw.indexOf(':');
            int lastColon = raw.lastIndexOf(':');
            if (firstColon >= 0 && firstColon == lastColon) {
                host = raw.substring(0, firstColon);
                embeddedPort = raw.substring(firstColon + 1);
                if (embeddedPort.isEmpty()) {
                    throw new IllegalArgumentException("missing embedded proxy port");
                }
            } else if (firstColon >= 0) {
                throw new IllegalArgumentException("IPv6 proxy addresses must be bracketed");
            } else {
                host = raw;
            }
        }

        if (host.isBlank() || hasControlOrSpace(host) || host.contains("[") || host.contains("]")) {
            throw new IllegalArgumentException("invalid proxy host");
        }
        if (!embeddedPort.isEmpty() && !separatePort.isEmpty()
                && parsePort(embeddedPort) != parsePort(separatePort)) {
            throw new IllegalArgumentException("conflicting proxy ports");
        }
        String chosenPort = embeddedPort.isEmpty() ? separatePort : embeddedPort;
        return new ProxyEndpoint(host, parsePort(chosenPort));
    }

    boolean enabled() {
        return authEnabled || serverEnabled;
    }

    private static int parsePort(String value) {
        if (value == null || !value.matches("[0-9]{1,5}")) {
            throw new IllegalArgumentException("invalid proxy port");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 65535) {
                throw new IllegalArgumentException("proxy port out of range");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid proxy port");
        }
    }

    private static boolean hasControlOrSpace(String value) {
        return value.chars().anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return "ProxySnapshot[authEnabled=" + authEnabled + ", serverEnabled=" + serverEnabled
                + ", protocol=" + protocol + ", address=<configured>, port=" + port
                + ", username=<redacted>, password=<redacted>]";
    }
}

final class AltAccount {
    final UUID uuid;
    final String username;
    final TokenType tokenType;
    final String clientId;
    final Instant lastSuccess;
    final String credential;

    AltAccount(UUID uuid, String username, TokenType tokenType, String clientId, Instant lastSuccess,
               String credential) {
        this.uuid = uuid;
        this.username = username;
        this.tokenType = tokenType;
        this.clientId = clientId == null ? "" : clientId;
        this.lastSuccess = lastSuccess;
        this.credential = credential;
    }

    boolean hasCredential() {
        return credential != null && !credential.isBlank();
    }

    @Override
    public String toString() {
        return "AltAccount[uuid=" + uuid + ", username=" + username + ", tokenType=" + tokenType
                + ", clientId=" + clientId + ", lastSuccess=" + lastSuccess + ", credential=<redacted>]";
    }
}

record LoginRequest(TokenType tokenType, String credential, String clientId, UUID selectedUuid) {
    LoginRequest {
        tokenType = tokenType == null ? TokenType.ACCESS_TOKEN : tokenType;
        credential = credential == null ? "" : credential.trim();
        clientId = clientId == null ? "" : clientId.trim();
        if (tokenType == TokenType.ACCESS_TOKEN) {
            clientId = "";
        }
    }

    @Override
    public String toString() {
        return "LoginRequest[tokenType=" + tokenType + ", credential=<redacted>, clientId=" + clientId
                + ", selectedUuid=" + selectedUuid + "]";
    }
}

final class AuthResult {
    final boolean success;
    final AltError error;
    final User user;
    final GameProfile profile;
    final String savedCredential;

    private AuthResult(boolean success, AltError error, User user, GameProfile profile, String savedCredential) {
        this.success = success;
        this.error = error;
        this.user = user;
        this.profile = profile;
        this.savedCredential = savedCredential;
    }

    static AuthResult success(User user, GameProfile profile, String savedCredential) {
        return new AuthResult(true, AltError.NONE, user, profile, savedCredential);
    }

    static AuthResult failure(AltError error) {
        return new AuthResult(false, error, null, null, null);
    }

    @Override
    public String toString() {
        return "AuthResult[success=" + success + ", error=" + error + ", user="
                + (user == null ? "null" : user.getName().toLowerCase(Locale.ROOT))
                + ", savedCredential=<redacted>]";
    }
}
