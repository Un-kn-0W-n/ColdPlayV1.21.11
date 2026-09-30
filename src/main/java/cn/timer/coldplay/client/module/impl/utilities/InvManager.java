package cn.timer.coldplay.client.module.impl.utilities;

import cn.timer.coldplay.client.manager.InventoryTransactions;
import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.InventoryPickerSetting;
import cn.timer.coldplay.client.setting.RangeSetting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class InvManager extends Module {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final int BACKPACK_START = Inventory.SELECTION_SIZE;
    private static final int BACKPACK_END = Inventory.INVENTORY_SIZE;
    /**
     * Vanilla's own position-send threshold, and horizontal only. A player who has just stopped
     * walking still drifts for several ticks before the client zeroes the residual velocity, and
     * one standing on a slab, in water or on a mount never stops moving vertically at all --
     * so a tighter bound, or one that counts Y, reads "standing still" as "moving" forever.
     */
    private static final double MOVEMENT_EPSILON_SQUARED = 9.0E-4;
    private static final int MILLIS_PER_TICK = 50;
    /**
     * Instant Swap is a floor, not zero. This module is driven from END_CLIENT_TICK, so a gap of
     * one tick is 20 swaps a second -- and since the client runs up to ten catch-up ticks in one
     * frame after a lag spike, ten of them could leave back to back. Two ticks halves both.
     */
    private static final int INSTANT_SWAP_GAP_TICKS = 2;
    /**
     * Instant Clean runs on a wall clock, not on ticks and not on frames: one slot every 20 ms,
     * 50 a second. The tick loop cannot express it (it only looks up every 50 ms) and the frame
     * loop must not define it (the rate would follow the player's fps), so the render loop is
     * only the sampler -- this interval is the rate.
     */
    static final long INSTANT_CLEAN_INTERVAL_NANOS = 20L * 1_000_000L;
    /**
     * Drops allowed in one frame. Every click lands whatever the container state id says, and
     * InventoryTransactions keeps the emptied slots emptied while the resyncs arrive, so the
     * whole backpack can go at once the way it did on 1.8.9 -- the cap is just its size.
     */
    static final int INSTANT_CLEAN_MAX_PER_FRAME = BACKPACK_END - BACKPACK_START;
    private static final int REACTION_MIN_TICKS = 6;
    private static final int REACTION_MAX_TICKS = 12;

    private final RangeSetting delay = addSetting(new RangeSetting("Delay", 100, 200, 50, 1000, 10));
    private final BooleanSetting autoArmor = addOwnerSetting(new BooleanSetting("AutoArmor", false));
    private final BooleanSetting instantArmor = addChildSetting(autoArmor,
            new BooleanSetting("Instant Swap", false));
    private final BooleanSetting autoHotBar = addOwnerSetting(new BooleanSetting("AutoHotBar", false));
    private final BooleanSetting instantHotBar = addChildSetting(autoHotBar,
            new BooleanSetting("Instant Swap", false));
    private final InventoryPickerSetting.Layout layout = addChildSetting(autoHotBar,
            new InventoryPickerSetting.Layout("Layout"));
    private final BooleanSetting cleaner = addOwnerSetting(new BooleanSetting("Cleaner", false));
    private final BooleanSetting instantClean = addChildSetting(cleaner,
            new BooleanSetting("Instant Clean", false));
    private final InventoryPickerSetting.Cleaner cleanerItems = addChildSetting(cleaner,
            new InventoryPickerSetting.Cleaner("Items"));
    private final BooleanSetting inventory = addOwnerSetting(new BooleanSetting("Inventory", false));
    private final BooleanSetting autoClose = addChildSetting(inventory,
            new BooleanSetting("Auto Close", false));

    private final InventoryTransactions transactions = new InventoryTransactions();

    private LocalPlayer catalogPlayer;
    private ClientLevel catalogLevel;
    private boolean catalogNeedsResolve;
    private LocalPlayer sessionPlayer;
    private ClientLevel sessionLevel;
    private InventoryScreen sessionScreen;
    /** Ticks still owed before the next action may run; 0 means due. */
    private int countdown;
    private boolean worked;
    private double previousX;
    private double previousZ;
    private int previousSelected;
    private int manualCooldown;
    /** Last tick's movement/cooldown verdict, sampled so the render loop can read it. */
    private boolean tickBlocked;
    /** Wall-clock deadline for the next Instant Clean drop. Seeded in beginSession. */
    private long nextCleanAt;

    public InvManager() {
        super("InvManager", "Manages armor, hotbar, and backpack items while inventory is open",
                Category.UTILITIES, GLFW.GLFW_KEY_UNKNOWN);
    }

    public InventoryPickerSetting.Layout layout() {
        return layout;
    }

    public InventoryPickerSetting.Cleaner cleanerItems() {
        return cleanerItems;
    }

    public InventoryPickerSetting.Catalog catalog() {
        return layout.catalog();
    }

    boolean effectiveAutoClose() {
        return enabled() && inventory.get() && autoClose.get();
    }

    public void tick(Minecraft minecraft) {
        syncCatalogIdentity(minecraft);
        LocalPlayer player = minecraft.player;
        if (!enabled()) {
            clearSession();
            return;
        }
        boolean catalogReady = player != null && minecraft.level != null && player.isAlive()
                && ensureCatalog(minecraft);

        if (!validEnvironment(minecraft, player)) {
            boolean worldUnavailable = player == null || minecraft.level == null || !player.isAlive();
            if (sessionScreen != null || worldUnavailable && catalog().ready()) {
                clearPickerReferences();
                catalogNeedsResolve = !worldUnavailable;
            }
            clearSession();
            return;
        }
        InventoryScreen screen = (InventoryScreen) minecraft.screen;
        if (player != sessionPlayer || minecraft.level != sessionLevel || screen != sessionScreen) {
            beginSession(player, minecraft.level, screen);
            return;
        }

        // Both run on every tick regardless of the outcome: trackPlayer owns the movement delta
        // and the cooldown drain, due() counts the action cooldown down.
        transactions.begin(player.inventoryMenu);
        trackPlayer(player);
        boolean due = due();
        if (!safeToAct(minecraft, player) || !due) {
            return;
        }

        InventoryAction action = firstNonNull(
                () -> autoArmor.get() ? armorAction(player) : null,
                () -> autoHotBar.get() && catalogReady ? hotbarAction(player) : null,
                () -> cleaner.get() && catalogReady ? cleanerAction(player) : null);
        if (action != null) {
            if (action.kind() == WorkKind.CLEANER && instantClean.get()) {
                // Owned by onRender. Returning rather than falling through is what keeps Auto
                // Close from shutting the screen while drops are still outstanding.
                return;
            }
            perform(minecraft, player, action);
            afterAction(action.kind());
            return;
        }
        if (!catalogReady && (autoHotBar.get() || cleaner.get())) {
            return;
        }
        if (inventory.get() && autoClose.get() && worked) {
            screen.onClose();
            clearSession();
        }
    }

    /**
     * Instant Clean runs here rather than on the tick loop, because a 20 ms spacing cannot be
     * expressed by something that only wakes every 50 ms. The render loop is the sampler, not the
     * clock: the rate comes from {@link #INSTANT_CLEAN_INTERVAL_NANOS}, so it is the same at
     * 30 fps and at 240.
     */
    @Override
    protected void onRender(DeltaTracker deltaTracker) {
        if (!instantClean.get() || !cleaner.get() || sessionScreen == null || countdown > 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player != sessionPlayer || minecraft.screen != sessionScreen
                || !validEnvironment(minecraft, player) || !catalog().ready()
                || !safeToAct(minecraft, player)) {
            return;
        }
        long now = System.nanoTime();
        nextCleanAt = cleanBaseline(now, nextCleanAt);
        for (int drop = 0; drop < INSTANT_CLEAN_MAX_PER_FRAME && cleanDue(now, nextCleanAt); drop++) {
            InventoryAction action = cleanerAction(player);
            if (action == null) {
                nextCleanAt = now + INSTANT_CLEAN_INTERVAL_NANOS;
                return;
            }
            perform(minecraft, player, action);
            worked = true;
            // Advance by the interval, not from now, so a late frame is repaid on the next one
            // and the long-run spacing stays 20 ms.
            nextCleanAt += INSTANT_CLEAN_INTERVAL_NANOS;
        }
    }

    /**
     * The deadline this frame starts from. A stored deadline more than a frame's worth of
     * catch-up in the past means the reaction delay just ended, or the client was away, so it
     * restarts from now instead of owing a backlog. Every comparison is a subtraction, which
     * stays correct when nanoTime wraps.
     */
    static long cleanBaseline(long now, long deadline) {
        return now - deadline > INSTANT_CLEAN_INTERVAL_NANOS * INSTANT_CLEAN_MAX_PER_FRAME ? now : deadline;
    }

    static boolean cleanDue(long now, long deadline) {
        return now - deadline >= 0L;
    }

    private void perform(Minecraft minecraft, LocalPlayer player, InventoryAction action) {
        // Recorded first: the prediction is built from the state the click is about to change.
        int inventorySlot = inventorySlot(action.menuSlot());
        switch (action.clickType()) {
            case THROW -> transactions.threw(inventorySlot);
            case SWAP -> transactions.swapped(player.getInventory(), inventorySlot, action.button());
            default -> transactions.quickMoved(inventorySlot);
        }
        minecraft.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, action.menuSlot(),
                action.button(), action.clickType(), player);
    }

    /** Inverse of {@link #menuSlot}: armour 5-8 run head first, the hotbar sits last at 36-44. */
    static int inventorySlot(int menuSlot) {
        if (menuSlot >= BACKPACK_END) {
            return menuSlot - BACKPACK_END;
        }
        return menuSlot >= BACKPACK_START ? menuSlot : 44 - menuSlot;
    }

    public void markManualInteraction() {
        if (sessionScreen != null) {
            manualCooldown = 2;
        }
    }

    public void reset() {
        clearSession();
        clearPickerReferences();
        catalogPlayer = null;
        catalogLevel = null;
        catalogNeedsResolve = false;
    }

    @Override
    protected void onEnable() {
        clearSession();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void syncCatalogIdentity(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player != catalogPlayer || level != catalogLevel) {
            clearSession();
            clearPickerReferences();
            catalogPlayer = player;
            catalogLevel = level;
            catalogNeedsResolve = player != null && level != null;
        }
    }

    private boolean ensureCatalog(Minecraft minecraft) {
        boolean needsResolve = catalogNeedsResolve || !catalog().ready();
        if (!catalog().ensure(minecraft)) {
            return false;
        }
        if (needsResolve) {
            ClientLevel level = minecraft.level;
            layout.resolve(level.registryAccess());
            cleanerItems.resolve(level.registryAccess());
            catalogNeedsResolve = false;
        }
        return true;
    }

    private void clearPickerReferences() {
        layout.clearResolved();
        cleanerItems.clearResolved();
        catalog().clear();
    }

    private static boolean validEnvironment(Minecraft minecraft, LocalPlayer player) {
        return player != null && minecraft.level != null && minecraft.gameMode != null
                && minecraft.gameMode.getPlayerMode() == GameType.SURVIVAL && player.isAlive()
                && minecraft.screen instanceof InventoryScreen screen
                && screen.getMenu() == player.inventoryMenu && player.containerMenu == player.inventoryMenu;
    }

    private void beginSession(LocalPlayer player, ClientLevel level, InventoryScreen screen) {
        clearSession();
        sessionPlayer = player;
        sessionLevel = level;
        sessionScreen = screen;
        // Every session opens with a human reaction delay of 300-600 ms. No Instant setting
        // shortens it -- those govern the gap between actions, not the time to the first one.
        countdown = ThreadLocalRandom.current().nextInt(REACTION_MIN_TICKS, REACTION_MAX_TICKS + 1);
        nextCleanAt = System.nanoTime();
        previousX = player.getX();
        previousZ = player.getZ();
        previousSelected = player.getInventory().getSelectedSlot();
    }

    /**
     * Per-tick bookkeeping: the movement delta and the manual-interaction cooldown. It must run
     * exactly once a tick, so it stays on the tick loop -- the render loop reads the result
     * through {@link #safeToAct} instead, which would otherwise measure deltas between frames
     * and drain the cooldown several times a tick.
     */
    private void trackPlayer(LocalPlayer player) {
        int selected = player.getInventory().getSelectedSlot();
        double x = player.getX();
        double z = player.getZ();
        double dx = x - previousX;
        double dz = z - previousZ;
        // Only a hotbar change is a manual interaction. Drifting is not: arming the cooldown from
        // it re-armed it every tick the player was still settling, which stalled the module
        // outright rather than pausing it.
        if (selected != previousSelected) {
            manualCooldown = 2;
        }
        previousSelected = selected;
        previousX = x;
        previousZ = z;
        tickBlocked = manualCooldown > 0 || dx * dx + dz * dz > MOVEMENT_EPSILON_SQUARED;
        if (manualCooldown > 0) {
            manualCooldown--;
        }
    }

    /** Pure, so the render loop may ask as often as it likes. */
    private boolean safeToAct(Minecraft minecraft, LocalPlayer player) {
        // tickBlocked is last tick's sample; manualCooldown is also read live, so a click the
        // player makes between two ticks stops the render loop on the very next frame.
        if (tickBlocked || manualCooldown > 0) {
            return false;
        }
        Input input = player.input == null || player.input.keyPresses == null
                ? Input.EMPTY : player.input.keyPresses;
        boolean moving = input.forward() || input.backward() || input.left() || input.right() || input.jump()
                || input.shift() || input.sprint() || player.isSprinting() || player.isShiftKeyDown();
        return !moving && minecraft.isWindowActive() && player.inventoryMenu.getCarried().isEmpty()
                && !minecraft.options.keyAttack.isDown() && !minecraft.options.keyUse.isDown()
                && !player.swinging && !player.isUsingItem() && !minecraft.gameMode.isDestroying();
    }

    private InventoryAction armorAction(LocalPlayer player) {
        Inventory carried = player.getInventory();
        for (EquipmentSlot equipmentSlot : ARMOR_SLOTS) {
            ItemStack equipped = transactions.item(carried, equipmentSlot.getIndex(Inventory.INVENTORY_SIZE));
            ArmorCandidate best = null;
            for (int inventorySlot = 0; inventorySlot < BACKPACK_END; inventorySlot++) {
                ItemStack stack = transactions.item(carried, inventorySlot);
                Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
                if (stack.isEmpty() || equippable == null || equippable.slot() != equipmentSlot
                        || !equippable.canBeEquippedBy(player.getType())
                        || !player.isEquippableInSlot(stack, equipmentSlot) || hasBindingCurse(stack)) {
                    continue;
                }
                ArmorScore score = armorScore(stack, equipmentSlot);
                if (best == null || score.compareTo(best.score()) > 0) {
                    best = new ArmorCandidate(inventorySlot, score);
                }
            }
            if (best == null || (!equipped.isEmpty()
                    && best.score().compareTo(armorScore(equipped, equipmentSlot)) <= 0)) {
                continue;
            }

            if (!equipped.isEmpty()) {
                if (hasBindingCurse(equipped) || carried.getFreeSlot() < 0) {
                    continue;
                }
                int menuSlot = menuSlot(player.inventoryMenu, carried,
                        equipmentSlot.getIndex(Inventory.INVENTORY_SIZE));
                if (menuSlot >= 0 && player.inventoryMenu.getSlot(menuSlot).mayPickup(player)) {
                    return new InventoryAction(menuSlot, 0, ClickType.QUICK_MOVE, WorkKind.ARMOR);
                }
                continue;
            }

            int menuSlot = menuSlot(player.inventoryMenu, carried, best.inventorySlot());
            if (menuSlot >= 0 && player.inventoryMenu.getSlot(menuSlot).mayPickup(player)) {
                return new InventoryAction(menuSlot, 0, ClickType.QUICK_MOVE, WorkKind.ARMOR);
            }
        }
        return null;
    }

    private InventoryAction hotbarAction(LocalPlayer player) {
        Inventory carried = player.getInventory();
        boolean[] protectedHotbar = protectedHotbar(carried);
        for (int destination = 0; destination < Inventory.getSelectionSize(); destination++) {
            List<InventoryPickerSetting.Preference> preferences = layout.slot(destination);
            if (preferences.isEmpty()) {
                continue;
            }
            ItemStack current = transactions.item(carried, destination);
            InventoryPickerSetting.Preference active = activePreference(carried, destination, protectedHotbar,
                    false);

            InventoryPickerSetting.Category currentCategory = current.isEmpty() ? null : catalog().category(current);
            boolean categoryConfigured = currentCategory != null && preferences.stream().anyMatch(preference ->
                    preference instanceof InventoryPickerSetting.CategoryPreference category
                            && category.category() == currentCategory);
            boolean activeExact = active instanceof InventoryPickerSetting.ItemPreference item
                    && item.item().matches(current);
            boolean relocateExcluded = !current.isEmpty() && layout.isExcluded(current)
                    && categoryConfigured && !activeExact;
            boolean backpackOnly = false;
            if (relocateExcluded) {
                int free = freeBackpackSlot(carried);
                if (free >= 0) {
                    int menuSlot = menuSlot(player.inventoryMenu, carried, free);
                    if (menuSlot >= 0) {
                        return new InventoryAction(menuSlot, destination, ClickType.SWAP, WorkKind.HOTBAR);
                    }
                }
                backpackOnly = true;
                active = activePreference(carried, destination, protectedHotbar, true);
            }

            if (active == null) {
                continue;
            }
            HotbarCandidate best = bestCandidate(carried, destination, active, protectedHotbar,
                    backpackOnly);
            if (best == null) {
                continue;
            }
            boolean currentMatches = matches(active, current);
            InventoryPickerSetting.Category scoreCategory = active instanceof InventoryPickerSetting.CategoryPreference category
                    ? category.category() : catalog().category(best.stack());
            if (currentMatches && itemScore(current, scoreCategory).compareTo(best.score()) >= 0) {
                continue;
            }
            int menuSlot = menuSlot(player.inventoryMenu, carried, best.inventorySlot());
            if (menuSlot >= 0 && player.inventoryMenu.getSlot(menuSlot).mayPickup(player)) {
                return new InventoryAction(menuSlot, destination, ClickType.SWAP, WorkKind.HOTBAR);
            }
        }
        return null;
    }

    private boolean[] protectedHotbar(Inventory carried) {
        boolean[] result = new boolean[Inventory.getSelectionSize()];
        for (int destination = 0; destination < result.length; destination++) {
            for (InventoryPickerSetting.Preference preference : layout.slot(destination)) {
                if (hasMatch(carried, preference)) {
                    result[destination] = matches(preference, transactions.item(carried, destination));
                    break;
                }
            }
        }
        return result;
    }

    private InventoryPickerSetting.Preference activePreference(Inventory carried, int destination,
                                                                boolean[] protectedHotbar,
                                                                boolean backpackOnly) {
        for (InventoryPickerSetting.Preference preference : layout.slot(destination)) {
            for (int inventorySlot = 0; inventorySlot < BACKPACK_END; inventorySlot++) {
                boolean protectedSlot = inventorySlot < protectedHotbar.length && protectedHotbar[inventorySlot];
                if (sourceAllowed(inventorySlot, destination, protectedSlot, backpackOnly)
                        && matches(preference, transactions.item(carried, inventorySlot))) {
                    return preference;
                }
            }
        }
        return null;
    }

    private HotbarCandidate bestCandidate(Inventory carried, int destination,
                                          InventoryPickerSetting.Preference preference,
                                          boolean[] protectedHotbar, boolean backpackOnly) {
        HotbarCandidate best = null;
        for (int inventorySlot = 0; inventorySlot < BACKPACK_END; inventorySlot++) {
            ItemStack stack = transactions.item(carried, inventorySlot);
            boolean protectedSlot = inventorySlot < protectedHotbar.length && protectedHotbar[inventorySlot];
            if (!sourceAllowed(inventorySlot, destination, protectedSlot, backpackOnly)
                    || !matches(preference, stack)) {
                continue;
            }
            InventoryPickerSetting.Category category = preference instanceof InventoryPickerSetting.CategoryPreference group
                    ? group.category() : catalog().category(stack);
            HotbarCandidate candidate = new HotbarCandidate(inventorySlot, stack, itemScore(stack, category));
            if (best == null || betterCandidate(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    static boolean sourceAllowed(int inventorySlot, int destination, boolean protectedSlot,
                                 boolean backpackOnly) {
        return (!backpackOnly || inventorySlot == destination || inventorySlot >= BACKPACK_START)
                && (inventorySlot == destination || inventorySlot >= BACKPACK_START || !protectedSlot);
    }

    private static boolean betterCandidate(HotbarCandidate candidate, HotbarCandidate current) {
        int score = candidate.score().compareTo(current.score());
        if (score != 0) {
            return score > 0;
        }
        boolean candidateBackpack = candidate.inventorySlot() >= BACKPACK_START;
        boolean currentBackpack = current.inventorySlot() >= BACKPACK_START;
        return candidateBackpack != currentBackpack ? candidateBackpack
                : candidate.inventorySlot() < current.inventorySlot();
    }

    private boolean hasMatch(Inventory carried, InventoryPickerSetting.Preference preference) {
        for (int inventorySlot = 0; inventorySlot < BACKPACK_END; inventorySlot++) {
            if (matches(preference, transactions.item(carried, inventorySlot))) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(InventoryPickerSetting.Preference preference, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (preference instanceof InventoryPickerSetting.ItemPreference item) {
            return item.item().matches(stack);
        }
        InventoryPickerSetting.CategoryPreference category =
                (InventoryPickerSetting.CategoryPreference) preference;
        return !layout.isExcluded(stack) && catalog().category(stack) == category.category();
    }

    private InventoryAction cleanerAction(LocalPlayer player) {
        Inventory carried = player.getInventory();
        for (int inventorySlot = BACKPACK_START; inventorySlot < BACKPACK_END; inventorySlot++) {
            ItemStack stack = transactions.item(carried, inventorySlot);
            if (stack.isEmpty()) {
                continue;
            }
            InventoryPickerSetting.CleanerMode mode = cleanerItems.effectiveMode(stack);
            if (mode == InventoryPickerSetting.CleanerMode.DROP
                    || mode == InventoryPickerSetting.CleanerMode.KEEP_ONE
                    && inventorySlot != keeperSlot(carried, inventorySlot)) {
                int menuSlot = menuSlot(player.inventoryMenu, carried, inventorySlot);
                if (menuSlot >= 0 && player.inventoryMenu.getSlot(menuSlot).mayPickup(player)) {
                    return new InventoryAction(menuSlot, 1, ClickType.THROW, WorkKind.CLEANER);
                }
            }
        }
        return null;
    }

    private int keeperSlot(Inventory carried, int seedSlot) {
        ItemStack seed = transactions.item(carried, seedSlot);
        ItemStack normalized = InventoryPickerSetting.ItemRef.normalize(seed);
        int keeper = seedSlot;
        // ponytail: bounded 27-slot scan; use grouping only if the player backpack grows materially.
        for (int inventorySlot = BACKPACK_START; inventorySlot < BACKPACK_END; inventorySlot++) {
            ItemStack candidate = transactions.item(carried, inventorySlot);
            if (!candidate.isEmpty() && ItemStack.isSameItemSameComponents(normalized,
                    InventoryPickerSetting.ItemRef.normalize(candidate))
                    && cleanerItems.effectiveMode(candidate) == InventoryPickerSetting.CleanerMode.KEEP_ONE
                    && betterKeeper(candidate.getCount(), inventorySlot, transactions.item(carried, keeper).getCount(), keeper)) {
                keeper = inventorySlot;
            }
        }
        return keeper;
    }

    static boolean betterKeeper(int count, int index, int currentCount, int currentIndex) {
        return count > currentCount || count == currentCount && index < currentIndex;
    }

    private int freeBackpackSlot(Inventory inventory) {
        for (int inventorySlot = BACKPACK_START; inventorySlot < BACKPACK_END; inventorySlot++) {
            if (transactions.item(inventory, inventorySlot).isEmpty()) {
                return inventorySlot;
            }
        }
        return -1;
    }

    private static int menuSlot(InventoryMenu menu, Inventory inventory, int inventorySlot) {
        for (int menuSlot = 0; menuSlot < menu.slots.size(); menuSlot++) {
            Slot slot = menu.slots.get(menuSlot);
            if (slot.container == inventory && slot.getContainerSlot() == inventorySlot) {
                return menuSlot;
            }
        }
        return -1;
    }

    /**
     * Spends one tick of the cooldown and reports whether an action may run now. It does not
     * re-arm the way AutoClicker's equivalent does: a due tick can still find nothing to do, and
     * the gap is only chosen once an action actually lands.
     */
    boolean due() {
        if (countdown > 0) {
            countdown--;
        }
        return countdown == 0;
    }

    void afterAction(WorkKind kind) {
        worked = true;
        boolean instant = switch (kind) {
            case ARMOR -> instantArmor.get();
            case HOTBAR -> instantHotBar.get();
            // Instant Clean drops run on the render loop and never reach this cooldown, so when
            // it is on the branch below is unreachable for CLEANER; when it is off the Delay
            // applies exactly as it does to everything else.
            case CLEANER -> false;
        };
        countdown = instant ? INSTANT_SWAP_GAP_TICKS
                : gapTicks(delay.sampleMillis(), ThreadLocalRandom.current().nextDouble());
    }

    /**
     * A millisecond delay as a whole number of ticks. The module only looks at the world once per
     * tick, so a wall-clock deadline can only ever be served on a tick boundary: 175 ms would
     * always come out as 200 ms, and the shipped 100-200 ms range collapsed to a coin flip
     * between 150 and 200. Rounding the leftover fraction at random instead spends the same
     * ticks in the same proportion, so the <em>mean</em> gap is the one that was asked for.
     */
    static int gapTicks(int delayMillis, double roundSample) {
        double ideal = delayMillis / (double) MILLIS_PER_TICK;
        int gap = (int) ideal;
        return Math.max(1, roundSample < ideal - gap ? gap + 1 : gap);
    }

    static <T> T firstNonNull(Supplier<? extends T> first, Supplier<? extends T> second,
                              Supplier<? extends T> third) {
        T result = first.get();
        if (result == null) result = second.get();
        return result == null ? third.get() : result;
    }

    private void clearSession() {
        transactions.reset();
        sessionPlayer = null;
        sessionLevel = null;
        sessionScreen = null;
        countdown = 0;
        worked = false;
        previousX = 0.0;
        previousZ = 0.0;
        previousSelected = -1;
        manualCooldown = 0;
        tickBlocked = false;
    }

    static ArmorScore armorScore(ItemStack stack, EquipmentSlot slot) {
        double armor = attribute(stack, Attributes.ARMOR, slot);
        double toughness = attribute(stack, Attributes.ARMOR_TOUGHNESS, slot);
        double knockbackResistance = attribute(stack, Attributes.KNOCKBACK_RESISTANCE, slot);
        int protection = 0;
        int defensive = 0;
        int other = 0;
        for (var entry : stack.getEnchantments().entrySet()) {
            Holder<Enchantment> enchantment = entry.getKey();
            if (enchantment.is(EnchantmentTags.CURSE) || !enchantment.value().matchingSlot(slot)
                    || !enchantment.value().isSupportedItem(stack)) {
                continue;
            }
            int level = entry.getIntValue();
            if (enchantment.is(Enchantments.PROTECTION)) {
                protection += level;
            } else if (isDefensive(enchantment)) {
                defensive += level;
            } else {
                other += level;
            }
        }
        return new ArmorScore(armor, toughness, knockbackResistance, protection, defensive, other,
                remainingDurability(stack));
    }

    private static boolean isDefensive(Holder<Enchantment> enchantment) {
        return enchantment.is(Enchantments.FIRE_PROTECTION) || enchantment.is(Enchantments.FEATHER_FALLING)
                || enchantment.is(Enchantments.BLAST_PROTECTION)
                || enchantment.is(Enchantments.PROJECTILE_PROTECTION)
                || enchantment.is(Enchantments.RESPIRATION) || enchantment.is(Enchantments.AQUA_AFFINITY)
                || enchantment.is(Enchantments.THORNS) || enchantment.is(Enchantments.FROST_WALKER);
    }

    private static boolean hasBindingCurse(ItemStack stack) {
        return stack.getEnchantments().keySet().stream().anyMatch(holder -> holder.is(Enchantments.BINDING_CURSE));
    }

    private static double attribute(ItemStack stack, Holder<Attribute> attribute, EquipmentSlot slot) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        return modifiers == null ? 0.0 : modifiers.compute(attribute, 0.0, slot);
    }

    private static ItemScore itemScore(ItemStack stack, InventoryPickerSetting.Category category) {
        if (category == null) {
            category = InventoryPickerSetting.Category.OTHER;
        }
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        EquipmentSlot slot = equippable == null ? EquipmentSlot.MAINHAND : equippable.slot();
        double attack = attribute(stack, Attributes.ATTACK_DAMAGE, EquipmentSlot.MAINHAND);
        double attackSpeed = attribute(stack, Attributes.ATTACK_SPEED, EquipmentSlot.MAINHAND);
        double armor = attribute(stack, Attributes.ARMOR, slot);
        double toughness = attribute(stack, Attributes.ARMOR_TOUGHNESS, slot);
        Tool tool = stack.get(DataComponents.TOOL);
        double toolSpeed = 0.0;
        if (tool != null) {
            toolSpeed = tool.defaultMiningSpeed();
            for (Tool.Rule rule : tool.rules()) {
                toolSpeed = Math.max(toolSpeed, rule.speed().orElse(0.0F));
            }
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        int enchantments = nonCurseEnchantments(stack);
        boolean rangedWeapon = stack.is(ItemTags.BOW_ENCHANTABLE)
                || stack.is(ItemTags.CROSSBOW_ENCHANTABLE) || stack.is(ItemTags.TRIDENT_ENCHANTABLE);
        return switch (category) {
            case SWORD -> new ItemScore(attack, attackSpeed, enchantments, remainingDurability(stack), stack.getCount());
            case BOW -> new ItemScore(rangedWeapon ? 1.0 : 0.0, attack, enchantments,
                    remainingDurability(stack), stack.getCount());
            case ARMOR -> new ItemScore(armor, toughness, enchantments, remainingDurability(stack), stack.getCount());
            case TOOLS -> new ItemScore(toolSpeed, attack, enchantments, remainingDurability(stack), stack.getCount());
            case FOOD -> new ItemScore(food == null ? 0.0 : food.nutrition(),
                    food == null ? 0.0 : food.saturation(), enchantments, remainingDurability(stack), stack.getCount());
            case POTION, THROWABLES, BLOCKS ->
                    new ItemScore(stack.getCount(), 0.0, enchantments, remainingDurability(stack), stack.getCount());
            case OTHER -> new ItemScore(Math.max(attack, armor), Math.max(attackSpeed, toughness), enchantments,
                    remainingDurability(stack), stack.getCount());
        };
    }

    private static int nonCurseEnchantments(ItemStack stack) {
        int total = 0;
        for (var entry : stack.getEnchantments().entrySet()) {
            if (!entry.getKey().is(EnchantmentTags.CURSE)) {
                total += entry.getIntValue();
            }
        }
        return total;
    }

    private static int remainingDurability(ItemStack stack) {
        return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

    enum WorkKind {
        ARMOR,
        HOTBAR,
        CLEANER
    }

    record ArmorScore(double armor, double toughness, double knockbackResistance, int protection,
                      int defensiveEnchantments, int otherEnchantments, int durability)
            implements Comparable<ArmorScore> {
        @Override
        public int compareTo(ArmorScore other) {
            int result = Double.compare(armor, other.armor);
            if (result == 0) result = Double.compare(toughness, other.toughness);
            if (result == 0) result = Double.compare(knockbackResistance, other.knockbackResistance);
            if (result == 0) result = Integer.compare(protection, other.protection);
            if (result == 0) result = Integer.compare(defensiveEnchantments, other.defensiveEnchantments);
            if (result == 0) result = Integer.compare(otherEnchantments, other.otherEnchantments);
            return result == 0 ? Integer.compare(durability, other.durability) : result;
        }
    }

    private record ItemScore(double primary, double secondary, int enchantments, int durability, int count)
            implements Comparable<ItemScore> {
        @Override
        public int compareTo(ItemScore other) {
            int result = Double.compare(primary, other.primary);
            if (result == 0) result = Double.compare(secondary, other.secondary);
            if (result == 0) result = Integer.compare(enchantments, other.enchantments);
            if (result == 0) result = Integer.compare(durability, other.durability);
            return result == 0 ? Integer.compare(count, other.count) : result;
        }
    }

    private record InventoryAction(int menuSlot, int button, ClickType clickType, WorkKind kind) {
    }

    private record ArmorCandidate(int inventorySlot, ArmorScore score) {
    }

    private record HotbarCandidate(int inventorySlot, ItemStack stack, ItemScore score) {
    }
}
