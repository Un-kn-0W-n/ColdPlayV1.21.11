package cn.timer.coldplay.client.mixin;

import cn.timer.coldplay.client.alt.AltManager;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.io.File;
import java.net.Proxy;
import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
abstract class MinecraftSessionMixin {
    @Shadow @Final @Mutable private User user;
    @Shadow @Final @Mutable private CompletableFuture<ProfileResult> profileFuture;
    @Shadow @Final @Mutable private UserApiService userApiService;
    @Shadow @Final @Mutable private CompletableFuture<UserApiService.UserProperties> userPropertiesFuture;
    @Shadow @Final @Mutable private PlayerSocialManager playerSocialManager;
    @Shadow @Final @Mutable private ClientTelemetryManager telemetryManager;
    @Shadow @Final @Mutable private ProfileKeyPairManager profileKeyPairManager;
    @Shadow private ReportingContext reportingContext;
    @Shadow @Final public File gameDirectory;

    @Shadow public abstract Proxy getProxy();

    @Unique
    private void coldplay$replaceSession(User newUser, GameProfile profile) {
        Minecraft minecraft = (Minecraft) (Object) this;
        UserApiService newUserApi = new YggdrasilAuthenticationService(getProxy())
                .createUserApiService(newUser.getAccessToken());
        CompletableFuture<UserApiService.UserProperties> newProperties = CompletableFuture.supplyAsync(() -> {
            try {
                return newUserApi.fetchProperties();
            } catch (AuthenticationException exception) {
                AltManager.logFailure("ALT_USER_PROPERTIES", 0, exception);
                return UserApiService.OFFLINE_PROPERTIES;
            }
        }, Util.nonCriticalIoPool());
        PlayerSocialManager newSocialManager = new PlayerSocialManager(minecraft, newUserApi);
        ClientTelemetryManager newTelemetryManager = new ClientTelemetryManager(minecraft, newUserApi, newUser);
        ProfileKeyPairManager newKeyManager = ProfileKeyPairManager.create(
                newUserApi, newUser, gameDirectory.toPath());
        ReportingContext newReportingContext = ReportingContext.create(ReportEnvironment.local(), newUserApi);

        PlayerSocialManager oldSocialManager = playerSocialManager;
        ClientTelemetryManager oldTelemetryManager = telemetryManager;
        user = newUser;
        profileFuture = CompletableFuture.completedFuture(new ProfileResult(profile));
        userApiService = newUserApi;
        userPropertiesFuture = newProperties;
        playerSocialManager = newSocialManager;
        telemetryManager = newTelemetryManager;
        profileKeyPairManager = newKeyManager;
        reportingContext = newReportingContext;

        try {
            oldSocialManager.stopOnlineMode();
        } catch (RuntimeException exception) {
            AltManager.logFailure("ALT_OLD_SOCIAL_CLOSE", 0, exception);
        }
        try {
            oldTelemetryManager.close();
        } catch (RuntimeException exception) {
            AltManager.logFailure("ALT_OLD_TELEMETRY_CLOSE", 0, exception);
        }
    }
}
