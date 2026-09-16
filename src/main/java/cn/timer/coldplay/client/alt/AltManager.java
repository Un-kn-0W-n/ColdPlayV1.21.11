package cn.timer.coldplay.client.alt;

import cn.timer.coldplay.client.ClientCore;
import com.mojang.authlib.GameProfile;
import io.netty.handler.proxy.ProxyHandler;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.time.Instant;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class AltManager implements AutoCloseable {
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
            if (screen instanceof JoinMultiplayerScreen) {
                Screens.getButtons(screen).add(Button.builder(Component.literal("Alts"), input ->
                                client.setScreen(new AltManagerScreen(screen, this)))
                        .bounds(5, 5, 50, 20).build());
            }
        });
    }

    List<AltAccount> accounts() {
        return repository.accounts();
    }

    ProxySnapshot proxy() {
        return proxies.snapshot();
    }

    boolean busy() {
        return busy.get();
    }

    AccountStatus status(AltAccount account) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getUser() != null && account.uuid.equals(minecraft.getUser().getProfileId())) {
            return AccountStatus.ACTIVE;
        }
        if (account.uuid.equals(failedAccount)) {
            return AccountStatus.FAILED;
        }
        return account.hasCredential() ? AccountStatus.SAVED : AccountStatus.NEEDS_TOKEN;
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

    void login(TokenType type, String enteredCredential, String clientId, UUID selectedUuid,
               Consumer<ActionResult> callback) {
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

    void testProxy(String savedServer, Consumer<ActionResult> callback) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        ProxySnapshot snapshot = proxies.snapshot();
        executor.execute(() -> {
            ActionResult result = proxies.test(snapshot, savedServer);
            Minecraft.getInstance().execute(() -> {
                busy.set(false);
                callback.accept(result);
            });
        });
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
        if (selected == null || !selected.hasCredential()) {
            return new LoginRequest(type, "", clientId, selectedUuid);
        }
        return new LoginRequest(selected.tokenType, selected.credential, selected.clientId, selectedUuid);
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
            callback.accept(ActionResult.success("Logged in as " + result.profile.name() + "."));
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
