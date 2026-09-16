package cn.timer.coldplay.client;

import net.fabricmc.api.ClientModInitializer;

public final class ClientMain implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCore.get().initialize();
    }
}
