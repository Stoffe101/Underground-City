package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Development convenience for {@code gradlew runClient} (set by build.gradle, never by normal play).
 *
 * <ul>
 *   <li>{@code -Duc.devWorld=<name>}: when the title screen first appears, open the save of that name or create it once
 *       (survival, cheats allowed so {@code /uc_dev} works, seed 5908, default world type). The three-city configuration
 *       comes from Lost Cities' own config and the original world package that {@code prepareDevRun} installed.</li>
 *   <li>{@code -Duc.launchSmoke=true}: additionally prove the launch worked. Once the player is in the world it prints
 *       {@code [UC-LAUNCH]} facts (mods loaded with versions, registry sizes, the Lost Cities chunk at Ironhaven's centre),
 *       places an Operations Terminal with {@code /uc_dev terminal}, uses it like a player would, waits for the terminal
 *       screen, takes a screenshot and quits. It proves the client starts, the pack loads, the world generates and one
 *       Underground City interface opens; it does not prove gameplay.</li>
 * </ul>
 * Nothing here runs unless one of those properties is present.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
public final class DevWorldLauncher {
    private static final String WORLD = System.getProperty("uc.devWorld");
    private static final boolean SMOKE = Boolean.getBoolean("uc.launchSmoke");
    private static final long SEED = 5908L;
    private static final long LIMIT_NANOS = 900_000_000_000L;
    private static final List<String> MODS = List.of("minecraft", "forge", "underground_city", "qshop", "lostcities", "tacz", "taczblueprints",
        "modernlife", "mts", "mtsofficialpack", "oamp");

    private enum Stage { TITLE, WORLD, SETTLE, TERMINAL_COMMAND, TERMINAL_USE, SCREEN, SHOT, DONE }

    private static Stage stage = Stage.TITLE;
    private static boolean opened;
    private static int ticks;
    private static long started = System.nanoTime();
    private static BlockPos terminal;
    private static final AtomicReference<String> serverFacts = new AtomicReference<>();
    private static final StringBuilder failures = new StringBuilder();

    private DevWorldLauncher() {}

    private static String worldName() { return WORLD != null ? WORLD : "UC-Dev-5908"; }

    /**
     * The dimensions of a world this development code creates: the vanilla Normal preset, or the preset named by {@code -Duc.devPreset} (the archipelago, D-104). The preset
     * is looked up by id in the registries of the world being created, so a launch whose data does not carry it fails with the preset's name instead of a vanilla world.
     */
    static net.minecraft.world.level.levelgen.WorldDimensions dimensions(net.minecraft.core.RegistryAccess registries) {
        String preset = System.getProperty("uc.devPreset");
        var key = preset == null || preset.isBlank() ? WorldPresets.NORMAL
            : net.minecraft.resources.ResourceKey.create(Registries.WORLD_PRESET, new net.minecraft.resources.ResourceLocation(preset));
        return registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(key).value().createWorldDimensions();
    }

    @SubscribeEvent
    public static void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || (WORLD == null && !SMOKE) || opened) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) return;
        opened = true;
        String name = worldName();
        try {
            if (mc.getLevelSource().levelExists(name)) {
                System.out.println("[UC-LAUNCH] opening the existing development world " + name);
                mc.createWorldOpenFlows().loadLevel(mc.screen, name);
            } else {
                System.out.println("[UC-LAUNCH] creating the development world " + name + " with seed " + SEED);
                LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(SEED, true, false), DevWorldLauncher::dimensions);
            }
            stage = Stage.WORLD;
        } catch (RuntimeException error) {
            fail(mc, "could not open the development world: " + error);
        }
    }

    /**
     * Reopening an existing development world makes Minecraft ask "Worlds using Experimental Settings are not supported" (a generator
     * registered by a mod is not vanilla-stable). It is our own known world, so the launcher answers Proceed instead of leaving a
     * scripted run, or a developer's second launch, waiting at a dialog. Only that one dialog is answered.
     */
    static void acceptExperimentalWarning(Minecraft mc) {
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen confirm)) return;
        // Two vanilla dialogs mention experimental settings: "Warning! These settings are using experimental features" when a world is created, and
        // "Worlds using Experimental Settings are not supported" when an existing one is reopened (the one a second launch meets; the first version
        // of this method only knew the first and left a second launch waiting).
        String title = confirm.getTitle().getString();
        if (!title.equals(net.minecraft.client.resources.language.I18n.get("selectWorld.warning.experimental.title"))
            && !title.equals(net.minecraft.client.resources.language.I18n.get("selectWorld.backupQuestion.experimental"))) return;
        for (var child : confirm.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button button
                && button.getMessage().getString().equals(net.minecraft.client.resources.language.I18n.get("gui.proceed"))) {
                System.out.println("[UC-LAUNCH] accepting the experimental-settings notice for the development world");
                button.onPress();
                return;
            }
        }
    }

    @SubscribeEvent
    public static void warning(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && opened && (WORLD != null || SMOKE)) acceptExperimentalWarning(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!SMOKE || event.phase != TickEvent.Phase.END || stage == Stage.DONE || stage == Stage.TITLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (System.nanoTime() - started > LIMIT_NANOS) { fail(mc, "timed out in stage " + stage); return; }
        switch (stage) {
            case WORLD -> {
                if (mc.player != null && mc.level != null && mc.getSingleplayerServer() != null && mc.screen == null) { ticks = 0; stage = Stage.SETTLE; }
            }
            case SETTLE -> {
                // Let the first chunks arrive and the integrated server finish starting before reporting.
                if (++ticks == 100) { report(mc); }
                if (ticks > 100 && serverFacts.get() != null) { ticks = 0; stage = Stage.TERMINAL_COMMAND; }
            }
            case TERMINAL_COMMAND -> {
                mc.player.connection.sendCommand("uc_dev terminal");
                ticks = 0;
                stage = Stage.TERMINAL_USE;
            }
            case TERMINAL_USE -> {
                if (terminal == null) terminal = findTerminal(mc);
                if (terminal != null) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(terminal), Direction.UP, terminal, false));
                    ticks = 0;
                    stage = Stage.SCREEN;
                } else if (++ticks > 200) fail(mc, "the Operations Terminal never appeared after /uc_dev terminal");
            }
            case SCREEN -> {
                if (mc.screen instanceof TerminalScreen) { ticks = 0; stage = Stage.SHOT; }
                else if (++ticks > 200) fail(mc, "using the Operations Terminal did not open its screen");
            }
            case SHOT -> {
                if (++ticks == 30) {
                    Screenshot.grab(mc.gameDirectory, "uc-launch-smoke-terminal.png", mc.getMainRenderTarget(),
                        message -> System.out.println("[UC-LAUNCH] screenshot saved: uc-launch-smoke-terminal.png"));
                }
                if (ticks > 60) finish(mc);
            }
            default -> { }
        }
    }

    private static BlockPos findTerminal(Minecraft mc) {
        BlockPos base = mc.player.blockPosition();
        for (BlockPos at : BlockPos.betweenClosed(base.offset(-4, -2, -4), base.offset(4, 2, 4))) {
            if (mc.level.getBlockState(at).is(UndergroundCity.TERMINAL.get())) return at.immutable();
        }
        return null;
    }

    private static void report(Minecraft mc) {
        for (String id : MODS) {
            var container = ModList.get().getModContainerById(id);
            System.out.println("[UC-LAUNCH] mod " + id + "=" + container.map(c -> c.getModInfo().getVersion().toString()).orElse("MISSING"));
            if (container.isEmpty() && !id.equals("minecraft") && !id.equals("forge")) failures.append("mod ").append(id).append(" is not loaded; ");
        }
        for (String ns : List.of("tacz", "taczblueprints", "modernlife", "mts")) {
            long items = ForgeRegistries.ITEMS.getKeys().stream().filter(key -> key.getNamespace().equals(ns)).count();
            System.out.println("[UC-LAUNCH] registry " + ns + " items=" + items);
            if (items == 0) failures.append("no ").append(ns).append(" items registered; ");
        }
        for (String prefix : List.of("mtsofficialpack.", "oamp.")) {
            long items = ForgeRegistries.ITEMS.getKeys().stream().filter(key -> key.getNamespace().equals("mts") && key.getPath().startsWith(prefix)).count();
            System.out.println("[UC-LAUNCH] mts pack " + prefix + " items=" + items);
            if (items == 0) failures.append("content pack ").append(prefix).append(" registered nothing; ");
        }
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            StringBuilder facts = new StringBuilder();
            facts.append("seed=").append(level.getSeed());
            facts.append(" generator=").append(level.getChunkSource().getGenerator().getClass().getSimpleName());
            facts.append(" datapacks=").append(server.getPackRepository().getSelectedIds());
            if (LostCitiesLink.ready()) {
                var centre = LostCitiesLink.chunk(level, 0, 0);
                facts.append(" lostcities_chunk_0_0=").append(centre.map(c -> "city=" + c.city() + ",building=" + c.building()).orElse("unavailable"));
                if (centre.isEmpty() || !centre.get().city()) failures.append("Lost Cities does not plan a city at Ironhaven's centre chunk; ");
            } else {
                facts.append(" lostcities_api=not-ready");
                failures.append("the Lost Cities API link never became ready; ");
            }
            facts.append(" core_work_orders=").append(WorkOrderCatalog.INSTANCE.all().size());
            facts.append(" regions=").append(CityRegions.INSTANCE.all().size());
            if (level.getSeed() != SEED) failures.append("the world seed is ").append(level.getSeed()).append(" not ").append(SEED).append("; ");
            serverFacts.set(facts.toString());
            System.out.println("[UC-LAUNCH] server " + facts);
        });
    }

    private static void finish(Minecraft mc) {
        stage = Stage.DONE;
        boolean pass = failures.length() == 0;
        System.out.println("[UC-LAUNCH] RESULT " + (pass ? "pass" : "FAIL: " + failures));
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("uc-launch-smoke.txt"), (pass ? "pass" : "FAIL: " + failures) + "\n");
        } catch (java.io.IOException ignored) { }
        mc.stop();
    }

    private static void fail(Minecraft mc, String reason) {
        failures.append(reason).append("; ");
        finish(mc);
    }
}
