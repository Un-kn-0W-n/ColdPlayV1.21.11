package cn.timer.coldplay.client.module.impl.utilities;

import cn.timer.coldplay.client.setting.InventoryPickerSetting;
import cn.timer.coldplay.client.setting.RangeSetting;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.List;

public final class InvManagerSelfCheck {
    private InvManagerSelfCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkRangeAndDeadline();
        checkArmorOrdering();
        checkLayoutAndExclusions();
        checkCleaner();
        checkTolerantConfig();
        checkPriorityAndOneAction();
        checkModuleOrder();
    }

    private static void checkRangeAndDeadline() {
        RangeSetting range = new RangeSetting("Delay", 100, 200, 50, 1000, 10);
        range.set(new RangeSetting.Value(910, 90));
        assert range.lower() == 90 && range.upper() == 910;
        range.setLower(1000);
        assert range.lower() == 910 && range.upper() == 910;
        range.setUpper(40);
        assert range.lower() == 910 && range.upper() == 910;
        range.set(new RangeSetting.Value(170, 170));
        int[] samples = {0};
        long deadline = InvManager.sampledDeadline(5_000L, false, () -> {
            samples[0]++;
            return range.sampleMillis();
        });
        assert samples[0] == 1;
        assert deadline == 170_005_000L;
        assert InvManager.sampledDeadline(5_000L, true, () -> {
            samples[0]++;
            return 999;
        }) == 0L;
        assert samples[0] == 1;
    }

    private static void checkArmorOrdering() {
        InvManager.ArmorScore base = new InvManager.ArmorScore(5.0, 2.0, 0.0, 4, 2, 1, 100);
        assert new InvManager.ArmorScore(6.0, 0.0, 0.0, 0, 0, 0, 1).compareTo(base) > 0;
        assert new InvManager.ArmorScore(5.0, 2.0, 0.0, 5, 0, 0, 1).compareTo(base) > 0;
        assert new InvManager.ArmorScore(5.0, 2.0, 0.0, 4, 2, 1, 101).compareTo(base) > 0;
        assert new InvManager.ArmorScore(5.0, 2.0, 0.0, 4, 2, 1, 100).compareTo(base) == 0;
    }

    private static void checkLayoutAndExclusions() {
        InventoryPickerSetting.Layout layout = new InventoryPickerSetting.Layout("Layout");
        var sword = new InventoryPickerSetting.CategoryPreference(InventoryPickerSetting.Category.SWORD);
        var bow = new InventoryPickerSetting.CategoryPreference(InventoryPickerSetting.Category.BOW);
        layout.assign(0, sword);
        layout.assign(0, bow);
        layout.assign(0, sword);
        assert layout.slot(0).equals(List.of(bow, sword));
        assert layout.remove(0, 0).equals(bow);
        assert layout.slot(0).equals(List.of(sword));

        InventoryPickerSetting.ItemRef item = new InventoryPickerSetting.ItemRef(itemJson("minecraft:stone"));
        JsonObject damagedJson = itemJson("minecraft:stone");
        damagedJson.addProperty("count", 64);
        JsonObject components = new JsonObject();
        components.addProperty("minecraft:damage", 12);
        damagedJson.add("components", components);
        assert new InventoryPickerSetting.ItemRef(damagedJson).equals(item);
        var exact = new InventoryPickerSetting.ItemPreference(item);
        layout.assign(0, exact);
        assert layout.toggle(item) && layout.isExcluded(item);
        assert layout.slot(0).contains(exact); // Exclusion applies to category matching, not exact preferences.
        assert !InvManager.sourceAllowed(1, 0, false, true);
        assert InvManager.sourceAllowed(9, 0, false, true);
        assert InvManager.sourceAllowed(0, 0, false, true);
    }

    private static void checkCleaner() {
        InventoryPickerSetting.Cleaner cleaner = new InventoryPickerSetting.Cleaner("Items");
        InventoryPickerSetting.ItemRef item = new InventoryPickerSetting.ItemRef(itemJson("minecraft:stone"));
        cleaner.setCategoryMode(InventoryPickerSetting.Category.BLOCKS, InventoryPickerSetting.CleanerMode.DROP);
        cleaner.setItemMode(item, InventoryPickerSetting.CleanerMode.IGNORE);
        assert cleaner.effectiveMode(item) == InventoryPickerSetting.CleanerMode.IGNORE;
        assert InvManager.betterKeeper(64, 20, 32, 9);
        assert InvManager.betterKeeper(64, 10, 64, 20);
        assert !InvManager.betterKeeper(64, 20, 64, 10);
    }

    private static void checkTolerantConfig() {
        InventoryPickerSetting.Layout layout = new InventoryPickerSetting.Layout("Layout");
        JsonObject root = new JsonObject();
        JsonArray slots = new JsonArray();
        JsonArray first = new JsonArray();
        JsonObject valid = new JsonObject();
        valid.addProperty("category", "SWORD");
        first.add(valid);
        first.add("wrong type");
        JsonObject removed = new JsonObject();
        removed.addProperty("category", "REMOVED_CATEGORY");
        first.add(removed);
        slots.add(first);
        root.add("slots", slots);
        JsonArray exclusions = new JsonArray();
        exclusions.add("wrong type");
        root.add("exclusions", exclusions);
        layout.fromJson(root);
        assert layout.slot(0).equals(List.of(
                new InventoryPickerSetting.CategoryPreference(InventoryPickerSetting.Category.SWORD)));

        InventoryPickerSetting.Cleaner cleaner = new InventoryPickerSetting.Cleaner("Items");
        JsonObject cleanerJson = new JsonObject();
        JsonObject categories = new JsonObject();
        categories.addProperty("BLOCKS", "KEEP_ONE");
        categories.addProperty("REMOVED_CATEGORY", "DROP");
        categories.addProperty("SWORD", "REMOVED_MODE");
        cleanerJson.add("categories", categories);
        cleaner.fromJson(cleanerJson);
        assert cleaner.categoryMode(InventoryPickerSetting.Category.BLOCKS)
                == InventoryPickerSetting.CleanerMode.KEEP_ONE;
        assert cleaner.categoryMode(InventoryPickerSetting.Category.SWORD)
                == InventoryPickerSetting.CleanerMode.UNSET;
    }

    private static void checkModuleOrder() {
        InvManager manager = new InvManager();
        List<String> names = manager.settings().stream().map(setting -> setting.name()).toList();
        assert names.subList(1, names.size()).equals(List.of(
                "Delay", "AutoArmor", "Instant Swap", "AutoHotBar", "Instant Swap", "Layout",
                "Cleaner", "Instant Clean", "Items", "Inventory", "Auto Close"));
        assert InvManager.WorkKind.ARMOR.ordinal() < InvManager.WorkKind.HOTBAR.ordinal();
        assert InvManager.WorkKind.HOTBAR.ordinal() < InvManager.WorkKind.CLEANER.ordinal();
    }

    private static void checkPriorityAndOneAction() {
        int[] evaluated = {0};
        String armor = InvManager.firstNonNull(
                () -> {
                    evaluated[0]++;
                    return "armor";
                },
                () -> {
                    evaluated[0]++;
                    return "hotbar";
                },
                () -> {
                    evaluated[0]++;
                    return "cleaner";
                });
        assert armor.equals("armor") && evaluated[0] == 1;

        evaluated[0] = 0;
        String selected = InvManager.firstNonNull(
                () -> {
                    evaluated[0]++;
                    return null;
                },
                () -> {
                    evaluated[0]++;
                    return "hotbar";
                },
                () -> {
                    evaluated[0]++;
                    return "cleaner";
                });
        assert selected.equals("hotbar");
        assert evaluated[0] == 2;
    }

    private static JsonObject itemJson(String id) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        return json;
    }
}
