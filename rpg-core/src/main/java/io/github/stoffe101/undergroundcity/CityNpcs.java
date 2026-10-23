package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The named citizens of a data-defined district (registrar, employers, residents, guard). They are ordinary vanilla villagers
 * (so beds, workstations and the village bell give them real routines) that carry a small tag naming their district and their
 * entry in the district data. What they say, and what they offer, is read from that data when a player speaks to them, so a
 * datapack can change a line without moving anybody. They are persistent and invulnerable, and the vanilla trade screen is
 * replaced by dialogue: an employer opens the Operations Terminal bound to their office for a citizen of their career,
 * the registrar opens the registration screen for someone who has none, everyone else talks.
 */
public final class CityNpcs {
    static final String TAG = "uc_npc";
    private static final int TALK_COOLDOWN_TICKS = 10;
    private static final double TERMINAL_REACH_SQUARED = 144.0;
    private static final java.util.Map<UUID, Integer> LAST_TALK = new java.util.HashMap<>();

    /**
     * Roles that stay where they were put: a wayside warden has no village bell or bed to be drawn back to, so left alone a vanilla villager strolls off down
     * the road. The tether is a leash on random walking (not a wall) and a mob does not save it, so it is written in the citizen's tag and restored when they load.
     */
    static final java.util.Set<String> TETHERED = java.util.Set.of("warden", "shopkeeper", "guard");
    static final int TETHER_RADIUS = 6;
    /** A shopkeeper keeps to the few blocks behind their counter: a counter is open only while its keeper stands within three blocks of the spot it was opened at ({@link ShopCounters}). */
    static final int SHOPKEEPER_TETHER = 1;

    static int tether(String role) { return role.equals("shopkeeper") ? SHOPKEEPER_TETHER : TETHER_RADIUS; }

    @SubscribeEvent
    public void joined(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Villager villager) || !isCityNpc(villager)) return;
        CompoundTag tag = villager.getPersistentData().getCompound(TAG);
        if (tag.contains("home", Tag.TAG_LONG)) villager.restrictTo(BlockPos.of(tag.getLong("home")), tag.contains("tether", Tag.TAG_INT) ? tag.getInt("tether") : TETHER_RADIUS);
    }

    /** Places one citizen. The floor must be solid with two clear blocks above, exactly as for the supply clerk. */
    static Villager spawn(ServerLevel level, String districtId, int index, DistrictCatalog.Npc npc, BlockPos position, BlockPos terminal) {
        if (!level.getBlockState(position).getCollisionShape(level, position).isEmpty()
            || !level.getBlockState(position.above()).getCollisionShape(level, position.above()).isEmpty()
            || !level.getBlockState(position.below()).isFaceSturdy(level, position.below(), net.minecraft.core.Direction.UP)) {
            throw new IllegalStateException(npc.name() + " needs two clear blocks above a solid floor at " + position.getX() + " " + position.getY() + " " + position.getZ());
        }
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) throw new IllegalStateException("Villager entity unavailable");
        // Experience 1 locks the profession, so a citizen keeps the look of their trade even before their workstation is claimed.
        villager.setVillagerData(new VillagerData(VillagerType.PLAINS, profession(npc.profession()), 1));
        villager.setVillagerXp(1);
        villager.setCustomName(Component.literal(npc.name()));
        villager.setCustomNameVisible(true);
        villager.setPersistenceRequired();
        villager.setInvulnerable(true);
        CompoundTag tag = new CompoundTag();
        tag.putString("district", districtId);
        tag.putInt("index", index);
        if (terminal != null) tag.putLong("terminal", terminal.asLong());
        if (TETHERED.contains(npc.role())) { tag.putLong("home", position.asLong()); tag.putInt("tether", tether(npc.role())); villager.restrictTo(position, tether(npc.role())); }
        villager.getPersistentData().put(TAG, tag);
        villager.moveTo(position.getX() + .5, position.getY(), position.getZ() + .5, 0, 0);
        if (!level.addFreshEntity(villager)) throw new IllegalStateException("Spawn of " + npc.name() + " was rejected");
        return villager;
    }

    static VillagerProfession profession(String name) {
        if (name == null || name.isBlank() || name.equals("none")) return VillagerProfession.NITWIT;
        VillagerProfession found = BuiltInRegistries.VILLAGER_PROFESSION.get(new ResourceLocation(name.contains(":") ? name : "minecraft:" + name));
        return found == null ? VillagerProfession.NITWIT : found;
    }

    static boolean isCityNpc(Villager villager) { return villager.getPersistentData().contains(TAG, Tag.TAG_COMPOUND); }

    static Optional<DistrictCatalog.Npc> definition(Villager villager) {
        if (!isCityNpc(villager)) return Optional.empty();
        CompoundTag tag = villager.getPersistentData().getCompound(TAG);
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(tag.getString("district"));
        if (district == null) return Optional.empty();
        List<DistrictCatalog.Op> ops = district.npcOps();
        int index = tag.getInt("index");
        return index >= 0 && index < ops.size() ? Optional.of(ops.get(index).npc()) : Optional.empty();
    }

    // -------------------------------------------------------------------------------------------------- speaking

    @SubscribeEvent
    public void tick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END && event.getServer().getTickCount() % 10 == 3) Dialogue.tickHolds(event.getServer());
    }

    @SubscribeEvent
    public void loggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) Dialogue.releaseFor(player);
    }

    @SubscribeEvent
    public void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager) || !isCityNpc(villager)) return;
        event.setCanceled(true); // never the vanilla trade screen, in either hand
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer player)) return;
        int now = player.server.getTickCount();
        Integer previous = LAST_TALK.get(player.getUUID());
        if (previous != null && now - previous < TALK_COOLDOWN_TICKS) return;
        LAST_TALK.put(player.getUUID(), now);
        Optional<DistrictCatalog.Npc> npc = definition(villager);
        if (npc.isEmpty()) {
            player.sendSystemMessage(Component.literal("This citizen has nothing to say right now.").withStyle(ChatFormatting.GRAY));
            return;
        }
        villager.getLookControl().setLookAt(player);
        talk(player, villager, npc.get());
    }

    /** A citizen remembers the people they have spoken to (a short list kept on the villager), and greets a returning one differently. */
    private static boolean remember(CompoundTag tag, UUID player) {
        net.minecraft.nbt.ListTag met = tag.getList("met", Tag.TAG_STRING);
        String id = player.toString();
        for (Tag entry : met) if (entry.getAsString().equals(id)) return true;
        met.add(net.minecraft.nbt.StringTag.valueOf(id));
        while (met.size() > 24) met.remove(0);
        tag.put("met", met);
        return false;
    }

    private static String greeting(DistrictCatalog.Npc npc, CitizenState.Citizen citizen, String playerName) {
        int delivered = citizen.journey("deliveries"), accepted = citizen.journey("accepted");
        return switch (npc.role()) {
            case "employer" -> !citizen.career.equals(npc.career()) ? "Back again, " + playerName + ". Your trade is not mine, but I hear things."
                : delivered == 0 && accepted == 0 ? "Back again, " + playerName + ". Your first order is waiting on the terminal."
                : accepted > delivered ? "Back again, " + playerName + ". Your last order is still open: deliver it when the goods are ready."
                : "Back again, " + playerName + ". " + delivered + (delivered == 1 ? " delivery" : " deliveries") + " so far, and you are level " + citizen.careerLevel() + ". Good.";
            case "registrar" -> "You are on the register, " + playerName + ".";
            case "vendor" -> "Back for more, " + playerName + "?";
            case "guard" -> "Papers in order, " + playerName + "?";
            case "instructor" -> "Welcome back to the line, " + playerName + ".";
            case "warden" -> "Still on the road, " + playerName + "?";
            case "shopkeeper" -> "Back again, " + playerName + "? The usual?";
            default -> "Good to see you again, " + playerName + ".";
        };
    }

    static void talk(ServerPlayer player, Villager villager, DistrictCatalog.Npc npc) {
        CompoundTag tag = villager.getPersistentData().getCompound(TAG);
        var citizen = CitizenState.get(player.server).find(player.getUUID());
        boolean knownBefore = remember(tag, player.getUUID());
        villager.getPersistentData().put(TAG, tag);
        String line = nextLine(npc, tag);
        if (knownBefore && citizen.isPresent() && !npc.role().equals("registrar")) line = greeting(npc, citizen.get(), player.getGameProfile().getName()) + " " + line;
        String text = switch (npc.role()) {
            case "registrar" -> citizen.isEmpty() ? line : "You are on the register, " + player.getGameProfile().getName() + ". " + nextStep(villager, citizen.get());
            case "employer" -> {
                if (citizen.isEmpty()) yield "I only hire registered citizens. Speak to the registrar by the gate, or type /uc start.";
                if (!citizen.get().career.equals(npc.career())) yield line;
                CitizenState.get(player.server).markJourney(player.getUUID(), "employer");
                yield line;
            }
            default -> line;
        };
        Dialogue.open(player, villager, npc, text);
    }

    /**
     * An employer's "open my orders": opens the Operations Terminal bound to their office, or, when the player is too far from the desk (by day
     * villagers gather at the bell), sends the employer back to it. Returns what to say instead, or null when the terminal opened.
     */
    static String openOrders(ServerPlayer player, Villager villager, DistrictCatalog.Npc npc, CompoundTag tag, CitizenState.Citizen citizen) {
        if (citizen == null) return "I only hire registered citizens. Speak to the registrar by the gate, or type /uc start.";
        if (!citizen.career.equals(npc.career())) return "That is not my trade.";
        CitizenState.get(player.server).markJourney(player.getUUID(), "employer");
        BlockPos terminal = tag.contains("terminal") ? BlockPos.of(tag.getLong("terminal")) : null;
        if (terminal == null || !villager.level().getBlockState(terminal).is(UndergroundCity.TERMINAL.get())) return "My terminal is out of order. Use any Operations Terminal.";
        if (player.distanceToSqr(terminal.getX() + .5, terminal.getY() + .5, terminal.getZ() + .5) > TERMINAL_REACH_SQUARED) {
            // By day villagers gather at the bell, so the employer may be standing in the lane: say so, and walk back to the desk for the player to follow.
            villager.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                new net.minecraft.world.entity.ai.memory.WalkTarget(terminal, 0.6F, 2));
            return "Come into the office and we will go through the orders. I will walk back to my desk.";
        }
        TerminalNetwork.open(player, terminal);
        return null;
    }

    /** The employer of {@code career} in the same district as {@code from}, named with a rough direction. */
    static String employerOf(Villager from, String career) {
        CompoundTag tag = from.getPersistentData().getCompound(TAG);
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(tag.getString("district"));
        if (district == null || !(from.level() instanceof ServerLevel level)) return null;
        List<DistrictCatalog.Op> ops = district.npcOps();
        for (int i = 0; i < ops.size(); i++) {
            DistrictCatalog.Npc candidate = ops.get(i).npc();
            if (candidate.role().equals("employer") && candidate.career().equals(career)) {
                BlockPos at = DistrictBuilder.locate(level, district, ops.get(i));
                return candidate.name() + (at == null ? "" : ", " + compass(from.blockPosition(), at));
            }
        }
        return null;
    }

    static String nextStep(Villager registrar, CitizenState.Citizen citizen) {
        String employer = employerOf(registrar, citizen.career);
        if (citizen.careerXp() <= 0) {
            return "Your first step: see your employer" + (employer == null ? "" : " - " + employer)
                + ". Take a work order at their terminal and deliver it.";
        }
        if (citizen.skills().isEmpty()) {
            return "You have earned your first experience. Open any Operations Terminal and spend a skill point on the Skills tab.";
        }
        return "Keep going: your employer posts new orders, the exchange pays more as your trust grows, and the gate guard knows what the checkpoints look for.";
    }

    static String compass(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        if (distance < 4) return "right here";
        String ns = Math.abs(dz) * 2 >= Math.abs(dx) ? (dz < 0 ? "north" : "south") : "";
        String ew = Math.abs(dx) * 2 >= Math.abs(dz) ? (dx < 0 ? "west" : "east") : "";
        return "about " + distance + " blocks " + (ns + (ns.isEmpty() || ew.isEmpty() ? "" : "-") + ew).toLowerCase(Locale.ROOT);
    }

    private static String nextLine(DistrictCatalog.Npc npc, CompoundTag tag) {
        if (npc.lines().isEmpty()) return "Good day.";
        int talked = tag.getInt("talk");
        tag.putInt("talk", talked + 1);
        return npc.lines().get(Math.floorMod(talked, npc.lines().size()));
    }

    static void say(ServerPlayer player, DistrictCatalog.Npc npc, String line) {
        player.sendSystemMessage(Component.literal(npc.name() + ": ").withStyle(ChatFormatting.GOLD).append(Component.literal(line).withStyle(ChatFormatting.WHITE)));
    }
}
