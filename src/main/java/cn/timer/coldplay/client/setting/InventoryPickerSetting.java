package cn.timer.coldplay.client.setting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public abstract class InventoryPickerSetting<T> extends Setting<T> {
    private static final Catalog SHARED_CATALOG = new Catalog();

    protected InventoryPickerSetting(String name) {
        super(name);
    }

    public final Catalog catalog() {
        return SHARED_CATALOG;
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement json);

    public abstract void resolve(HolderLookup.Provider provider);

    public abstract void clearResolved();

    public enum Category {
        SWORD("Sword", Items.DIAMOND_SWORD),
        BOW("Bow", Items.BOW),
        ARMOR("Armor", Items.DIAMOND_CHESTPLATE),
        TOOLS("Tools", Items.DIAMOND_PICKAXE),
        FOOD("Food", Items.COOKED_BEEF),
        POTION("Potion", Items.POTION),
        THROWABLES("Throwables", Items.ENDER_PEARL),
        BLOCKS("Blocks", Items.STONE),
        OTHER("Other", Items.NETHER_STAR);

        private final String displayName;
        private final Item icon;

        Category(String displayName, Item icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String displayName() {
            return displayName;
        }

        public ItemStack icon() {
            return new ItemStack(icon);
        }
    }

    public enum CleanerMode {
        UNSET,
        DROP,
        KEEP_ONE,
        IGNORE;

        public CleanerMode cycle(int direction) {
            int step = direction < 0 ? -1 : 1;
            CleanerMode[] modes = values();
            return modes[Math.floorMod(ordinal() + step, modes.length)];
        }
    }

    public sealed interface Preference permits CategoryPreference, ItemPreference {
    }

    public record CategoryPreference(Category category) implements Preference {
        public CategoryPreference {
            Objects.requireNonNull(category, "category");
        }
    }

    public record ItemPreference(ItemRef item) implements Preference {
        public ItemPreference {
            Objects.requireNonNull(item, "item");
        }
    }

    public static final class ItemRef {
        private final JsonElement raw;
        private ItemStack stack;
        private HolderLookup.Provider resolvedProvider;

        public ItemRef(JsonElement raw) {
            this(raw, null);
        }

        private ItemRef(JsonElement raw, ItemStack stack) {
            if (raw == null || raw.isJsonNull()) {
                throw new IllegalArgumentException("Item reference JSON is required");
            }
            this.raw = normalizeRaw(raw);
            this.stack = stack;
        }

        public static ItemRef of(ItemStack stack, HolderLookup.Provider provider) {
            Objects.requireNonNull(provider, "provider");
            ItemStack normalized = normalize(stack);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Empty item stack");
            }
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, provider);
            JsonElement raw = ItemStack.STRICT_SINGLE_ITEM_CODEC.encodeStart(ops, normalized)
                    .result()
                    .orElseThrow(() -> new IllegalArgumentException("Item stack cannot be encoded"));
            ItemRef ref = new ItemRef(raw, normalized);
            ref.resolvedProvider = provider;
            return ref;
        }

        public JsonElement raw() {
            return raw.deepCopy();
        }

        public ItemStack stack() {
            return stack == null ? ItemStack.EMPTY : stack;
        }

        public boolean resolved() {
            return stack != null;
        }

        public boolean resolve(HolderLookup.Provider provider) {
            Objects.requireNonNull(provider, "provider");
            if (stack != null && resolvedProvider == provider) {
                return true;
            }
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, provider);
            stack = ItemStack.STRICT_SINGLE_ITEM_CODEC.parse(ops, raw)
                    .result()
                    .filter(candidate -> !candidate.isEmpty())
                    .map(ItemRef::normalize)
                    .orElse(null);
            resolvedProvider = stack == null ? null : provider;
            return stack != null;
        }

        public void clearResolved() {
            stack = null;
            resolvedProvider = null;
        }

        public boolean matches(ItemStack candidate) {
            return stack != null && ItemStack.isSameItemSameComponents(stack, normalize(candidate));
        }

        public static ItemStack normalize(ItemStack stack) {
            Objects.requireNonNull(stack, "stack");
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack normalized = stack.copyWithCount(1);
            if (normalized.getMaxDamage() > 0) {
                normalized.setDamageValue(0);
            }
            return normalized;
        }

        private static JsonElement normalizeRaw(JsonElement source) {
            JsonElement normalized = source.deepCopy();
            if (!normalized.isJsonObject()) {
                return normalized;
            }
            JsonObject item = normalized.getAsJsonObject();
            item.remove("count");
            JsonElement serializedComponents = item.get("components");
            if (serializedComponents != null && serializedComponents.isJsonObject()) {
                JsonObject components = serializedComponents.getAsJsonObject();
                components.remove("minecraft:damage");
                if (components.isEmpty()) {
                    item.remove("components");
                }
            }
            return normalized;
        }

        @Override
        public boolean equals(Object object) {
            return this == object || object instanceof ItemRef other && raw.equals(other.raw);
        }

        @Override
        public int hashCode() {
            return raw.hashCode();
        }

        @Override
        public String toString() {
            return raw.toString();
        }
    }

    public record Entry(Category category, ItemRef ref, String name, String id) {
        public Entry {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(id, "id");
        }

        public ItemStack stack() {
            return ref.stack();
        }
    }

    public static final class Catalog {
        private final EnumMap<Category, List<Entry>> entries = new EnumMap<>(Category.class);
        private HolderLookup.Provider provider;
        private boolean ready;

        private Catalog() {
            for (Category category : Category.values()) {
                entries.put(category, List.of());
            }
        }

        public boolean ensure(Minecraft minecraft) {
            if (minecraft == null || minecraft.player == null || minecraft.level == null) {
                clear();
                return false;
            }
            HolderLookup.Provider currentProvider = minecraft.level.registryAccess();
            if (ready && provider == currentProvider) {
                return true;
            }

            clear();
            provider = currentProvider;
            try {
                CreativeModeTabs.tryRebuildTabContents(minecraft.level.enabledFeatures(), false, currentProvider);
                EnumMap<Category, LinkedHashMap<ItemRef, Entry>> collected = new EnumMap<>(Category.class);
                for (Category category : Category.values()) {
                    collected.put(category, new LinkedHashMap<>());
                }
                for (ItemStack source : CreativeModeTabs.searchTab().getDisplayItems()) {
                    if (source.isEmpty()) {
                        continue;
                    }
                    try {
                        ItemStack normalized = ItemRef.normalize(source);
                        ItemRef ref = ItemRef.of(normalized, currentProvider);
                        Category category = category(normalized);
                        Entry entry = new Entry(
                                category,
                                ref,
                                normalized.getHoverName().getString(),
                                BuiltInRegistries.ITEM.getKey(normalized.getItem()).toString()
                        );
                        collected.get(category).putIfAbsent(ref, entry);
                    } catch (RuntimeException ignored) {
                        // One malformed creative variant must not hide the rest of the catalog.
                    }
                }
                for (Category category : Category.values()) {
                    entries.put(category, List.copyOf(collected.get(category).values()));
                }
                ready = true;
                return true;
            } catch (RuntimeException exception) {
                clear();
                return false;
            }
        }

        public void clear() {
            provider = null;
            ready = false;
            for (Category category : Category.values()) {
                entries.put(category, List.of());
            }
        }

        public boolean ready() {
            return ready;
        }

        public List<Entry> entries(Category category) {
            return entries.get(Objects.requireNonNull(category, "category"));
        }

        public List<Entry> filtered(Category category, String search) {
            List<Entry> categoryEntries = entries(category);
            if (search == null || search.isBlank()) {
                return categoryEntries;
            }
            String query = search.toLowerCase(Locale.ROOT);
            return categoryEntries.stream()
                    .filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(query)
                            || entry.id().toLowerCase(Locale.ROOT).contains(query))
                    .toList();
        }

        public Category category(ItemStack stack) {
            if (stack.isEmpty()) {
                return Category.OTHER;
            }
            if (stack.has(DataComponents.POTION_CONTENTS)) {
                return Category.POTION;
            }
            if (stack.is(ItemTags.BOW_ENCHANTABLE)
                    || stack.is(ItemTags.CROSSBOW_ENCHANTABLE)
                    || stack.is(ItemTags.TRIDENT_ENCHANTABLE)
                    || stack.is(ItemTags.ARROWS)) {
                return Category.BOW;
            }
            if (stack.has(DataComponents.EQUIPPABLE)) {
                return Category.ARMOR;
            }
            if (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.SPEARS) || stack.is(ItemTags.MACE_ENCHANTABLE)) {
                return Category.SWORD;
            }
            if (stack.has(DataComponents.TOOL)
                    || stack.is(ItemTags.AXES)
                    || stack.is(ItemTags.HOES)
                    || stack.is(ItemTags.PICKAXES)
                    || stack.is(ItemTags.SHOVELS)) {
                return Category.TOOLS;
            }
            if (stack.has(DataComponents.FOOD) || stack.has(DataComponents.CONSUMABLE)) {
                return Category.FOOD;
            }
            if (stack.getItem() instanceof ProjectileItem
                    || stack.is(Items.ENDER_PEARL)
                    || stack.is(Items.ENDER_EYE)) {
                return Category.THROWABLES;
            }
            if (stack.getItem() instanceof BlockItem) {
                return Category.BLOCKS;
            }
            return Category.OTHER;
        }

        public ItemRef ref(ItemStack stack) {
            if (provider == null) {
                throw new IllegalStateException("Join a world to load items");
            }
            return ItemRef.of(stack, provider);
        }

        public ItemStack icon(Category category) {
            return Objects.requireNonNull(category, "category").icon();
        }
    }

    public static final class Layout extends InventoryPickerSetting<Layout.Value> {
        public static final int SLOT_COUNT = 9;

        private final List<List<Preference>> slots = new ArrayList<>(SLOT_COUNT);
        private final LinkedHashSet<ItemRef> exclusions = new LinkedHashSet<>();

        public Layout(String name) {
            super(name);
            for (int i = 0; i < SLOT_COUNT; i++) {
                slots.add(new ArrayList<>());
            }
        }

        @Override
        public Value get() {
            return new Value(slots, exclusions);
        }

        @Override
        public void set(Value value) {
            Objects.requireNonNull(value, "value");
            if (value.slots().size() != SLOT_COUNT) {
                throw new IllegalArgumentException("Layout must contain exactly nine slots");
            }
            clear();
            for (int i = 0; i < SLOT_COUNT; i++) {
                for (Preference preference : value.slots().get(i)) {
                    assign(i, preference);
                }
            }
            exclusions.addAll(value.exclusions());
        }

        public List<Preference> slot(int index) {
            return List.copyOf(slotMutable(index));
        }

        public List<List<Preference>> slots() {
            return get().slots();
        }

        public void assign(int index, Preference preference) {
            List<Preference> target = slotMutable(index);
            Objects.requireNonNull(preference, "preference");
            target.remove(preference);
            target.add(preference);
        }

        public Preference remove(int slot, int preference) {
            return slotMutable(slot).remove(preference);
        }

        public boolean remove(int slot, Preference preference) {
            return slotMutable(slot).remove(Objects.requireNonNull(preference, "preference"));
        }

        public Set<ItemRef> exclusions() {
            return Collections.unmodifiableSet(exclusions);
        }

        public boolean toggle(ItemRef item) {
            Objects.requireNonNull(item, "item");
            if (exclusions.remove(item)) {
                return false;
            }
            exclusions.add(item);
            return true;
        }

        public boolean isExcluded(ItemRef item) {
            return exclusions.contains(item);
        }

        public boolean isExcluded(ItemStack stack) {
            return exclusions.stream().anyMatch(item -> item.matches(stack));
        }

        public int maxDepth() {
            return slots.stream().mapToInt(List::size).max().orElse(0);
        }

        @Override
        public JsonElement toJson() {
            JsonObject root = new JsonObject();
            JsonArray serializedSlots = new JsonArray();
            for (List<Preference> slot : slots) {
                JsonArray serializedSlot = new JsonArray();
                for (Preference preference : slot) {
                    serializedSlot.add(preferenceJson(preference));
                }
                serializedSlots.add(serializedSlot);
            }
            root.add("slots", serializedSlots);

            JsonArray serializedExclusions = new JsonArray();
            for (ItemRef exclusion : exclusions) {
                serializedExclusions.add(exclusion.raw());
            }
            root.add("exclusions", serializedExclusions);
            return root;
        }

        @Override
        public void fromJson(JsonElement json) {
            clear();
            if (json == null || !json.isJsonObject()) {
                return;
            }
            JsonObject root = json.getAsJsonObject();
            JsonElement serializedSlots = root.get("slots");
            if (serializedSlots != null && serializedSlots.isJsonArray()) {
                JsonArray array = serializedSlots.getAsJsonArray();
                for (int slotIndex = 0; slotIndex < Math.min(SLOT_COUNT, array.size()); slotIndex++) {
                    JsonElement serializedSlot = array.get(slotIndex);
                    if (!serializedSlot.isJsonArray()) {
                        continue;
                    }
                    for (JsonElement serializedPreference : serializedSlot.getAsJsonArray()) {
                        Preference preference = parsePreference(serializedPreference);
                        if (preference != null) {
                            assign(slotIndex, preference);
                        }
                    }
                }
            }
            JsonElement serializedExclusions = root.get("exclusions");
            if (serializedExclusions != null && serializedExclusions.isJsonArray()) {
                for (JsonElement serializedItem : serializedExclusions.getAsJsonArray()) {
                    ItemRef item = parseItem(serializedItem);
                    if (item != null) {
                        exclusions.add(item);
                    }
                }
            }
        }

        @Override
        public void resolve(HolderLookup.Provider provider) {
            Objects.requireNonNull(provider, "provider");
            for (List<Preference> slot : slots) {
                slot.removeIf(preference -> preference instanceof ItemPreference item && !item.item().resolve(provider));
            }
            exclusions.removeIf(item -> !item.resolve(provider));
        }

        @Override
        public void clearResolved() {
            for (List<Preference> slot : slots) {
                for (Preference preference : slot) {
                    if (preference instanceof ItemPreference item) {
                        item.item().clearResolved();
                    }
                }
            }
            exclusions.forEach(ItemRef::clearResolved);
        }

        private List<Preference> slotMutable(int index) {
            if (index < 0 || index >= SLOT_COUNT) {
                throw new IndexOutOfBoundsException(index);
            }
            return slots.get(index);
        }

        private void clear() {
            slots.forEach(List::clear);
            exclusions.clear();
        }

        public record Value(List<List<Preference>> slots, Set<ItemRef> exclusions) {
            public Value {
                Objects.requireNonNull(slots, "slots");
                Objects.requireNonNull(exclusions, "exclusions");
                List<List<Preference>> copy = new ArrayList<>(slots.size());
                for (List<Preference> slot : slots) {
                    copy.add(List.copyOf(slot));
                }
                slots = List.copyOf(copy);
                exclusions = Set.copyOf(exclusions);
            }
        }
    }

    public static final class Cleaner extends InventoryPickerSetting<Cleaner.Value> {
        private final EnumMap<Category, CleanerMode> categoryModes = new EnumMap<>(Category.class);
        private final LinkedHashMap<ItemRef, CleanerMode> itemModes = new LinkedHashMap<>();

        public Cleaner(String name) {
            super(name);
        }

        @Override
        public Value get() {
            return new Value(categoryModes, itemModes);
        }

        @Override
        public void set(Value value) {
            Objects.requireNonNull(value, "value");
            categoryModes.clear();
            itemModes.clear();
            value.categoryModes().forEach(this::setCategoryMode);
            value.itemModes().forEach(this::setItemMode);
        }

        public CleanerMode categoryMode(Category category) {
            return categoryModes.getOrDefault(Objects.requireNonNull(category, "category"), CleanerMode.UNSET);
        }

        public Map<Category, CleanerMode> categoryModes() {
            return Collections.unmodifiableMap(categoryModes);
        }

        public void setCategoryMode(Category category, CleanerMode mode) {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(mode, "mode");
            if (mode == CleanerMode.UNSET) {
                categoryModes.remove(category);
            } else {
                categoryModes.put(category, mode);
            }
        }

        public CleanerMode itemMode(ItemRef item) {
            return itemModes.getOrDefault(Objects.requireNonNull(item, "item"), CleanerMode.UNSET);
        }

        public Map<ItemRef, CleanerMode> itemModes() {
            return Collections.unmodifiableMap(itemModes);
        }

        public void setItemMode(ItemRef item, CleanerMode mode) {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(mode, "mode");
            if (mode == CleanerMode.UNSET) {
                itemModes.remove(item);
            } else {
                itemModes.put(item, mode);
            }
        }

        public CleanerMode effectiveMode(ItemRef item) {
            CleanerMode exact = itemMode(item);
            if (exact != CleanerMode.UNSET) {
                return exact;
            }
            return item.resolved() ? categoryMode(catalog().category(item.stack())) : CleanerMode.UNSET;
        }

        public CleanerMode effectiveMode(ItemStack stack) {
            for (Map.Entry<ItemRef, CleanerMode> entry : itemModes.entrySet()) {
                if (entry.getKey().matches(stack)) {
                    return entry.getValue();
                }
            }
            return categoryMode(catalog().category(stack));
        }

        public CleanerMode cycle(Category category, int direction) {
            CleanerMode next = categoryMode(category).cycle(direction);
            setCategoryMode(category, next);
            return next;
        }

        public CleanerMode cycle(ItemRef item, int direction) {
            CleanerMode next = itemMode(item).cycle(direction);
            setItemMode(item, next);
            return next;
        }

        @Override
        public JsonElement toJson() {
            JsonObject root = new JsonObject();
            JsonObject serializedCategories = new JsonObject();
            categoryModes.forEach((category, mode) -> serializedCategories.addProperty(category.name(), mode.name()));
            root.add("categories", serializedCategories);

            JsonArray serializedItems = new JsonArray();
            itemModes.forEach((item, mode) -> {
                JsonObject serialized = new JsonObject();
                serialized.add("item", item.raw());
                serialized.addProperty("mode", mode.name());
                serializedItems.add(serialized);
            });
            root.add("items", serializedItems);
            return root;
        }

        @Override
        public void fromJson(JsonElement json) {
            categoryModes.clear();
            itemModes.clear();
            if (json == null || !json.isJsonObject()) {
                return;
            }
            JsonObject root = json.getAsJsonObject();
            JsonElement serializedCategories = root.get("categories");
            if (serializedCategories != null && serializedCategories.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : serializedCategories.getAsJsonObject().entrySet()) {
                    Category category = parseEnum(Category.class, entry.getKey());
                    CleanerMode mode = parseMode(entry.getValue());
                    if (category != null && mode != null) {
                        setCategoryMode(category, mode);
                    }
                }
            }
            JsonElement serializedItems = root.get("items");
            if (serializedItems != null && serializedItems.isJsonArray()) {
                for (JsonElement element : serializedItems.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    ItemRef item = parseItem(object.get("item"));
                    CleanerMode mode = parseMode(object.get("mode"));
                    if (item != null && mode != null) {
                        setItemMode(item, mode);
                    }
                }
            }
        }

        @Override
        public void resolve(HolderLookup.Provider provider) {
            Objects.requireNonNull(provider, "provider");
            itemModes.entrySet().removeIf(entry -> !entry.getKey().resolve(provider));
        }

        @Override
        public void clearResolved() {
            itemModes.keySet().forEach(ItemRef::clearResolved);
        }

        public record Value(Map<Category, CleanerMode> categoryModes, Map<ItemRef, CleanerMode> itemModes) {
            public Value {
                categoryModes = Map.copyOf(Objects.requireNonNull(categoryModes, "categoryModes"));
                itemModes = Map.copyOf(Objects.requireNonNull(itemModes, "itemModes"));
            }
        }
    }

    private static JsonElement preferenceJson(Preference preference) {
        JsonObject result = new JsonObject();
        if (preference instanceof CategoryPreference category) {
            result.addProperty("category", category.category().name());
        } else if (preference instanceof ItemPreference item) {
            result.add("item", item.item().raw());
        }
        return result;
    }

    private static Preference parsePreference(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return null;
        }
        JsonObject object = json.getAsJsonObject();
        JsonElement serializedCategory = object.get("category");
        if (serializedCategory != null && serializedCategory.isJsonPrimitive()
                && serializedCategory.getAsJsonPrimitive().isString()) {
            Category category = parseEnum(Category.class, serializedCategory.getAsString());
            return category == null ? null : new CategoryPreference(category);
        }
        ItemRef item = parseItem(object.get("item"));
        return item == null ? null : new ItemPreference(item);
    }

    private static ItemRef parseItem(JsonElement json) {
        return json != null && json.isJsonObject() ? new ItemRef(json) : null;
    }

    private static CleanerMode parseMode(JsonElement json) {
        if (json == null || !json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString()) {
            return null;
        }
        return parseEnum(CleanerMode.class, json.getAsString());
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
