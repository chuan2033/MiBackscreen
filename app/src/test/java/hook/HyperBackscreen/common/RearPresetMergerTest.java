package hook.HyperBackscreen.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RearPresetMergerTest {

    private static final class Item {
        final String id;
        final boolean usable;

        Item(String id, boolean usable) {
            this.id = id;
            this.usable = usable;
        }
    }

    private static final class Category {
        final String type;
        final List<Item> items;

        Category(String type, Item... items) {
            this.type = type;
            this.items = new ArrayList<>(Arrays.asList(items));
        }
    }

    private static final RearPresetMerger.Access<Category, Item> ACCESS =
            new RearPresetMerger.Access<Category, Item>() {
                @Override public String categoryType(Category category) {
                    return category.type;
                }

                @Override public List<Item> items(Category category) {
                    return category.items;
                }

                @Override public String itemKey(Item item) {
                    return item.id;
                }

                @Override public boolean usable(Item item) {
                    return item.usable;
                }
            };

    private static Item ok(String id) {
        return new Item(id, true);
    }

    private static Item missing(String id) {
        return new Item(id, false);
    }

    private static List<String> ids(List<Item> items) {
        List<String> out = new ArrayList<>();
        for (Item item : items) out.add(item.id);
        return out;
    }

    private static List<String> types(List<Category> categories) {
        List<String> out = new ArrayList<>();
        for (Category category : categories) out.add(category.type);
        return out;
    }

    @Test public void insertsNewItemsAtSupplementPositions() {
        Category hostPet = new Category("cutePet", ok("gai"), ok("tuan"), ok("hei"));
        Category supplementPet = new Category("cutePet",
                ok("pao"), ok("pipi"), ok("gai"), ok("tuan"), ok("hei"));

        RearPresetMerger.Result<Category, Item> result = RearPresetMerger.merge(
                List.of(hostPet), List.of(supplementPet), ACCESS);

        assertEquals(2, result.addedItems);
        assertEquals(0, result.addedCategories);
        assertEquals(List.of("pao", "pipi", "gai", "tuan", "hei"), ids(result.updatedItems.get(hostPet)));
        // 宿主对象本身不被改动，由调用方写回
        assertEquals(List.of("gai", "tuan", "hei"), ids(hostPet.items));
    }

    @Test public void appendsAfterLastHostAnchor() {
        Category hostNfc = new Category("nfc", ok("case1"), ok("case2"));
        Category supplementNfc = new Category("nfc",
                ok("case1"), ok("case2"), ok("pet1"), ok("pet2"));

        RearPresetMerger.Result<Category, Item> result = RearPresetMerger.merge(
                List.of(hostNfc), List.of(supplementNfc), ACCESS);

        assertEquals(List.of("case1", "case2", "pet1", "pet2"), ids(result.updatedItems.get(hostNfc)));
    }

    @Test public void itemsWithoutAnchorsKeepTheirOrderAtFront() {
        Category hostAi = new Category("ai", ok("cartoon"), ok("anime"));
        // 旧版补充模板只带新条目，没有宿主已有的条目可作位置参照
        Category supplementAi = new Category("ai", ok("groupPhoto"), ok("phoneCase"));

        RearPresetMerger.Result<Category, Item> result = RearPresetMerger.merge(
                List.of(hostAi), List.of(supplementAi), ACCESS);

        assertEquals(List.of("groupPhoto", "phoneCase", "cartoon", "anime"),
                ids(result.updatedItems.get(hostAi)));
    }

    @Test public void insertsMissingCategoryAfterPreviousSupplementCategory() {
        Category signature = new Category("signature", ok("sig"));
        Category ai = new Category("ai", ok("cartoon"));
        Category supplementMate = new Category("ai-mate", ok("custom"), ok("peeko"));
        Category supplementSignature = new Category("signature", ok("sig"));
        Category supplementAi = new Category("ai", ok("cartoon"));
        Category supplementBonus = new Category("bonus", ok("extra"));

        RearPresetMerger.Result<Category, Item> result = RearPresetMerger.merge(
                List.of(signature, ai),
                List.of(supplementMate, supplementSignature, supplementBonus, supplementAi),
                ACCESS);

        assertEquals(List.of("ai-mate", "signature", "bonus", "ai"), types(result.categories));
        assertEquals(2, result.addedCategories);
        assertEquals(3, result.addedItems);
        assertSame(supplementMate, result.categories.get(0));
        assertEquals(List.of("custom", "peeko"), ids(result.updatedItems.get(supplementMate)));
    }

    @Test public void skipsUnusableItemsAndEmptyCategories() {
        Category hostPet = new Category("cutePet", ok("gai"));
        Category supplementPet = new Category("cutePet", missing("pao"), ok("gai"), ok("pipi"));
        Category supplementNfc = new Category("nfc", missing("pet1"));

        RearPresetMerger.Result<Category, Item> result = RearPresetMerger.merge(
                List.of(hostPet), List.of(supplementPet, supplementNfc), ACCESS);

        assertEquals(1, result.addedItems);
        assertEquals(0, result.addedCategories);
        assertEquals(List.of("cutePet"), types(result.categories));
        assertEquals(List.of("gai", "pipi"), ids(result.updatedItems.get(hostPet)));
    }

    @Test public void unusableAnchorStillPositionsWhenHostHasIt() {
        Category hostWatch = new Category("watch", ok("step"), ok("time"));
        // 补充模板里的已有条目只作参照，可用与否不影响定位
        Category supplementWatch = new Category("watch",
                missing("step"), ok("float"), missing("time"));

        RearPresetMerger.Result<Category, Item> result = RearPresetMerger.merge(
                List.of(hostWatch), List.of(supplementWatch), ACCESS);

        assertEquals(List.of("step", "float", "time"), ids(result.updatedItems.get(hostWatch)));
    }

    @Test public void reportsNoChangeWhenEverythingIsPresent() {
        Category hostPet = new Category("cutePet", ok("gai"), ok("pipi"));
        Category supplementPet = new Category("cutePet", ok("pipi"), ok("gai"));

        RearPresetMerger.Result<Category, Item> result = RearPresetMerger.merge(
                List.of(hostPet), List.of(supplementPet), ACCESS);

        assertFalse(result.changed());
        assertTrue(result.updatedItems.isEmpty());
        assertEquals(List.of("cutePet"), types(result.categories));
    }

    @Test public void ignoresDuplicateAndKeylessSupplementItems() {
        Category hostPet = new Category("cutePet", ok("gai"));
        Category supplementPet = new Category("cutePet",
                ok("gai"), ok("pipi"), ok("pipi"), new Item(null, true));

        RearPresetMerger.Result<Category, Item> result = RearPresetMerger.merge(
                List.of(hostPet), List.of(supplementPet), ACCESS);

        assertEquals(1, result.addedItems);
        assertEquals(List.of("gai", "pipi"), ids(result.updatedItems.get(hostPet)));
    }

    @Test public void repeatedCategoryInSupplementBuildsOnEarlierMerge() {
        Category hostPet = new Category("cutePet", ok("gai"));
        Category first = new Category("cutePet", ok("gai"), ok("pipi"));
        Category second = new Category("cutePet", ok("pipi"), ok("pao"));

        RearPresetMerger.Result<Category, Item> result = RearPresetMerger.merge(
                List.of(hostPet), List.of(first, second), ACCESS);

        assertEquals(2, result.addedItems);
        assertEquals(List.of("gai", "pipi", "pao"), ids(result.updatedItems.get(hostPet)));
    }
}
