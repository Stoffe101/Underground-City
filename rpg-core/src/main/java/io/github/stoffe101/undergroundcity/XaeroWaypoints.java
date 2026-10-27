package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The tracked destination as a waypoint in Xaero's Minimap (D-161), client side and optional.
 *
 * <p>Xaero's Minimap 26.6.0 keeps waypoints that other mods supply apart from the player's own: each world container has a third-party manager, a set of waypoints per origin, which its own
 * Waystones support fills, its minimap draws and Xaero's World Map 1.47.0 lists. This adds one waypoint, under the origin {@link #ORIGIN}, for the one destination the server says this player is
 * tracking, and takes it away again; it never touches the player's own waypoint sets or files, and nothing it adds is saved by Xaero (the server sends the destination again on every join).
 *
 * <p>Xaero publishes no API for this, so the calls are made by reflection, looked up once and only when the mod is installed in the 26.x line it was read from. Any failure switches the bridge
 * off for the session with one line in the log; the status display's own arrow keeps working either way. Nothing here loads a Xaero class unless the mod is present.
 */
@OnlyIn(Dist.CLIENT)
final class XaeroWaypoints {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String MOD_ID = "xaerominimap", TESTED = "26.6.0";
    static final ResourceLocation ORIGIN = ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "tracked");
    static final String ID = "tracked";

    enum State { UNCHECKED, ABSENT, READY, FAILED }
    private static State state = State.UNCHECKED;
    private static String version = "";

    private static Object minimapModule;
    private static Method currentSession, worldManager, currentWorld, container, thirdParty, originSet, setAdd, setRemove, setGet, thirdPartyDeleted;
    private static Constructor<?> newWaypoint;
    private static Class<?> colourType;
    private static Object normalPurpose;

    private static volatile Navigation.Destination desired;
    private static Navigation.Destination applied;
    private static Object appliedSet, appliedWaypoint;
    private static boolean dismissed;
    /** Evidence for the scenarios: waypoints added and removed this session, and how often the bridge looked. */
    static int added, removed, looks;

    private XaeroWaypoints() {}

    static void want(Navigation.Destination destination) { desired = destination; }

    /** True while the tracked destination is on Xaero's minimap (and the player has not deleted it there). */
    static boolean showing() { return state == State.READY && appliedWaypoint != null && !dismissed; }

    static State state() { return state; }

    static String describe() { return state + (version.isEmpty() ? "" : " " + version) + "; looked " + looks + ", added " + added + ", removed " + removed + (appliedWaypoint != null ? ", showing" : ""); }

    /** Twice a second: makes Xaero's set match what the server says is tracked. */
    static void tick(Minecraft mc) {
        if (mc.level == null || ++looks % 10 != 0) return;
        if (!ready()) return;
        try { reconcile(mc); }
        catch (Throwable error) { fail("updating the waypoint", error); }
    }

    private static void reconcile(Minecraft mc) throws ReflectiveOperationException {
        Object set = currentSet();
        if (set == null) return;   // the minimap has no world yet (joining, or between dimensions)
        if (appliedSet != null && appliedSet != set) {
            // the player changed dimension or world: take ours out of the set it was put in
            setRemove.invoke(appliedSet, ID);
            removed++;
            appliedSet = null; appliedWaypoint = null; applied = null;
        }
        Navigation.Destination d = desired;
        String here = mc.level.dimension().location().toString();
        Navigation.Destination want = d != null && d.dimension().equals(here) ? d : null;
        if (want == null) {
            if (appliedWaypoint != null) { setRemove.invoke(set, ID); removed++; }
            applied = null; appliedSet = null; appliedWaypoint = null; dismissed = false;
            return;
        }
        if (want.equals(applied) && appliedSet == set) {
            Object present = setGet.invoke(set, ID);
            if (present == appliedWaypoint) { dismissed = (Boolean) thirdPartyDeleted.invoke(present); return; }
            // the set was emptied under us (Xaero reloaded its world): put it back below
        }
        Object waypoint = newWaypoint.newInstance(want.pos().getX(), want.pos().getY(), want.pos().getZ(), name(want), initials(want), colour(want), normalPurpose);
        setAdd.invoke(set, ID, waypoint);   // replaces a waypoint of the same id, so a destination is never there twice
        added++;
        applied = want; appliedSet = set; appliedWaypoint = waypoint; dismissed = false;
    }

    private static Object currentSet() throws ReflectiveOperationException {
        Object session = currentSession.invoke(minimapModule);
        if (session == null) return null;
        Object manager = worldManager.invoke(session);
        Object world = manager == null ? null : currentWorld.invoke(manager);
        Object box = world == null ? null : container.invoke(world);
        Object parties = box == null ? null : thirdParty.invoke(box);
        return parties == null ? null : originSet.invoke(parties, ORIGIN);
    }

    static String name(Navigation.Destination d) {
        String title = d.title();
        return title.length() > 40 ? title.substring(0, 39) + "…" : title;
    }

    static String initials(Navigation.Destination d) {
        return switch (d.kind()) { case "tech" -> "T"; case "place" -> "P"; case "supply" -> "D"; case "home" -> "H"; default -> "U"; };
    }

    /** Evidence for the scenarios (read only): how many waypoints Underground City has in Xaero's current world, and how many of the player's own there are; -1 when Xaero cannot be read. */
    static int ours() {
        if (!ready()) return -1;
        try {
            Object set = currentSet();
            return set == null ? -1 : (Integer) set.getClass().getMethod("getCount").invoke(set);
        } catch (Throwable error) { return -1; }
    }

    static int playersOwn() {
        if (!ready()) return -1;
        try {
            Object session = currentSession.invoke(minimapModule);
            Object world = session == null ? null : currentWorld.invoke(worldManager.invoke(session));
            if (world == null) return -1;
            int n = 0;
            for (Object set : (Iterable<?>) world.getClass().getMethod("getIterableWaypointSets").invoke(world))
                for (Object ignored : (Iterable<?>) set.getClass().getMethod("getWaypoints").invoke(set)) n++;
            return n;
        } catch (Throwable error) { return -1; }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object colour(Navigation.Destination d) {
        String name = switch (d.kind()) { case "tech" -> "AQUA"; case "place" -> "GOLD"; case "supply" -> "GREEN"; case "home" -> "LIGHT_BLUE"; default -> "WHITE"; };
        return Enum.valueOf((Class) colourType, name);
    }

    // ------------------------------------------------------------------------------------------------ finding Xaero

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean ready() {
        if (state == State.READY) return true;
        if (state != State.UNCHECKED) return false;
        var mod = ModList.get().getModContainerById(MOD_ID);
        if (mod.isEmpty()) { state = State.ABSENT; LOGGER.info("[UC] Xaero's Minimap is not installed; tracked destinations are shown on the status display"); return false; }
        version = mod.get().getModInfo().getVersion().toString();
        if (!version.startsWith("26.")) {
            state = State.FAILED;
            LOGGER.info("[UC] Xaero's Minimap {} is installed; tracked destinations are only put on its map for the 26.x line ({} was read). The status display's arrow still points the way.", version, TESTED);
            return false;
        }
        try {
            Class<?> modules = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Field minimap = modules.getField("MINIMAP");
            minimapModule = minimap.get(null);
            currentSession = minimapModule.getClass().getMethod("getCurrentSession");
            Class<?> session = Class.forName("xaero.hud.minimap.module.MinimapSession");
            worldManager = session.getMethod("getWorldManager");
            currentWorld = Class.forName("xaero.hud.minimap.world.MinimapWorldManager").getMethod("getCurrentWorld");
            container = Class.forName("xaero.hud.minimap.world.MinimapWorld").getMethod("getContainer");
            thirdParty = Class.forName("xaero.hud.minimap.world.container.MinimapWorldContainer").getMethod("getThirdPartyWaypointManager");
            Class<?> manager = Class.forName("xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypointManager");
            originSet = manager.getMethod("get", ResourceLocation.class);
            Class<?> set = Class.forName("xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints");
            Class<?> waypoint = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            setAdd = set.getMethod("add", String.class, waypoint);
            setRemove = set.getMethod("remove", String.class);
            setGet = set.getMethod("get", String.class);
            thirdPartyDeleted = waypoint.getMethod("isThirdPartyDeleted");
            colourType = Class.forName("xaero.hud.minimap.waypoint.WaypointColor");
            Class<?> purpose = Class.forName("xaero.hud.minimap.waypoint.WaypointPurpose");
            normalPurpose = Enum.valueOf((Class) purpose, "NORMAL");
            newWaypoint = waypoint.getConstructor(int.class, int.class, int.class, String.class, String.class, colourType, purpose);
            state = State.READY;
            LOGGER.info("[UC] Xaero's Minimap {} found: tracked destinations appear as a waypoint on its map{}", version, version.equals(TESTED) ? "" : " (read from " + TESTED + ")");
            return true;
        } catch (Throwable error) {
            fail("finding its waypoint manager", error);
            return false;
        }
    }

    private static void fail(String doing, Throwable error) {
        state = State.FAILED;
        appliedSet = null; appliedWaypoint = null; applied = null;
        LOGGER.warn("[UC] Xaero's Minimap {}: {} failed ({}); tracked destinations stay on the status display only for this session", version, doing, error.toString());
    }
}
