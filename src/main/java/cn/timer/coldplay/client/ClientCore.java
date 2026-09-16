package cn.timer.coldplay.client;

import cn.timer.coldplay.client.alt.AltManager;
import cn.timer.coldplay.client.gui.click.ClickGuiScreen;
import cn.timer.coldplay.client.hud.HudState;
import cn.timer.coldplay.client.hud.StatusBars;
import cn.timer.coldplay.client.manager.ConfigManager;
import cn.timer.coldplay.client.manager.RotationManager;
import cn.timer.coldplay.client.module.ModuleManager;
import cn.timer.coldplay.client.module.impl.combat.AimAssist;
import cn.timer.coldplay.client.module.impl.combat.AntiBot;
import cn.timer.coldplay.client.module.impl.combat.BackTrack;
import cn.timer.coldplay.client.module.impl.combat.KillAura;
import cn.timer.coldplay.client.module.impl.combat.WTap;
import cn.timer.coldplay.client.module.impl.movement.Sprint;
import cn.timer.coldplay.client.module.impl.utilities.ChestStealer;
import cn.timer.coldplay.client.module.impl.utilities.InvManager;
import cn.timer.coldplay.client.module.impl.visuals.ClickGuiModule;
import cn.timer.coldplay.client.module.impl.visuals.EntityESP;
import cn.timer.coldplay.client.module.impl.visuals.FullBright;
import cn.timer.coldplay.client.module.impl.visuals.Hud;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientCore {
    public static final Logger LOGGER = LoggerFactory.getLogger("ColdPlay");
    private static final ClientCore INSTANCE = new ClientCore();

    private ModuleManager modules;
    private ConfigManager config;
    private ClickGuiScreen clickGui;
    private HudState hudState;
    private AltManager altManager;
    private EntityESP entityEsp;
    private InvManager invManager;
    private ChestStealer chestStealer;
    private boolean initialized;

    private ClientCore() {
    }

    public static ClientCore get() {
        return INSTANCE;
    }

    public void initialize() {
        if (initialized) {
            return;
        }

        modules = new ModuleManager();
        hudState = new HudState();
        AntiBot antiBot = AntiBot.get();
        modules.register(antiBot);
        antiBot.setEnabled(true);
        BackTrack backTrack = new BackTrack();
        KillAura killAura = new KillAura(backTrack);
        modules.register(killAura);
        WTap wTap = new WTap();
        modules.register(wTap);
        AttackEntityCallback.EVENT.register(wTap::onAttack);
        modules.register(backTrack);
        AttackEntityCallback.EVENT.register(backTrack::onAttack);
        modules.register(new AimAssist());
        modules.register(new Sprint());
        invManager = new InvManager();
        modules.register(invManager);
        chestStealer = new ChestStealer(invManager);
        modules.register(chestStealer);
        modules.register(new FullBright());
        Hud hud = new Hud(modules, hudState);
        modules.register(hud);
        entityEsp = new EntityESP();
        modules.register(entityEsp);
        modules.register(new ClickGuiModule());
        altManager = new AltManager();
        altManager.initialize();
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("coldplay", "hud"), hud::render);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("coldplay", "entity_esp"), entityEsp::render2D);
        registerStatusBars();
        WorldRenderEvents.END_EXTRACTION.register(entityEsp::extract2D);
        WorldRenderEvents.END_EXTRACTION.register(backTrack::extractRewind);

        ClientLifecycleEvents.CLIENT_STARTED.register(this::initializeUi);
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            antiBot.tick(client);
            backTrack.tick(client);
            wTap.tick(client);
            if (killAura.enabled()) {
                killAura.preTick(client);
            }
        });
        ClientEntityEvents.ENTITY_UNLOAD.register(backTrack::onEntityUnload);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            initializeUi(client);
            if (clickGui != null) {
                if (clickGui.consumeKeybindDrain()) {
                    modules.drainKeybinds(); // the key that closed the GUI or was just bound is not a toggle
                }
                if (!clickGui.isCapturingKey() && modules.pollKeybinds()) {
                    save();
                }
            }
            invManager.tick(client);
            chestStealer.tick(client);
        });
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof InventoryScreen) {
                registerInventoryInput(screen, invManager::markManualInteraction, invManager::reset);
            } else if (screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen) {
                registerInventoryInput(screen, chestStealer::markManualInteraction, chestStealer::reset);
            }
        });
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) -> {
            backTrack.clear();
            resetInventoryState();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            backTrack.clear();
            resetInventoryState();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            backTrack.clear();
            invManager.reset();
            chestStealer.reset();
            save();
            altManager.close();
        });
        initialized = true;
        LOGGER.info("ColdPlay initialized");
    }

    /** The pill bars stand in for the vanilla survival rows; each wrapper falls back to vanilla when off. */
    private void registerStatusBars() {
        HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR, vanilla -> (graphics, tickCounter) -> {
            if (!StatusBars.active() || !StatusBars.render(graphics)) {
                vanilla.render(graphics, tickCounter);
            }
        });
        for (Identifier hidden : new Identifier[]{VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR,
                VanillaHudElements.AIR_BAR}) {
            HudElementRegistry.replaceElement(hidden, vanilla -> (graphics, tickCounter) -> {
                if (!StatusBars.active()) {
                    vanilla.render(graphics, tickCounter);
                }
            });
        }
        HudElementRegistry.replaceElement(VanillaHudElements.EXPERIENCE_LEVEL, vanilla -> (graphics, tickCounter) -> {
            if (!StatusBars.consumeExperienceRendered()) {
                vanilla.render(graphics, tickCounter);
            }
        });
    }

    public boolean initialized() {
        return initialized;
    }

    public ModuleManager modules() {
        return modules;
    }

    public ConfigManager config() {
        return config;
    }

    public HudState hudState() {
        return hudState;
    }

    public AltManager altManager() {
        return altManager;
    }

    public EntityESP entityEsp() {
        return entityEsp;
    }

    public void render(DeltaTracker deltaTracker) {
        if (!initialized) {
            return;
        }
        modules.render(deltaTracker);
        RotationManager.getInstance().onRender(deltaTracker);
    }

    public void openClickGui() {
        Minecraft minecraft = Minecraft.getInstance();
        initializeUi(minecraft);
        if (clickGui == null) {
            LOGGER.warn("ClickGUI requested before the client font was ready");
            return;
        }
        Screen parent = minecraft.screen == clickGui ? null : minecraft.screen;
        clickGui.setParent(parent);
        minecraft.setScreen(clickGui);
    }

    public boolean isClickGuiOpen() {
        return clickGui != null && Minecraft.getInstance().screen == clickGui;
    }

    private void initializeUi(Minecraft minecraft) {
        if (clickGui != null || minecraft.font == null) {
            return;
        }
        clickGui = new ClickGuiScreen(modules, hudState);
        config = new ConfigManager(modules, clickGui, hudState);
        config.load();
    }

    private void resetInventoryState() {
        invManager.reset();
        chestStealer.reset();
        if (clickGui != null) {
            clickGui.resetTransientState();
        }
    }

    private static void registerInventoryInput(Screen screen, Runnable markManual, Runnable reset) {
        ScreenMouseEvents.beforeMouseClick(screen).register((ignored, event) -> markManual.run());
        ScreenMouseEvents.beforeMouseRelease(screen).register((ignored, event) -> markManual.run());
        ScreenMouseEvents.beforeMouseDrag(screen).register((ignored, event, dx, dy) -> markManual.run());
        ScreenMouseEvents.beforeMouseScroll(screen).register(
                (ignored, mouseX, mouseY, horizontal, vertical) -> markManual.run());
        ScreenKeyboardEvents.beforeKeyPress(screen).register((ignored, event) -> markManual.run());
        ScreenEvents.remove(screen).register(ignored -> reset.run());
    }

    public void save() {
        if (config != null) {
            config.save();
        }
    }
}
