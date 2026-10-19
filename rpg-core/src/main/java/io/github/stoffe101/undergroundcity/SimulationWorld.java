package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingConversionEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Underground City is a simulation of a functioning society, not a monster-survival pack (owner direction, D-094). Zombies, skeletons, creepers, spiders, endermen, witches,
 * phantoms, Nether and End monsters and every other hostile creature of the base game or of any mod do not appear on their own. The game is NOT put on Peaceful (difficulty,
 * hunger, thirst and environmental hazards stay as they are) and the {@code doMobSpawning} game rule is NOT switched off (livestock, wildlife, fish, ambient creatures and villagers
 * spawn, breed and persist normally).
 *
 * <p><b>How.</b> Three Forge events, one rule: a hostile mob (anything that is an {@link Enemy} or is in the MONSTER category) is refused unless it is permitted. It is refused when
 * its position is checked ({@link MobSpawnEvent.PositionCheck}, natural spawning and spawners), when it is finalised ({@link MobSpawnEvent.FinalizeSpawn}, which carries the spawn reason
 * and covers structures, patrols, raids, reinforcements, jockeys and triggered spawns) and, as the catch-all for everything else, when it joins a level
 * ({@link EntityJoinLevelEvent}, which also removes hostile mobs a chunk saved in an older world as it loads). A hostile mob that would be created by a conversion (lightning on a
 * villager or a pig) is not converted ({@link LivingConversionEvent.Pre}), so the villager or pig simply survives.
 *
 * <p><b>Permitted</b> are: a mob with the scoreboard tag {@value #PERMIT_TAG} (the way scripted guards, armed criminals and future raids are spawned: add the tag before the mob joins
 * the level, see {@link #permit(Entity)}); an entity type listed in the data tag {@code underground_city:permitted_hostiles} (empty by default); and a deliberate spawn by an operator
 * (spawn egg, dispenser, {@code /summon} without NBT), which is tagged so that it persists across restarts. {@code /summon} with its own NBT carries no spawn reason: add
 * {@code Tags:["uc_permitted_hostile"]} to it.
 *
 * <p><b>Profiles.</b> {@code SIMULATION} is the default everywhere because the default lives in this class, not in a file: a world without the configuration file, a developer launch,
 * a server pack and a single-player world all behave the same. {@code CLASSIC} restores ordinary hostile spawning
 * (world/serverconfig/underground_city-server.toml, key {@code hostileMobs}); it is for operators who want it and is documented in the playtest guide.
 *
 * <p>Why not In Control! (McJty, MIT): version 9.5.0 would do this job with rules in {@code config/incontrol/spawn.json}, but that file must exist in every game directory (a single-player
 * world reads the client's), a missing or reset file silently brings the monsters back, and it cannot see which of our own code spawned a mob. See
 * docs/technical/simulation-world.md for the comparison.
 */
public final class SimulationWorld {
    private static final Logger LOG = LoggerFactory.getLogger("UndergroundCity/Simulation");

    public enum Profile { SIMULATION, CLASSIC }

    /** The scoreboard tag that lets a hostile mob exist in the simulation: scripted enemies, armed criminals, future raids and operator spawns. */
    public static final String PERMIT_TAG = "uc_permitted_hostile";
    /** Entity types that may always exist (data tag, empty by default; a datapack or an operator can add, for example, the vanilla pillager for a scripted raid). */
    public static final TagKey<EntityType<?>> PERMITTED_TYPES = TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "permitted_hostiles"));

    /** Spawns that a person asked for. Everything else (natural, chunk generation, structures, spawners, patrols, reinforcements, triggered, events, summoned, conversions) is automatic. */
    private static final Set<MobSpawnType> DELIBERATE = EnumSet.of(MobSpawnType.COMMAND, MobSpawnType.SPAWN_EGG, MobSpawnType.DISPENSER);

    private static final ForgeConfigSpec.EnumValue<Profile> HOSTILE_MOBS;
    public static final ForgeConfigSpec SPEC;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Underground City world rules (per world; a new world starts from defaultconfigs/underground_city-server.toml when it exists).").push("world");
        HOSTILE_MOBS = builder.comment(
                "SIMULATION (default): ordinary hostile creatures (zombies, skeletons, creepers, spiders, endermen, witches, phantoms, Nether and End monsters, hostile creatures of other mods) do not appear on their own.",
                "  Difficulty, hunger, thirst and the game rule doMobSpawning are NOT changed; animals, villagers, fish and ambient creatures spawn normally. Scripted enemies still work:",
                "  a mob with the tag uc_permitted_hostile, or of a type in the data tag underground_city:permitted_hostiles, is allowed.",
                "CLASSIC: ordinary hostile spawning as in the base game. Changing this takes effect at once; hostile creatures that already exist are removed when you change to SIMULATION.")
            .defineEnum("hostileMobs", Profile.SIMULATION);
        builder.pop();
        SPEC = builder.build();
    }

    /** Set by the tests, in one synchronous call, and restored in a finally block. */
    private static volatile Profile override;

    private static final Map<String, LongAdder> SUPPRESSED = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------------------------------------------------ the rule

    public static Profile profile() {
        Profile forced = override;
        if (forced != null) return forced;
        try {
            return HOSTILE_MOBS.get();
        } catch (IllegalStateException notLoadedYet) {
            return Profile.SIMULATION;   // before the configuration is read the safe answer is the default
        }
    }

    /** Runs {@code body} as if the world had the profile (tests only; synchronous, restores the previous value). */
    static <T> T withProfile(Profile profile, Supplier<T> body) {
        Profile before = override;
        override = profile;
        try {
            return body.get();
        } finally {
            override = before;
        }
    }

    static void withProfile(Profile profile, Runnable body) {
        withProfile(profile, () -> {
            body.run();
            return null;
        });
    }

    /** Is this creature one of the hostile kinds the simulation keeps out (a base-game or modded monster)? */
    public static boolean hostile(Entity entity) {
        return entity instanceof Mob mob && (mob instanceof Enemy || mob.getType().getCategory() == MobCategory.MONSTER);
    }

    /** Is this hostile creature allowed to exist (scripted or placed on purpose)? */
    public static boolean permitted(Entity entity) {
        return entity.getTags().contains(PERMIT_TAG) || entity.getType().is(PERMITTED_TYPES);
    }

    /** Marks a scripted enemy (a guard, an armed criminal, a raider) as allowed. Call it before the entity joins the level. */
    public static <T extends Entity> T permit(T entity) {
        entity.addTag(PERMIT_TAG);
        return entity;
    }

    static boolean deliberate(MobSpawnType type) { return type != null && DELIBERATE.contains(type); }

    /** Should this spawn be refused? {@code type} is null where the game gives no reason (a plain join). */
    static boolean refuses(Entity entity, MobSpawnType type) {
        return profile() == Profile.SIMULATION && hostile(entity) && !permitted(entity) && !deliberate(type);
    }

    private static void suppressed(Entity entity, String route) {
        String type = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()));
        SUPPRESSED.computeIfAbsent(type + " (" + route + ")", key -> new LongAdder()).increment();
        if (LOGGED.size() < 40 && LOGGED.add(type + "/" + route)) LOG.info("[UC-SIMULATION] kept {} out of the world ({}); later ones are counted, see /uc_simulation status", type, route);
    }

    // ------------------------------------------------------------------------------------------------------------ events

    /** Natural spawning and spawner blocks ask here before anything is created. */
    @SubscribeEvent
    public void position(MobSpawnEvent.PositionCheck event) {
        if (!refuses(event.getEntity(), event.getSpawnType())) return;
        event.setResult(Event.Result.DENY);
        suppressed(event.getEntity(), "position " + event.getSpawnType());
    }

    /** Every spawn that carries a reason: structures, patrols, raids, reinforcements, jockeys, triggered spawns (a warden), spawners. A deliberate spawn is tagged so that it persists. */
    @SubscribeEvent
    public void finalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        Mob mob = event.getEntity();
        if (profile() != Profile.SIMULATION || !hostile(mob) || permitted(mob)) return;
        if (deliberate(event.getSpawnType())) {
            permit(mob);
            return;
        }
        event.setSpawnCancelled(true);
        suppressed(mob, "spawn " + event.getSpawnType());
    }

    /** The catch-all: whatever else puts a hostile mob into a level, including a chunk loading one that an older world saved. */
    @SubscribeEvent
    public void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !refuses(event.getEntity(), null)) return;
        event.setCanceled(true);
        suppressed(event.getEntity(), event.loadedFromDisk() ? "removed on load" : "join");
    }

    /** Lightning on a villager makes a witch and on a pig a zombified piglin: in the simulation the villager and the pig stay what they are. */
    @SubscribeEvent
    public void conversion(LivingConversionEvent.Pre event) {
        if (profile() != Profile.SIMULATION) return;
        EntityType<?> outcome = event.getOutcome();
        if (outcome.getCategory() == MobCategory.MONSTER && !outcome.is(PERMITTED_TYPES)) {
            event.setCanceled(true);
            suppressed(event.getEntity(), "conversion to " + net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(outcome));
        }
    }

    /** One line in every server log, so that a restart (and a boot check) can show which profile the world is running. */
    @SubscribeEvent
    public void started(net.minecraftforge.event.server.ServerStartedEvent event) {
        LOG.info("[UC-SIMULATION] hostile creatures: {} (world/serverconfig/underground_city-server.toml, key world.hostileMobs); difficulty {}; doMobSpawning {}", profile(),
            event.getServer().getWorldData().getDifficulty(), event.getServer().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING));
    }

    // ------------------------------------------------------------------------------------------------------------ changing the profile, status

    /** Hostile creatures that are in loaded chunks now and are neither scripted nor permitted. */
    static List<Entity> existing(MinecraftServer server) {
        List<Entity> found = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) if (hostile(entity) && !permitted(entity)) found.add(entity);
        }
        return found;
    }

    /** Removes them without drops. Returns how many. */
    static int purge(MinecraftServer server) {
        List<Entity> found = existing(server);
        for (Entity entity : found) {
            suppressed(entity, "purged");
            entity.discard();
        }
        return found.size();
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("uc_simulation").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("status").executes(ctx -> {
            StringBuilder text = new StringBuilder("Hostile mobs: " + profile() + (profile() == Profile.SIMULATION ? " (ordinary monsters do not appear; scripted ones with the tag " + PERMIT_TAG + " do)" : " (ordinary hostile spawning)"));
            Map<String, Long> counts = new TreeMap<>();
            SUPPRESSED.forEach((key, value) -> counts.put(key, value.sum()));
            if (counts.isEmpty()) text.append("\nNothing has been kept out since the server started.");
            counts.forEach((key, count) -> text.append("\n  ").append(count).append(" x ").append(key));
            int now = existing(ctx.getSource().getServer()).size();
            text.append("\nHostile creatures in loaded chunks now: ").append(now);
            ctx.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
            return 1;
        }));
        root.then(Commands.literal("purge").executes(ctx -> {
            int removed = purge(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " hostile creature(s) from the loaded chunks."), true);
            return removed;
        }));
        event.getDispatcher().register(root);
    }

    /** Called on the mod event bus when the world's configuration is read again (the operator edited the file). Going to SIMULATION removes what already exists. */
    public static void reloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != SPEC) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            if (profile() == Profile.SIMULATION) {
                int removed = purge(server);
                LOG.info("[UC-SIMULATION] hostileMobs is SIMULATION after a configuration reload; removed {} hostile creature(s) from the loaded chunks", removed);
            } else {
                LOG.info("[UC-SIMULATION] hostileMobs is {} after a configuration reload", profile());
            }
        });
    }

    /** Counts of what has been kept out (tests and the status command). */
    static long suppressedTotal() {
        long total = 0;
        for (LongAdder adder : SUPPRESSED.values()) total += adder.sum();
        return total;
    }
}
