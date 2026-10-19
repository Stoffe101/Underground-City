package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The real-client half of the combined journey's survival part (D-092): the Smith buys a backpack at the exchange terminal's screen, wears it from the inventory screen, opens it
 * with the mod's own key (B), moves items into it and an upgrade into its upgrade slot with the container clicks the screen itself sends, and looks at it at three window sizes
 * (720p, 1080p, 1440p), screenshots included. After the abrupt server kill and restart he opens it again and reads what the screen shows. The server decides nothing here: it waits for
 * the effects (the item in the inventory, the pack on his back, the contents in the pack) and checks them itself ({@link SurvivalJourney#guiBackpack}).
 *
 * <p>What is real: the screens (terminal exchange, inventory, the mod's backpack screen) drawn by the real client, the key press path ({@code KeyMapping.click} is what the keyboard
 * handler calls), the container clicks ({@code MultiPlayerGameMode.handleInventoryMouseClick} is what {@code AbstractContainerScreen.slotClicked} calls) and the packets they send.
 * What is not: a mouse pointer, a held shift key or a person's eyes. Loaded only by the journey.
 */
final class SurvivalJourneyActions {
    private static final int PHASE = Integer.getInteger("uc.journey.phase", 0);
    static final boolean ON = CrewJourneyServer.ENABLED && Boolean.getBoolean("uc.journey.survival");
    private static final String PACK_OFFER = "underground_city:survival/backpack_ironhaven";
    private static final int[][] SIZES = {{1280, 720}, {1920, 1080}, {2560, 1440}};

    private static int ticks, stage, stageTicks;
    private static final List<String> LOG = new ArrayList<>();
    private static final JsonObject OBSERVED = new JsonObject();
    private static final JsonArray SHOTS = new JsonArray();
    // screenshot state machine (render thread)
    private static String shotName;
    private static int shotSize = -1, shotWait;
    private static boolean shotsDone = true, shotTaken, inventoryShot, emptyShot, triedQuickMove, pickedUp;

    private SurvivalJourneyActions() {}

    /** The Smith is the one who carries a backpack in this journey; the founder only plays his Crew part. */
    static boolean mine(Minecraft mc) { return ON && mc.player != null && mc.getUser().getName().equals(CrewJourneyServer.SMITH); }

    private static boolean taken;

    /** Has the Crew part of this client's script finished (and, in phase 1, has the server handed over the things to carry), so that the survival part may use the screen? Once true it stays true. */
    static boolean takesOver(Minecraft mc) {
        if (taken) return true;
        if (!mine(mc)) return false;
        if (CrewJourneyActions.finished() && (PHASE == 2 || mc.player.getInventory().countItem(Items.DIAMOND) >= 7)) {
            taken = true;
            say("the Crew steps are done; the backpack steps begin");
        }
        return taken;
    }

    private static Item item(String id) { return ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.parse(id)); }

    private static boolean isPack(ItemStack stack) { return !stack.isEmpty() && ForgeRegistries.ITEMS.getKey(stack.getItem()).getPath().endsWith("backpack"); }

    private static boolean backpackScreenOpen(Minecraft mc) { return mc.screen != null && mc.screen.getClass().getName().endsWith("BackpackScreen"); }

    private static void say(String line) {
        String text = "[UC-JOURNEY] survival client phase " + PHASE + ": " + line;
        if (LOG.isEmpty() || !LOG.get(LOG.size() - 1).equals(text)) { LOG.add(text); System.out.println(text); }
    }

    private static void next(String what) { stage++; stageTicks = 0; say(what); }

    // ------------------------------------------------------------------------------------------------------------ the script

    static void tick(Minecraft mc) {
        if (mc.player == null || ++ticks % 5 != 0) return;
        stageTicks++;
        if (stageTicks > 20 * 60 / 5 * 3) throw new IllegalStateException("survival journey client stuck in stage " + stage + " (screen " + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()) + "), slots: " + describe(mc.player.containerMenu) + ", log: " + LOG);
        if (!shotsDone) return;                       // a screenshot series is running: leave the screen alone
        if (PHASE == 1) phaseOne(mc); else if (PHASE == 2) phaseTwo(mc);
    }

    private static void phaseOne(Minecraft mc) {
        switch (stage) {
            case 0 -> {   // the server opens the exchange terminal for the Smith; wait for it, find the backpack's line, scroll to it with the wheel and press BUY 1
                if (!(mc.screen instanceof TerminalScreen terminal)) return;
                terminal.showPage(TerminalScreen.Page.IMPORTS);
                var offers = terminal.snapshot().offers();
                int index = -1;
                for (int i = 0; i < offers.size(); i++) if (offers.get(i).id().equals(PACK_OFFER)) index = i;
                if (index < 0) return;   // the exchange has not arrived yet
                say("the exchange offers " + offers.size() + " lines; the backpack is line " + (index + 1) + " at " + offers.get(index).price() + " credits, " + offers.get(index).stock() + " on the shelf");
                OBSERVED.addProperty("exchange_lines_seen", offers.size());
                OBSERVED.addProperty("backpack_price_seen", offers.get(index).price());
                OBSERVED.addProperty("backpack_stock_seen", offers.get(index).stock());
                OBSERVED.addProperty("wallet_seen_at_exchange", terminal.snapshot().balance());
                if (!shotTaken) { shotTaken = true; snap("exchange_survival_goods"); return; }
                if (!terminal.clickBuyOffer(PACK_OFFER)) return;   // inactive for now (a refresh in flight): try again on the next tick
                next("pressed BUY 1 on the backpack");
            }
            case 1 -> {   // the purchase goes through the server; the pack arrives in the inventory
                if (hasPackInInventory(mc)) { mc.setScreen(null); next("the backpack is in the inventory"); }
            }
            case 2 -> {   // wear it: the inventory screen; first the quick-move click a player makes on an item that belongs to an armour slot
                if (isPack(mc.player.getItemBySlot(EquipmentSlot.CHEST))) { next("the backpack is already on the back"); stage = 3; return; }
                if (!(mc.screen instanceof InventoryScreen)) { mc.setScreen(new InventoryScreen(mc.player)); return; }
                AbstractContainerMenu menu = mc.player.containerMenu;
                Slot pack = null;
                for (Slot slot : menu.slots) if (slot.container == mc.player.getInventory() && isPack(slot.getItem())) pack = slot;
                if (pack == null) return;
                if (!triedQuickMove) {
                    triedQuickMove = true;
                    click(mc, menu, pack, ClickType.QUICK_MOVE);
                    say("shift-clicked the backpack in the inventory screen");
                    return;
                }
                // the quick-move did not put it on the back (it went to another inventory slot): pick it up and drop it on the chest armour slot, as a player would by dragging
                Slot chest = menu.slots.stream().filter(s -> s.container == mc.player.getInventory() && s.getContainerSlot() == 36 + EquipmentSlot.CHEST.getIndex()).findFirst().orElse(null);
                if (chest == null) throw new IllegalStateException("the inventory screen has no chest armour slot");
                if (mc.player.containerMenu.getCarried().isEmpty()) { pickedUp = true; click(mc, menu, pack, ClickType.PICKUP); say("picked the backpack up with the cursor"); }
                else { click(mc, menu, chest, ClickType.PICKUP); say("dropped the backpack on the chest armour slot"); }
            }
            case 3 -> {
                if (isPack(mc.player.getItemBySlot(EquipmentSlot.CHEST))) {
                    OBSERVED.addProperty("worn_after_click", true);
                    OBSERVED.addProperty("worn_by_quick_move", !pickedUp);
                    if (!inventoryShot) { inventoryShot = true; snap("inventory_with_backpack_worn"); return; }   // photograph the inventory screen before it is closed
                    mc.setScreen(null);
                    next("the backpack is on the back");
                }
            }
            case 4 -> {   // open it with the mod's own key
                if (backpackScreenOpen(mc)) { OBSERVED.addProperty("opened_with_key", true); next("the backpack screen is open"); return; }
                if (stageTicks % 8 == 1) pressOpenKey();
            }
            case 5 -> {   // move the diamonds, the bread and the upgrade in with quick-move clicks
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (!backpackScreenOpen(mc)) { next("the screen closed; opening it again"); stage = 4; return; }
                if (!emptyShot) {   // let the open message and the menu contents arrive, then photograph the empty pack before anything is moved
                    if (stageTicks < 3) return;
                    emptyShot = true;
                    snap("backpack_empty");
                    return;
                }
                boolean clicked = false;
                for (Slot slot : menu.slots) {
                    if (slot.container != mc.player.getInventory() || !slot.hasItem()) continue;
                    ItemStack stack = slot.getItem();
                    if (stack.is(Items.DIAMOND) || stack.is(Items.BREAD) || stack.getItem() == item("sophisticatedbackpacks:pickup_upgrade")) {
                        click(mc, menu, slot, ClickType.QUICK_MOVE);
                        clicked = true;
                    }
                }
                next(clicked ? "shift-clicked the diamonds, the bread and the pickup upgrade into the pack" : "nothing to move");
            }
            case 6 -> {   // the screen shows them
                AbstractContainerMenu menu = mc.player.containerMenu;
                int diamonds = 0, bread = 0, upgrades = 0;
                for (Slot slot : menu.slots) {
                    if (slot.container == mc.player.getInventory()) continue;
                    ItemStack stack = slot.getItem();
                    if (stack.is(Items.DIAMOND)) diamonds += stack.getCount();
                    else if (stack.is(Items.BREAD)) bread += stack.getCount();
                    else if (stack.getItem() == item("sophisticatedbackpacks:pickup_upgrade")) upgrades += stack.getCount();
                }
                // The mod's upgrade slots are not among the menu slots a client can read here (found by the second run: the diamonds and the bread are, the pickup upgrade that left the inventory is not),
                // so the screen is asked only whether the upgrade is gone from the player's slots; the server, which can read the upgrade inventory, checks that it is in the upgrade slot.
                boolean upgradeLeftTheInventory = true;
                for (Slot slot : menu.slots) if (slot.container == mc.player.getInventory() && slot.getItem().getItem() == item("sophisticatedbackpacks:pickup_upgrade")) upgradeLeftTheInventory = false;
                if (diamonds >= 7 && bread >= 12 && upgradeLeftTheInventory) {
                    OBSERVED.addProperty("pickup_upgrade_left_the_inventory", true);
                    OBSERVED.addProperty("screen_shows_diamonds", diamonds);
                    OBSERVED.addProperty("screen_shows_bread", bread);
                    OBSERVED.addProperty("screen_shows_pickup_upgrades", upgrades);
                    OBSERVED.addProperty("storage_slots_on_screen", (int) menu.slots.stream().filter(s -> s.container != mc.player.getInventory()).count());
                    snap("backpack_with_items_and_upgrade");
                    next("the pack screen shows " + diamonds + " diamonds, " + bread + " bread and " + upgrades + " pickup upgrade");
                }
            }
            case 7 -> {   // let the server see the contents, then close with the screen's own route
                if (stageTicks >= 8) { mc.player.closeContainer(); write(mc, "phase1"); next("closed the backpack; the server will now check what is inside"); }
            }
            default -> { }
        }
    }

    private static void phaseTwo(Minecraft mc) {
        switch (stage) {
            case 0 -> {
                if (!isPack(mc.player.getItemBySlot(EquipmentSlot.CHEST))) return;   // the world is still arriving
                OBSERVED.addProperty("worn_after_restart", true);
                next("the backpack is on the back after the restart");
            }
            case 1 -> {
                if (backpackScreenOpen(mc)) { next("the backpack screen is open after the restart"); return; }
                if (mc.screen != null) { mc.setScreen(null); return; }   // the Crew screen the server opened for the Crew part is closed, as a player would, before the key can open the pack
                if (stageTicks % 8 == 1) pressOpenKey();
            }
            case 2 -> {
                AbstractContainerMenu menu = mc.player.containerMenu;
                int diamonds = 0, bread = 0, upgrades = 0;
                for (Slot slot : menu.slots) {
                    if (slot.container == mc.player.getInventory()) continue;
                    ItemStack stack = slot.getItem();
                    if (stack.is(Items.DIAMOND)) diamonds += stack.getCount();
                    else if (stack.is(Items.BREAD)) bread += stack.getCount();
                    else if (stack.getItem() == item("sophisticatedbackpacks:pickup_upgrade")) upgrades += stack.getCount();
                }
                OBSERVED.addProperty("screen_after_restart_shows_diamonds", diamonds);
                OBSERVED.addProperty("screen_after_restart_shows_bread", bread);
                OBSERVED.addProperty("screen_after_restart_shows_pickup_upgrades", upgrades);   // 0 expected: the upgrade slots are not readable from the menu slots; the server checks the upgrade slot
                snap("backpack_after_restart");
                next("after the restart the screen shows " + diamonds + " diamonds, " + bread + " bread and " + upgrades + " pickup upgrade");
            }
            case 3 -> {
                if (stageTicks >= 8) { mc.player.closeContainer(); write(mc, "phase2"); next("closed the backpack"); }
            }
            default -> { }
        }
    }

    /** Every non-empty slot of a menu as index:container:item x count, for the message of a stuck script. */
    private static String describe(AbstractContainerMenu menu) {
        StringBuilder text = new StringBuilder();
        for (Slot slot : menu.slots) if (slot.hasItem()) text.append(slot.index).append(':').append(slot.container.getClass().getSimpleName()).append(':').append(ForgeRegistries.ITEMS.getKey(slot.getItem().getItem())).append(" x").append(slot.getItem().getCount()).append(' ');
        return text.toString();
    }

    private static boolean hasPackInInventory(Minecraft mc) {
        for (ItemStack stack : mc.player.getInventory().items) if (isPack(stack)) return true;
        return false;
    }

    /** The click the screen sends for a click on a slot: the same call {@code AbstractContainerScreen.slotClicked} makes. */
    private static void click(Minecraft mc, AbstractContainerMenu menu, Slot slot, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, type, mc.player);
    }

    /** What the keyboard handler does for a key press of the bound key: the mod's tick handler consumes the click and sends its open message. */
    private static void pressOpenKey() {
        KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_B));
    }

    // ------------------------------------------------------------------------------------------------------------ screenshots at three window sizes

    /** Starts a series: the current screen is grabbed at 720p, 1080p and 1440p in turn, by changing the real window's size and waiting for the client to lay the screen out again. */
    private static void snap(String name) {
        shotName = name;
        shotSize = 0;
        shotWait = 0;
        shotsDone = false;
    }

    /** Called every frame at the end of the render tick. */
    static void render(Minecraft mc) {
        if (!ON || shotsDone || shotName == null) return;
        mc.getToasts().clear();   // advancement toasts ("Diamonds!", recipes) that the handed-over goods trigger would cover the corner of the screen being photographed
        long window = mc.getWindow().getWindow();
        if (shotWait == 0) {
            int[] size = SIZES[shotSize];
            mc.options.guiScale().set(0);                 // "Auto": what a player would normally have
            GLFW.glfwSetWindowSize(window, size[0], size[1]);
            shotWait = 1;
            return;
        }
        if (++shotWait < 12) return;                       // twelve frames for the resize event and the new layout
        int[] size = SIZES[shotSize];
        String file = "journey-" + PHASE + "-" + shotName + "-" + size[1] + "p.png";
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> System.out.println("[UC-JOURNEY] screenshot " + message.getString()));
        JsonObject shot = new JsonObject();
        shot.addProperty("file", "screenshots/" + file);
        shot.addProperty("requested", size[0] + "x" + size[1]);
        shot.addProperty("actual_framebuffer", w + "x" + h);
        shot.addProperty("gui_scale", mc.getWindow().getGuiScale());
        shot.addProperty("screen", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
        SHOTS.add(shot);
        shotWait = 0;
        if (++shotSize >= SIZES.length) {
            shotsDone = true;
            mc.options.guiScale().set(3);                  // back to the journey's usual scale
            GLFW.glfwSetWindowSize(window, 1920, 1080);
            say("screenshots of " + shotName + " taken at 720p, 1080p and 1440p");
        }
    }

    private static void write(Minecraft mc, String when) {
        OBSERVED.add("screenshots", SHOTS);
        OBSERVED.addProperty("player", mc.getUser().getName());
        JsonArray log = new JsonArray();
        LOG.forEach(log::add);
        OBSERVED.add("log", log);
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("journey-survival-client-" + when + ".json"), OBSERVED + "\n");
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
