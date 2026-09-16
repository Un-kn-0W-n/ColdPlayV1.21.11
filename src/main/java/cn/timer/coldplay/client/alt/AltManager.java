package cn.timer.coldplay.client.alt;

import cn.timer.coldplay.client.ClientCore;
import com.mojang.authlib.GameProfile;
import io.netty.handler.proxy.ProxyHandler;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.time.Instant;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class AltManager implements AutoCloseable {
    /** The launcher title ID; refresh tokens pasted into the Premium tab are exchanged with it. */
    static final String DEFAULT_CLIENT_ID = "00000000402B5328";
    private static final int TITLE_BUTTON_WIDTH = 200;
    private static final int TITLE_BUTTON_HEIGHT = 20;
    private static final int TITLE_ROW_STEP = 24;
    /** Less than a full step, so the Options row still clears the version line on a 240px-tall GUI. */
    private static final int TITLE_SHIFT = 16;

    private final AltAccountRepository repository = new AltAccountRepository();
    private final ProxyManager proxies = new ProxyManager();
    private final AuthService authentication = new AuthService(proxies);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ColdPlay Alt Manager");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile UUID failedAccount;

    public void initialize() {
        repository.load();
        proxies.update(repository.proxy());
        repository.setProxy(proxies.snapshot());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                addTitleButton(client, screen, width, height);
            }
        });
    }

    /** Inserts "Alt Manager" as the next full-width row and pushes the rows below it down one step. */
    private void addTitleButton(Minecraft client, Screen screen, int width, int height) {
        int mainRows = client.isDemo() ? 2 : 3;
        int rowY = height / 4 + 48 + TITLE_ROW_STEP * mainRows;
        List<AbstractWidget> buttons = Screens.getButtons(screen);
        for (AbstractWidget widget : buttons) {
            // the copyright link hugs the bottom edge and stays put
            if (widget.getY() >= rowY && widget.getY() < height - TITLE_BUTTON_HEIGHT) {
                widget.setY(widget.getY() + TITLE_SHIFT);
            }
        }
        buttons.add(Button.builder(Component.literal("Alt Manager"),
                        button -> client.setScreen(new AltManagerScreen(screen, this)))
                .bounds(width / 2 - TITLE_BUTTON_WIDTH / 2, rowY, TITLE_BUTTON_WIDTH, TITLE_BUTTON_HEIGHT)
                .build());
    }

    /** Saved accounts of one tab, newest login first. */
    List<AltAccount> accounts(boolean cracked) {
        return repository.accounts().stream()
                .filter(account -> account.tokenType.cracked() == cracked)
                .toList();
    }

    ProxySnapshot proxy() {
        return proxies.snapshot();
    }

    boolean busy() {
        return busy.get();
    }

    /** An empty token or a name-derived (version 3) UUID marks a session no server can verify. */
    static boolean offlineSession(User user) {
        return user == null || user.getAccessToken().isEmpty() || user.getProfileId().version() == 3;
    }

    AccountStatus status(AltAccount account) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getUser() != null && account.uuid.equals(minecraft.getUser().getProfileId())) {
            return AccountStatus.ACTIVE;
        }
        if (account.uuid.equals(failedAccount)) {
            return AccountStatus.FAILED;
        }
        return account.tokenType.cracked() || account.hasCredential() ? AccountStatus.SAVED : AccountStatus.NEEDS_TOKEN;
    }

    void updateProxy(ProxySnapshot snapshot) {
        proxies.update(snapshot);
        repository.setProxy(proxies.snapshot());
    }

    ActionResult saveProxy() {
        try {
            repository.save();
            return ActionResult.success("Proxy settings saved.");
        } catch (Exception exception) {
            logFailure("ALT_STORE_PROXY", 0, exception);
            return ActionResult.failure(AltError.STORAGE_FAILED);
        }
    }

    /** True once the account is gone from disk; a failed write keeps it and is logged. */
    boolean remove(UUID uuid) {
        try {
            return repository.removeAndSave(uuid);
        } catch (IOException exception) {
            logFailure("ALT_STORE_REMOVE", 0, exception);
            return false;
        }
    }

    void login(TokenType type, String enteredCredential, String clientId, UUID selectedUuid,
               Consumer<ActionResult> callback) {
        if (type.cracked()) {
            callback.accept(ActionResult.failure(AltError.TOKEN_REQUIRED));
            return;
        }
        LoginRequest request = resolveRequest(type, enteredCredential, clientId, selectedUuid);
        if (request.credential().isBlank()) {
            callback.accept(ActionResult.failure(AltError.TOKEN_REQUIRED));
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }

        ProxySnapshot snapshot = proxies.snapshot();
        executor.execute(() -> {
            AuthResult result = authentication.login(request, snapshot);
            AltError completionError = AltError.NONE;
            if (result.success) {
                AltAccount account = new AltAccount(result.profile.id(), result.profile.name(),
                        request.tokenType(), request.clientId(), Instant.now(), result.savedCredential);
                try {
                    repository.upsertAndSave(account);
                } catch (IOException exception) {
                    logFailure("ALT_STORE_LOGIN", 0, exception);
                    completionError = AltError.STORAGE_FAILED;
                }
            }
            AltError finalCompletionError = completionError;
            Minecraft.getInstance().execute(() -> finishLogin(request, result, finalCompletionError, callback));
        });
    }

    /** Cracked session, applied at once on the client thread; nothing is checked remotely. */
    void loginOffline(String name, Consumer<ActionResult> callback) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            UUID uuid = UUIDUtil.createOfflinePlayerUUID(name);
            GameProfile profile = new GameProfile(uuid, name);
            User user = new User(name, uuid, "", Optional.empty(), Optional.empty());
            if (!replaceSession(user, profile)) {
                callback.accept(ActionResult.failure(AltError.UNKNOWN));
                return;
            }
            failedAccount = null;
            try {
                repository.upsertAndSave(new AltAccount(uuid, name, TokenType.OFFLINE, "", Instant.now(), null));
            } catch (IOException exception) {
                logFailure("ALT_STORE_OFFLINE", 0, exception);
                callback.accept(ActionResult.failure(AltError.STORAGE_FAILED));
                return;
            }
            callback.accept(ActionResult.success("Offline session: " + name));
        } finally {
            busy.set(false);
        }
    }

    public ProxyHandler serverProxyHandler() {
        try {
            return proxies.serverHandler();
        } catch (RuntimeException exception) {
            logFailure("ALT_SERVER_PROXY_CONFIG", 0, exception);
            throw new IllegalStateException("ALT_SERVER_PROXY_CONFIG");
        }
    }

    @Override
    public void close() {
        saveProxy();
        executor.shutdownNow();
    }

    private LoginRequest resolveRequest(TokenType type, String enteredCredential, String clientId, UUID selectedUuid) {
        if (enteredCredential != null && !enteredCredential.isBlank()) {
            return new LoginRequest(type, enteredCredential, clientId, selectedUuid);
        }
        AltAccount selected = repository.accounts().stream()
                .filter(account -> account.uuid.equals(selectedUuid))
                .findFirst().orElse(null);
        if (selected == null || selected.tokenType.cracked() || !selected.hasCredential()) {
            return new LoginRequest(type, "", clientId, selectedUuid);
        }
        // accounts saved without a client ID were exchanged with the default one
        String savedClientId = selected.clientId.isEmpty() ? DEFAULT_CLIENT_ID : selected.clientId;
        return new LoginRequest(selected.tokenType, selected.credential, savedClientId, selectedUuid);
    }

    private void finishLogin(LoginRequest request, AuthResult result, AltError completionError,
                             Consumer<ActionResult> callback) {
        try {
            if (!result.success) {
                failedAccount = request.selectedUuid();
                callback.accept(ActionResult.failure(result.error));
                return;
            }
            if (completionError != AltError.NONE) {
                callback.accept(ActionResult.failure(completionError));
                return;
            }

            if (!replaceSession(result.user, result.profile)) {
                callback.accept(ActionResult.failure(AltError.UNKNOWN));
                return;
            }
            failedAccount = null;
            callback.accept(ActionResult.success("Logged in as " + result.profile.name()));
        } finally {
            busy.set(false);
        }
    }

    public static void logFailure(String code, int status, Throwable exception) {
        StackTraceElement[] frames = exception.getStackTrace();
        String frame = frames.length == 0 ? "none" : frames[0].toString();
        ClientCore.LOGGER.warn("{} status={} class={} at={}", code, status,
                exception.getClass().getName(), frame);
    }

    private static boolean replaceSession(User user, GameProfile profile) {
        try {
            Method method = Minecraft.getInstance().getClass()
                    .getDeclaredMethod("coldplay$replaceSession", User.class, GameProfile.class);
            if (!method.trySetAccessible()) {
                logFailure("ALT_SESSION_SWAP_ACCESS", 0, new IllegalAccessException());
                return false;
            }
            method.invoke(Minecraft.getInstance(), user, profile);
            return true;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            logFailure("ALT_SESSION_SWAP_TARGET", 0, cause);
            return false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logFailure("ALT_SESSION_SWAP_BRIDGE", 0, exception);
            return false;
        }
    }
}
