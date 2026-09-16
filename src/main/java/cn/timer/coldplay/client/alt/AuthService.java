package cn.timer.coldplay.client.alt;

import com.mojang.authlib.GameProfile;
import net.lenni0451.commons.httpclient.HttpClient;
import net.lenni0451.commons.httpclient.exceptions.HttpRequestException;
import io.netty.handler.proxy.ProxyConnectException;
import net.minecraft.client.User;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.exception.MinecraftProfileNotFoundException;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.java.request.MinecraftProfileRequest;
import net.raphimc.minecraftauth.msa.data.MsaConstants;
import net.raphimc.minecraftauth.msa.exception.MsaRequestException;
import net.raphimc.minecraftauth.msa.model.MsaApplicationConfig;
import net.raphimc.minecraftauth.util.http.exception.ApiHttpRequestException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class AuthService {
    private static final Pattern TITLE_ID = Pattern.compile("[0-9a-fA-F]{16}");
    private static final Pattern APPLICATION_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ProxyManager proxies;

    AuthService(ProxyManager proxies) {
        this.proxies = proxies;
    }

    AuthResult login(LoginRequest request, ProxySnapshot proxy) {
        if (request.credential().isBlank()) {
            return AuthResult.failure(AltError.TOKEN_REQUIRED);
        }
        if (request.credential().length() > 16_384 || request.credential().chars().anyMatch(
                character -> Character.isWhitespace(character) || Character.isISOControl(character))) {
            return AuthResult.failure(AltError.INVALID_TOKEN);
        }
        if (request.tokenType() == TokenType.REFRESH_TOKEN) {
            if (request.clientId().isBlank()) {
                return AuthResult.failure(AltError.CLIENT_ID_REQUIRED);
            }
            if (!validClientId(request.clientId())) {
                return AuthResult.failure(AltError.INVALID_CLIENT_ID);
            }
        }
        if (proxy.authEnabled) {
            try {
                proxy.endpoint();
            } catch (IllegalArgumentException exception) {
                AltManager.logFailure("ALT_AUTH_PROXY_CONFIG", 0, exception);
                return AuthResult.failure(AltError.MALFORMED_PROXY);
            }
        }

        try {
            return request.tokenType() == TokenType.ACCESS_TOKEN
                    ? accessToken(request.credential(), proxy)
                    : refreshToken(request.credential(), request.clientId(), proxy);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            AltError error = mapFailure(exception, proxy.authEnabled);
            AltManager.logFailure("ALT_AUTH_" + error.name(), httpStatus(exception), exception);
            return AuthResult.failure(error);
        }
    }

    static boolean validClientId(String value) {
        return value != null && (TITLE_ID.matcher(value).matches() || APPLICATION_ID.matcher(value).matches());
    }

    static AltError mapStatus(int status) {
        if (status == 401 || status == 403) {
            return AltError.INVALID_TOKEN;
        }
        if (status == 404) {
            return AltError.PROFILE_MISSING;
        }
        if (status == 407) {
            return AltError.PROXY_AUTH_FAILED;
        }
        if (status == 408 || status == 429 || status >= 500) {
            return AltError.SERVICES_UNAVAILABLE;
        }
        return AltError.UNKNOWN;
    }

    private AuthResult accessToken(String token, ProxySnapshot proxy) throws IOException {
        HttpClient client = proxies.authClient(proxy);
        MinecraftToken minecraftToken = new MinecraftToken(Long.MAX_VALUE, "Bearer", token);
        MinecraftProfile minecraftProfile = client.executeAndHandle(new MinecraftProfileRequest(minecraftToken));
        return success(minecraftProfile, token, token, "");
    }

    private AuthResult refreshToken(String token, String clientId, ProxySnapshot proxy) throws IOException {
        String scope = TITLE_ID.matcher(clientId).matches()
                ? MsaConstants.SCOPE_TITLE_AUTH
                : MsaConstants.SCOPE_OFFLINE_ACCESS;
        JavaAuthManager manager = JavaAuthManager.create(proxies.authClient(proxy))
                .msaApplicationConfig(new MsaApplicationConfig(clientId, scope))
                .login(token);
        MinecraftToken minecraftToken = manager.getMinecraftToken().getUpToDate();
        MinecraftProfile minecraftProfile = manager.getMinecraftProfile().getUpToDate();
        String rotatedRefreshToken = manager.getMsaToken().getCached().getRefreshToken();
        if (rotatedRefreshToken == null || rotatedRefreshToken.isBlank()) {
            rotatedRefreshToken = token;
        }
        return success(minecraftProfile, minecraftToken.getToken(), rotatedRefreshToken, clientId);
    }

    private static AuthResult success(MinecraftProfile minecraftProfile, String accessToken,
                                      String savedCredential, String clientId) {
        UUID uuid = minecraftProfile.getId();
        String username = minecraftProfile.getName();
        GameProfile gameProfile = new GameProfile(uuid, username);
        User user = new User(username, uuid, accessToken, Optional.empty(),
                clientId.isBlank() ? Optional.empty() : Optional.of(clientId));
        return AuthResult.success(user, gameProfile, savedCredential);
    }

    private static AltError mapFailure(Throwable exception, boolean authProxyEnabled) {
        if (hasCause(exception, MinecraftProfileNotFoundException.class)) {
            return AltError.PROFILE_MISSING;
        }
        ApiHttpRequestException apiException = cause(exception, ApiHttpRequestException.class);
        if (apiException != null) {
            String error = apiException.getError();
            if (apiException instanceof MsaRequestException && error != null) {
                if (error.equalsIgnoreCase("invalid_client") || error.equalsIgnoreCase("unauthorized_client")) {
                    return AltError.CLIENT_ID_MISMATCH;
                }
                if (error.equalsIgnoreCase("invalid_grant") || error.equalsIgnoreCase("interaction_required")) {
                    return AltError.INVALID_TOKEN;
                }
            }
            return mapStatus(httpStatus(apiException));
        }
        HttpRequestException requestException = cause(exception, HttpRequestException.class);
        if (requestException != null) {
            return mapStatus(httpStatus(requestException));
        }
        if (authProxyEnabled && hasCause(exception, ProxyConnectException.class)) {
            return proxyAuthFailure(exception) ? AltError.PROXY_AUTH_FAILED : AltError.PROXY_CONNECTION_FAILED;
        }
        if (hasCause(exception, UnknownHostException.class)
                || hasCause(exception, ConnectException.class)
                || hasCause(exception, SocketException.class)
                || hasCause(exception, IOException.class)) {
            return authProxyEnabled ? AltError.PROXY_CONNECTION_FAILED : AltError.NETWORK_UNAVAILABLE;
        }
        return AltError.UNKNOWN;
    }

    private static boolean proxyAuthFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ProxyConnectException && current.getMessage() != null) {
                String message = current.getMessage().toLowerCase(Locale.ROOT);
                if (message.contains("407") || message.contains("auth")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int httpStatus(Throwable exception) {
        HttpRequestException requestException = cause(exception, HttpRequestException.class);
        return requestException == null || requestException.getResponse() == null
                ? 0 : requestException.getResponse().getStatusCode();
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        return cause(throwable, type) != null;
    }

    private static <T extends Throwable> T cause(Throwable throwable, Class<T> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }
}
