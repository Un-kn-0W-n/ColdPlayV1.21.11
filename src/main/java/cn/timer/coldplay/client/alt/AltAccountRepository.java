package cn.timer.coldplay.client.alt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class AltAccountRepository {
    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final SecureCredentialStorage credentials;
    private final List<AltAccount> accounts = new ArrayList<>();
    private ProxySnapshot proxy = ProxySnapshot.EMPTY;

    AltAccountRepository() {
        this(FabricLoader.getInstance().getConfigDir().resolve("coldplay-alts.json"), new SecureCredentialStorage());
    }

    AltAccountRepository(Path path, SecureCredentialStorage credentials) {
        this.path = path;
        this.credentials = credentials;
    }

    synchronized void load() {
        accounts.clear();
        proxy = ProxySnapshot.EMPTY;
        if (!Files.isRegularFile(path)) {
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (integer(root, "version", -1) != VERSION) {
                throw new IOException("unsupported storage version");
            }
            proxy = readProxy(root.getAsJsonObject("proxy"));
            JsonArray savedAccounts = root.getAsJsonArray("accounts");
            if (savedAccounts != null) {
                for (JsonElement element : savedAccounts) {
                    readAccount(element.getAsJsonObject());
                }
            }
            sort();
        } catch (Exception exception) {
            accounts.clear();
            proxy = ProxySnapshot.EMPTY;
            log("ALT_STORE_LOAD", exception);
        }
    }

    synchronized List<AltAccount> accounts() {
        return List.copyOf(accounts);
    }

    synchronized ProxySnapshot proxy() {
        return proxy;
    }

    synchronized void setProxy(ProxySnapshot proxy) {
        this.proxy = proxy == null ? ProxySnapshot.EMPTY : proxy;
    }

    synchronized void upsert(AltAccount account) {
        accounts.removeIf(existing -> existing.uuid.equals(account.uuid));
        accounts.add(account);
        sort();
    }

    synchronized void upsertAndSave(AltAccount account) throws IOException {
        List<AltAccount> previous = List.copyOf(accounts);
        upsert(account);
        try {
            save();
        } catch (RuntimeException | IOException exception) {
            accounts.clear();
            accounts.addAll(previous);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("credential protection failed", exception);
        }
    }

    synchronized void save() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("proxy", writeProxy(proxy));
        JsonArray savedAccounts = new JsonArray();
        for (AltAccount account : accounts) {
            savedAccounts.add(writeAccount(account));
        }
        root.add("accounts", savedAccounts);

        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("storage path has no parent");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Path path() {
        return path;
    }

    private ProxySnapshot readProxy(JsonObject object) {
        if (object == null) {
            return ProxySnapshot.EMPTY;
        }
        String protectedPassword = string(object, "password", "");
        String password = unprotect(protectedPassword);
        return new ProxySnapshot(bool(object, "auth", false), bool(object, "server", false),
                enumValue(ProxyProtocol.class, string(object, "protocol", "HTTP"), ProxyProtocol.HTTP),
                string(object, "address", ""), string(object, "port", ""),
                string(object, "username", ""), password == null ? "" : password);
    }

    private JsonObject writeProxy(ProxySnapshot value) {
        JsonObject object = new JsonObject();
        object.addProperty("auth", value.authEnabled);
        object.addProperty("server", value.serverEnabled);
        object.addProperty("protocol", value.protocol.name());
        object.addProperty("address", value.address);
        object.addProperty("port", value.port);
        object.addProperty("username", value.username);
        String password = credentials.protect(value.password);
        if (password != null) {
            object.addProperty("password", password);
        }
        return object;
    }

    private void readAccount(JsonObject object) {
        try {
            UUID uuid = UUID.fromString(string(object, "uuid", ""));
            String username = string(object, "username", "");
            TokenType tokenType = enumValue(TokenType.class, string(object, "tokenType", ""), null);
            Instant lastSuccess = Instant.parse(string(object, "lastSuccess", ""));
            if (username.isBlank() || tokenType == null) {
                return;
            }
            accounts.removeIf(existing -> existing.uuid.equals(uuid));
            accounts.add(new AltAccount(uuid, username, tokenType, string(object, "clientId", ""),
                    lastSuccess, unprotect(string(object, "token", ""))));
        } catch (RuntimeException exception) {
            log("ALT_STORE_ACCOUNT", exception);
        }
    }

    private JsonObject writeAccount(AltAccount account) {
        JsonObject object = new JsonObject();
        object.addProperty("uuid", account.uuid.toString());
        object.addProperty("username", account.username);
        object.addProperty("tokenType", account.tokenType.name());
        object.addProperty("clientId", account.clientId);
        object.addProperty("lastSuccess", account.lastSuccess.toString());
        String token = credentials.protect(account.credential);
        if (token != null) {
            object.addProperty("token", token);
        }
        return object;
    }

    private String unprotect(String value) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            return credentials.unprotect(value);
        } catch (RuntimeException exception) {
            log("ALT_DPAPI_UNPROTECT", exception);
            return null;
        }
    }

    private void sort() {
        accounts.sort(Comparator.comparing((AltAccount account) -> account.lastSuccess).reversed());
    }

    private static String string(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }

    private static int integer(JsonObject object, String name, int fallback) {
        try {
            JsonElement value = object.get(name);
            return value == null ? fallback : value.getAsInt();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String name, boolean fallback) {
        try {
            JsonElement value = object.get(name);
            return value == null ? fallback : value.getAsBoolean();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static void log(String code, Exception exception) {
        AltManager.logFailure(code, 0, exception);
    }
}
