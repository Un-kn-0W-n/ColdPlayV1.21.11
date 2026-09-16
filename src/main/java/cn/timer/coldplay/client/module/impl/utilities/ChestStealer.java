package cn.timer.coldplay.client.module.impl.utilities;

import cn.timer.coldplay.client.module.Category;
import cn.timer.coldplay.client.module.Module;
import cn.timer.coldplay.client.setting.RangeSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class ChestStealer extends Module {
    private static final long CONFIRMATION_TIMEOUT_NANOS = 1_000_000_000L;
    private static final double MOVEMENT_EPSILON_SQUARED = 1.0E-8;

    private final RangeSetting delay = addSetting(new RangeSetting("Delay", 100, 200, 25, 400, 5));
    private final InvManager invManager;
    private final Map<Integer, ItemStack> failedSlots = new HashMap<>();

    private LocalPlayer sessionPlayer;
    private ClientLevel sessionLevel;
    private AbstractContainerScreen<?> sessionScreen;
    private AbstractContainerMenu sessionMenu;
    private Container sessionContainer;
    private long reactionDeadline;
    private long actionDeadline;
    private int lastDelayMillis;
    private int previousDelayMinimum = delay.lower();
    private int previousDelayMaximum = delay.upper();
    private int successfulTransfers;
    private int tieSeed;
    private double previousX;
    private double previousY;
    private double previousZ;
    private int previousSelected;
    private int manualCooldown;
    private boolean manualConflict;
    private PendingTransfer pending;
    private PendingTransfer lastConfirmed;
    private List<ItemStack> blockedState;

    public ChestStealer(InvManager invManager) {
        super("ChestStealer", "Steals every fitting item from opened storage containers",
                Category.UTILITIES, GLFW.GLFW_KEY_UNKNOWN);
        this.invManager = Objects.requireNonNull(invManager, "invManager");
    }

    public void tick(Minecraft minecraft) {
        if (!enabled()) {
            clearSession();
            return;
        }
        Context context = context(minecraft);
        if (context == null) {
            clearSession();
            return;
        }

        long now = System.nanoTime();
        if (!sameSession(context)) {
            beginSession(context, now);
            return;
        }

        clampActionDeadline(now);
        boolean safe = safeToAct(minecraft, context);
        if (pending != null) {
            updatePending(context, now);
            return;
        }
        if (lastConfirmed != null && !currentDelta(context, lastConfirmed).confirmed()) {
            fail(context, lastConfirmed, now, true);
            return;
        }
        if (blockedState != null) {
            if (sameMenuState(context.menu(), blockedState)) {
                return;
            }
            blockedState = null;
        }
        pruneFailures(context);
        if (!safe || now - reactionDeadline < 0L || now - actionDeadline < 0L) {
            return;
        }

        // ponytail: bounded vanilla-menu scan; index player slots only if larger custom menus are supported.
        Candidate candidate = bestCandidate(candidates(context), tieSeed);
        if (candidate == null) {
            boolean quarantined = !failedSlots.isEmpty();
            if (canAutoClose(invManager.effectiveAutoClose(), successfulTransfers, true,
                    false, safe, quarantined)) {
                context.screen().onClose();
                clearSession();
            }
            return;
        }

        Slot source = context.menu().getSlot(candidate.menuSlot());
        if (!eligibleSource(context, source)
                || !ItemStack.matches(candidate.stack(), source.getItem())
                || fit(context, source.getItem()).transferable() <= 0) {
            return;
        }

        ItemStack sourceBefore = source.getItem().copy();
        int playerBefore = countMatching(context, sourceBefore);
        PendingTransfer transfer = new PendingTransfer(candidate.menuSlot(), sourceBefore, playerBefore,
                now + CONFIRMATION_TIMEOUT_NANOS);
        lastConfirmed = null;
        minecraft.gameMode.handleInventoryMouseClick(context.menu().containerId, candidate.menuSlot(), 0,
                ClickType.QUICK_MOVE, context.player());
        pending = transfer;
    }

    public void markManualInteraction() {
        if (sessionScreen != null) {
            manualCooldown = 2;
            manualConflict |= pending != null;
            lastConfirmed = null;
        }
    }

    public void reset() {
        clearSession();
    }

    @Override
    protected void onEnable() {
        clearSession();
    }

    @Override
    protected void onDisable() {
        clearSession();
    }

    private static Context context(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.gameMode == null || !player.isAlive()
                || minecraft.gameMode.getPlayerMode() != GameType.SURVIVAL) {
            return null;
        }

        AbstractContainerScreen<?> screen;
        AbstractContainerMenu menu;
        if (minecraft.screen instanceof ContainerScreen containerScreen) {
            screen = containerScreen;
            menu = containerScreen.getMenu();
        } else if (minecraft.screen instanceof ShulkerBoxScreen shulkerBoxScreen) {
            screen = shulkerBoxScreen;
            menu = shulkerBoxScreen.getMenu();
        } else {
            return null;
        }
        if (screen.getMenu() != menu || player.containerMenu != menu || !menu.stillValid(player)) {
            return null;
        }

        // Server NPC and compass menus reuse chest screens, so demand a real container block under the crosshair.
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || level.getBlockState(hit.getBlockPos()).getMenuProvider(level, hit.getBlockPos()) == null) {
            return null;
        }

        Inventory inventory = player.getInventory();
        Container source = sourceContainer(menu, inventory);
        return source == null ? null : new Context(player, level, screen, menu, source, inventory);
    }

    private static Container sourceContainer(AbstractContainerMenu menu, Inventory inventory) {
        Container source = null;
        for (Slot slot : menu.slots) {
            if (slot.container == inventory) {
                continue;
            }
            if (source == null) {
                source = slot.container;
            } else if (source != slot.container) {
                return null;
            }
        }
        return source;
    }

    private boolean sameSession(Context context) {
        return context.player() == sessionPlayer && context.level() == sessionLevel
                && context.screen() == sessionScreen && context.menu() == sessionMenu
                && context.source() == sessionContainer;
    }

    private void beginSession(Context context, long now) {
        clearSession();
        sessionPlayer = context.player();
        sessionLevel = context.level();
        sessionScreen = context.screen();
        sessionMenu = context.menu();
        sessionContainer = context.source();
        reactionDeadline = now + ThreadLocalRandom.current().nextLong(300L, 601L) * 1_000_000L;
        tieSeed = ThreadLocalRandom.current().nextInt();
        previousX = context.player().getX();
        previousY = context.player().getY();
        previousZ = context.player().getZ();
        previousSelected = context.inventory().getSelectedSlot();
    }

    private boolean safeToAct(Minecraft minecraft, Context context) {
        LocalPlayer player = context.player();
        int selected = context.inventory().getSelectedSlot();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        double dx = x - previousX;
        double dy = y - previousY;
        double dz = z - previousZ;
        if (selected != previousSelected || dx * dx + dy * dy + dz * dz > MOVEMENT_EPSILON_SQUARED) {
            manualCooldown = 2;
        }
        previousSelected = selected;
        previousX = x;
        previousY = y;
        previousZ = z;

        if (manualCooldown > 0) {
            manualCooldown--;
            return false;
        }
        Input input = player.input == null || player.input.keyPresses == null
                ? Input.EMPTY : player.input.keyPresses;
        boolean moving = input.forward() || input.backward() || input.left() || input.right() || input.jump()
                || input.shift() || input.sprint() || player.isSprinting() || player.isShiftKeyDown();
        return !moving && minecraft.isWindowActive() && context.menu().getCarried().isEmpty()
                && !minecraft.options.keyAttack.isDown() && !minecraft.options.keyUse.isDown()
                && !player.swinging && !player.isUsingItem() && !minecraft.gameMode.isDestroying();
    }

    private List<Candidate> candidates(Context context) {
        List<Candidate> result = new ArrayList<>();
        for (int menuSlot = 0; menuSlot < context.menu().slots.size(); menuSlot++) {
            Slot slot = context.menu().slots.get(menuSlot);
            if (!eligibleSource(context, slot)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            ItemStack failed = failedSlots.get(menuSlot);
            if (failed != null && ItemStack.matches(failed, stack)) {
                continue;
            }
            Fit fit = fit(context, stack);
            if (fit.transferable() > 0) {
                result.add(new Candidate(menuSlot, stack.copy(), fit));
            }
        }
        return result;
    }

    private static boolean eligibleSource(Context context, Slot slot) {
        return slot.container == context.source() && slot.isActive() && !slot.isFake() && slot.hasItem()
                && slot.mayPickup(context.player()) && slot.allowModification(context.player())
                && slot.container.canTakeItem(context.inventory(), slot.getContainerSlot(), slot.getItem());
    }

    private static Fit fit(Context context, ItemStack source) {
        int mergeCapacity = 0;
        List<Integer> emptyCapacities = new ArrayList<>();
        for (Slot destination : context.menu().slots) {
            if (destination.container != context.inventory() || !destination.isActive() || destination.isFake()
                    || !destination.mayPlace(source)) {
                continue;
            }
            int maximum = destination.getMaxStackSize(source);
            if (maximum <= 0) {
                continue;
            }
            ItemStack current = destination.getItem();
            if (current.isEmpty()) {
                emptyCapacities.add(maximum);
            } else {
                mergeCapacity += mergeCapacity(source, current, maximum);
            }
        }
        return fit(source.getCount(), mergeCapacity, emptyCapacities);
    }

    static int mergeCapacity(ItemStack source, ItemStack destination, int maximum) {
        return source.isEmpty() || destination.isEmpty()
                || !ItemStack.isSameItemSameComponents(source, destination)
                ? 0 : Math.max(0, maximum - destination.getCount());
    }

    static Fit fit(int sourceCount, int mergeCapacity, List<Integer> emptyCapacities) {
        if (sourceCount <= 0) {
            return Fit.NONE;
        }
        int emptyCapacity = emptyCapacities.stream().mapToInt(capacity -> Math.max(0, capacity)).sum();
        int transferable = Math.min(sourceCount, Math.max(0, mergeCapacity) + emptyCapacity);
        if (transferable <= 0) {
            return Fit.NONE;
        }

        int remaining = Math.max(0, transferable - Math.min(transferable, Math.max(0, mergeCapacity)));
        int newSlots = 0;
        List<Integer> largestFirst = new ArrayList<>(emptyCapacities);
        largestFirst.sort(Comparator.reverseOrder());
        for (int capacity : largestFirst) {
            if (remaining <= 0) {
                break;
            }
            if (capacity > 0) {
                remaining -= capacity;
                newSlots++;
            }
        }
        return new Fit(mergeCapacity > 0, newSlots, transferable);
    }

    static Candidate bestCandidate(List<Candidate> candidates, int tieSeed) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (best == null || better(candidate, best, tieSeed)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean better(Candidate candidate, Candidate current, int tieSeed) {
        if (candidate.fit().merges() != current.fit().merges()) {
            return candidate.fit().merges();
        }
        if (candidate.fit().newSlots() != current.fit().newSlots()) {
            return candidate.fit().newSlots() < current.fit().newSlots();
        }
        if (candidate.fit().transferable() != current.fit().transferable()) {
            return candidate.fit().transferable() > current.fit().transferable();
        }
        return Integer.compareUnsigned(candidate.menuSlot() ^ tieSeed, current.menuSlot() ^ tieSeed) < 0;
    }

    private void updatePending(Context context, long now) {
        PendingTransfer transfer = pending;
        if (manualConflict) {
            fail(context, transfer, now, false);
            return;
        }
        ItemStack sourceNow = currentSource(context, transfer);
        Confirmation confirmation = observe(transfer, sourceNow, countMatching(context, transfer.sourceBefore), now);
        if (confirmation == Confirmation.WAIT) {
            return;
        }
        pending = null;
        if (confirmation == Confirmation.FAILURE) {
            fail(context, transfer, now, false);
            return;
        }

        successfulTransfers++;
        manualConflict = false;
        lastConfirmed = transfer;
        lastDelayMillis = delay.sampleMillis();
        actionDeadline = deadlineAfter(now, lastDelayMillis);
    }

    private TransferDelta currentDelta(Context context, PendingTransfer transfer) {
        return transferDelta(transfer.sourceBefore, transfer.playerBefore,
                currentSource(context, transfer), countMatching(context, transfer.sourceBefore));
    }

    private ItemStack currentSource(Context context, PendingTransfer transfer) {
        if (transfer.menuSlot < 0 || transfer.menuSlot >= context.menu().slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = context.menu().slots.get(transfer.menuSlot);
        return slot.container == context.source() ? slot.getItem() : ItemStack.EMPTY;
    }

    private static int countMatching(Context context, ItemStack source) {
        int count = 0;
        for (Slot slot : context.menu().slots) {
            ItemStack stack = slot.getItem();
            if (slot.container == context.inventory() && !stack.isEmpty()
                    && ItemStack.isSameItemSameComponents(source, stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    static TransferDelta transferDelta(ItemStack sourceBefore, int playerBefore,
                                       ItemStack sourceNow, int playerNow) {
        int remaining = ItemStack.isSameItemSameComponents(sourceBefore, sourceNow)
                ? sourceNow.getCount() : 0;
        return new TransferDelta(sourceBefore.getCount() - remaining, playerNow - playerBefore);
    }

    static Confirmation observe(PendingTransfer transfer, ItemStack sourceNow, int playerNow, long now) {
        if (transferDelta(transfer.sourceBefore, transfer.playerBefore, sourceNow, playerNow).confirmed()) {
            transfer.stableTicks++;
            return transfer.stableTicks >= 2 ? Confirmation.SUCCESS : Confirmation.WAIT;
        }
        if (transfer.stableTicks > 0 || now - transfer.timeoutDeadline >= 0L) {
            return Confirmation.FAILURE;
        }
        return Confirmation.WAIT;
    }

    private void fail(Context context, PendingTransfer transfer, long now, boolean undoSuccess) {
        if (undoSuccess && successfulTransfers > 0) {
            successfulTransfers--;
        }
        failedSlots.put(transfer.menuSlot, transfer.sourceBefore.copy());
        blockedState = menuSnapshot(context.menu());
        pending = null;
        manualConflict = false;
        lastConfirmed = null;
        int retryMillis = retryDelay(lastDelayMillis, delay.lower(), delay.upper());
        actionDeadline = deadlineAfter(now, retryMillis);
    }

    private void pruneFailures(Context context) {
        failedSlots.entrySet().removeIf(entry -> entry.getKey() < 0
                || entry.getKey() >= context.menu().slots.size()
                || context.menu().slots.get(entry.getKey()).container != context.source()
                || !ItemStack.matches(entry.getValue(), context.menu().slots.get(entry.getKey()).getItem()));
    }

    private static List<ItemStack> menuSnapshot(AbstractContainerMenu menu) {
        List<ItemStack> snapshot = new ArrayList<>(menu.slots.size() + 1);
        for (Slot slot : menu.slots) {
            snapshot.add(slot.getItem().copy());
        }
        snapshot.add(menu.getCarried().copy());
        return snapshot;
    }

    private static boolean sameMenuState(AbstractContainerMenu menu, List<ItemStack> snapshot) {
        if (snapshot.size() != menu.slots.size() + 1) {
            return false;
        }
        for (int index = 0; index < menu.slots.size(); index++) {
            if (!ItemStack.matches(snapshot.get(index), menu.slots.get(index).getItem())) {
                return false;
            }
        }
        return ItemStack.matches(snapshot.getLast(), menu.getCarried());
    }

    static boolean canAutoClose(boolean effectiveAutoClose, int successfulTransfers, boolean complete,
                                boolean pending, boolean safe, boolean quarantined) {
        return effectiveAutoClose && successfulTransfers > 0 && complete && !pending && safe && !quarantined;
    }

    private void clampActionDeadline(long now) {
        int minimum = delay.lower();
        int maximum = delay.upper();
        if (minimum == previousDelayMinimum && maximum == previousDelayMaximum) {
            return;
        }
        actionDeadline = clampDeadline(now, actionDeadline, minimum, maximum);
        if (lastDelayMillis > 0) {
            lastDelayMillis = Math.clamp(lastDelayMillis, minimum, maximum);
        }
        previousDelayMinimum = minimum;
        previousDelayMaximum = maximum;
    }

    static long clampDeadline(long now, long deadline, int minimumMillis, int maximumMillis) {
        if (deadline - now <= 0L) {
            return deadline;
        }
        long remaining = deadline - now;
        long minimum = minimumMillis * 1_000_000L;
        long maximum = maximumMillis * 1_000_000L;
        return now + Math.max(minimum, Math.min(remaining, maximum));
    }

    static int retryDelay(int lastDelayMillis, int minimumMillis, int maximumMillis) {
        return lastDelayMillis > 0 ? Math.clamp(lastDelayMillis, minimumMillis, maximumMillis) : minimumMillis;
    }

    static long deadlineAfter(long now, int delayMillis) {
        return now + delayMillis * 1_000_000L;
    }

    private void clearSession() {
        sessionPlayer = null;
        sessionLevel = null;
        sessionScreen = null;
        sessionMenu = null;
        sessionContainer = null;
        reactionDeadline = 0L;
        actionDeadline = 0L;
        lastDelayMillis = 0;
        successfulTransfers = 0;
        tieSeed = 0;
        previousX = 0.0;
        previousY = 0.0;
        previousZ = 0.0;
        previousSelected = -1;
        manualCooldown = 0;
        manualConflict = false;
        pending = null;
        lastConfirmed = null;
        blockedState = null;
        failedSlots.clear();
        previousDelayMinimum = delay.lower();
        previousDelayMaximum = delay.upper();
    }

    record Fit(boolean merges, int newSlots, int transferable) {
        static final Fit NONE = new Fit(false, 0, 0);
    }

    record Candidate(int menuSlot, ItemStack stack, Fit fit) {
    }

    record TransferDelta(int sourceDecrease, int playerIncrease) {
        boolean confirmed() {
            return sourceDecrease > 0 && sourceDecrease == playerIncrease;
        }
    }

    enum Confirmation {
        WAIT,
        SUCCESS,
        FAILURE
    }

    static final class PendingTransfer {
        final int menuSlot;
        final ItemStack sourceBefore;
        final int playerBefore;
        final long timeoutDeadline;
        int stableTicks;

        PendingTransfer(int menuSlot, ItemStack sourceBefore, int playerBefore, long timeoutDeadline) {
            this.menuSlot = menuSlot;
            this.sourceBefore = sourceBefore.copy();
            this.playerBefore = playerBefore;
            this.timeoutDeadline = timeoutDeadline;
        }
    }

    private record Context(LocalPlayer player, ClientLevel level, AbstractContainerScreen<?> screen,
                           AbstractContainerMenu menu, Container source, Inventory inventory) {
    }
}
