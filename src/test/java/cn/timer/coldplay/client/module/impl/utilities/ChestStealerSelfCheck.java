package cn.timer.coldplay.client.module.impl.utilities;

import cn.timer.coldplay.client.setting.BooleanSetting;
import cn.timer.coldplay.client.setting.RangeSetting;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

public final class ChestStealerSelfCheck {
    private ChestStealerSelfCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkRangeAndDeadlines();
        checkComponentAwareFit();
        checkRankingAndStableVariation();
        checkConfirmation();
        checkAutoClose();
    }

    private static void checkRangeAndDeadlines() {
        ChestStealer stealer = new ChestStealer(new InvManager());
        RangeSetting range = stealer.settings().stream()
                .filter(setting -> setting.name().equals("Delay"))
                .map(RangeSetting.class::cast)
                .findFirst()
                .orElseThrow();
        assert range.lower() == 100 && range.upper() == 200;
        assert range.allowedMinimum() == 25 && range.allowedMaximum() == 400 && range.increment() == 5;
        range.set(new RangeSetting.Value(999, 0));
        assert range.lower() == 25 && range.upper() == 400;
        range.setLower(103);
        range.setUpper(197);
        assert range.lower() == 105 && range.upper() == 195;

        long now = 1_000_000_000L;
        assert ChestStealer.clampDeadline(now, now + 500_000_000L, 25, 400) == now + 400_000_000L;
        assert ChestStealer.clampDeadline(now, now + 10_000_000L, 100, 200) == now + 100_000_000L;
        assert ChestStealer.clampDeadline(now, now - 1L, 100, 200) == now - 1L;
        assert ChestStealer.retryDelay(0, 25, 400) == 25;
        assert ChestStealer.retryDelay(500, 25, 400) == 400;
    }

    private static void checkComponentAwareFit() {
        ItemStack source = new ItemStack(Items.STONE, 20);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("source"));
        ItemStack compatible = source.copyWithCount(30);
        ItemStack incompatible = source.copyWithCount(30);
        incompatible.set(DataComponents.CUSTOM_NAME, Component.literal("different"));

        assert ChestStealer.mergeCapacity(source, compatible, 64) == 34;
        assert ChestStealer.mergeCapacity(source, incompatible, 64) == 0;

        ChestStealer.Fit fullMerge = ChestStealer.fit(20, 40, List.of());
        assert fullMerge.merges() && fullMerge.newSlots() == 0 && fullMerge.transferable() == 20;
        ChestStealer.Fit partial = ChestStealer.fit(64, 10, List.of(20));
        assert partial.merges() && partial.newSlots() == 1 && partial.transferable() == 30;
        ChestStealer.Fit emptySlot = ChestStealer.fit(1, 0, List.of(1));
        assert !emptySlot.merges() && emptySlot.newSlots() == 1 && emptySlot.transferable() == 1;
        assert ChestStealer.fit(64, 0, List.of()).transferable() == 0;
    }

    private static void checkRankingAndStableVariation() {
        ItemStack stack = new ItemStack(Items.STONE);
        ChestStealer.Candidate general = new ChestStealer.Candidate(1, stack,
                new ChestStealer.Fit(false, 1, 64));
        ChestStealer.Candidate merge = new ChestStealer.Candidate(2, stack,
                new ChestStealer.Fit(true, 1, 5));
        assert ChestStealer.bestCandidate(List.of(general, merge), 0) == merge;

        ChestStealer.Candidate compact = new ChestStealer.Candidate(3, stack,
                new ChestStealer.Fit(true, 0, 5));
        assert ChestStealer.bestCandidate(List.of(merge, compact), 0) == compact;

        ChestStealer.Candidate larger = new ChestStealer.Candidate(4, stack,
                new ChestStealer.Fit(false, 1, 32));
        assert ChestStealer.bestCandidate(List.of(general, larger), 0) == general;

        ChestStealer.Fit tie = new ChestStealer.Fit(false, 1, 1);
        List<ChestStealer.Candidate> tied = List.of(
                new ChestStealer.Candidate(1, stack, tie),
                new ChestStealer.Candidate(2, stack, tie),
                new ChestStealer.Candidate(3, stack, tie));
        ChestStealer.Candidate first = ChestStealer.bestCandidate(tied, 0);
        assert first == ChestStealer.bestCandidate(tied, 0);
        assert first != ChestStealer.bestCandidate(tied, -1);
    }

    private static void checkConfirmation() {
        ItemStack sourceBefore = new ItemStack(Items.STONE, 20);
        ItemStack sourceAfter = new ItemStack(Items.STONE, 10);
        assert ChestStealer.transferDelta(sourceBefore, 5, sourceAfter, 15).confirmed();
        assert !ChestStealer.transferDelta(sourceBefore, 5, sourceAfter, 14).confirmed();

        ChestStealer.PendingTransfer success = new ChestStealer.PendingTransfer(0, sourceBefore, 5, 100L);
        assert ChestStealer.observe(success, sourceAfter, 15, 1L) == ChestStealer.Confirmation.WAIT;
        assert ChestStealer.observe(success, sourceAfter, 15, 2L) == ChestStealer.Confirmation.SUCCESS;

        ChestStealer.PendingTransfer rollback = new ChestStealer.PendingTransfer(0, sourceBefore, 5, 100L);
        assert ChestStealer.observe(rollback, sourceAfter, 15, 1L) == ChestStealer.Confirmation.WAIT;
        assert ChestStealer.observe(rollback, sourceBefore, 5, 2L) == ChestStealer.Confirmation.FAILURE;

        ChestStealer.PendingTransfer timeout = new ChestStealer.PendingTransfer(0, sourceBefore, 5, 10L);
        assert ChestStealer.observe(timeout, sourceBefore, 5, 9L) == ChestStealer.Confirmation.WAIT;
        assert ChestStealer.observe(timeout, sourceBefore, 5, 10L) == ChestStealer.Confirmation.FAILURE;
    }

    private static void checkAutoClose() {
        assert ChestStealer.canAutoClose(true, 1, true, false, true, false);
        assert !ChestStealer.canAutoClose(false, 1, true, false, true, false);
        assert !ChestStealer.canAutoClose(true, 0, true, false, true, false);
        assert !ChestStealer.canAutoClose(true, 1, false, false, true, false);
        assert !ChestStealer.canAutoClose(true, 1, true, true, true, false);
        assert !ChestStealer.canAutoClose(true, 1, true, false, false, false);
        assert !ChestStealer.canAutoClose(true, 1, true, false, true, true);

        InvManager manager = new InvManager();
        BooleanSetting inventory = setting(manager, "Inventory");
        BooleanSetting autoClose = setting(manager, "Auto Close");
        inventory.set(true);
        autoClose.set(true);
        assert !manager.effectiveAutoClose();
        manager.setEnabled(true);
        assert manager.effectiveAutoClose();
        autoClose.set(false);
        assert !manager.effectiveAutoClose();
    }

    private static BooleanSetting setting(InvManager manager, String name) {
        return manager.settings().stream()
                .filter(setting -> setting.name().equals(name))
                .map(BooleanSetting.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
