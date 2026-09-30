package cn.timer.coldplay.client.manager;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Holds an automated click's outcome true while the server catches up, so a burst of them can
 * leave in a single tick.
 *
 * <p>On 1.8.9 nothing like this was needed. A click carried its own transaction id and the
 * resulting stack; the server compared that one slot, confirmed it, and detectAndSendChanges then
 * found nothing to correct, so <em>it sent nothing back</em>. The client's own optimistic state
 * simply stood, and the next scan saw the slot it had just emptied. Thirty-six clicks could leave
 * in one tick and every one of them landed.
 *
 * <p>Since 1.17 the click packet carries a container state id that only the server ever advances
 * (incrementStateId is called nowhere but the two ServerPlayer send methods). Every click sent
 * before the previous one's update has crossed the wire therefore looks stale, and the server
 * answers it with a full container resync -- handleContainerClick calls broadcastFullState. The
 * click itself is never refused; clicked() runs before that branch. But the resync rewinds the
 * client's view by one round trip, and a module that re-reads that view drops the same slot twice
 * and never gets ahead of the wire.
 *
 * <p>So reads are answered from what the click was predicted to leave behind, until the server
 * has plainly caught up. A prediction that is never borne out expires, exactly as the 1.8.9 broker
 * gave up on a click the server never answered: one it never answered is one it never ran.
 */
public final class InventoryTransactions {
    /**
     * How long a prediction is trusted before the live slot is believed again. The 1.8.9 broker
     * allowed 40 sweeps for the same reason; at two seconds this outlasts any sane round trip, and
     * the only cost of being wrong is that a refused click is retried a little later.
     */
    private static final int GRACE_TICKS = 40;

    private final Map<Integer, Prediction> predictions = new HashMap<>();
    private AbstractContainerMenu menu;

    private static final class Prediction {
        private final ItemStack expected;
        private int age;

        private Prediction(ItemStack expected) {
            this.expected = expected;
        }
    }

    /** Once per tick. A different container drops every prediction; otherwise stale ones expire. */
    public void begin(AbstractContainerMenu menu) {
        if (this.menu != menu) {
            reset();
            this.menu = menu;
            return;
        }
        Iterator<Prediction> pending = predictions.values().iterator();
        while (pending.hasNext()) {
            if (++pending.next().age > GRACE_TICKS) {
                pending.remove();
            }
        }
    }

    public void reset() {
        menu = null;
        predictions.clear();
    }

    /** Whether every click issued so far has been accounted for. */
    public boolean settled() {
        return predictions.isEmpty();
    }

    /** What the slot holds, as far as the clicks already sent are concerned. */
    public ItemStack item(Inventory carried, int slot) {
        Prediction prediction = predictions.get(slot);
        return prediction == null ? carried.getItem(slot) : prediction.expected;
    }

    /** A whole-stack throw empties the slot it came from. */
    public void threw(int slot) {
        predictions.put(slot, new Prediction(ItemStack.EMPTY));
    }

    /**
     * A shift-click moves the whole stack out of the slot. Where vanilla puts it is its own
     * business, and predicting that badly would be worse than not predicting it at all -- the
     * slot emptying is the part that stops the same click being sent twice.
     */
    public void quickMoved(int slot) {
        predictions.put(slot, new Prediction(ItemStack.EMPTY));
    }

    /** A hotbar swap exchanges the two slots. */
    public void swapped(Inventory carried, int source, int destination) {
        ItemStack fromSource = item(carried, source).copy();
        ItemStack fromDestination = item(carried, destination).copy();
        predictions.put(source, new Prediction(fromDestination));
        predictions.put(destination, new Prediction(fromSource));
    }
}
