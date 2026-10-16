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

    static void talk(ServerPlayer player, Villager villager, DistrictCatalog.Npc npc) {
        CompoundTag tag = villager.getPersistentData().getCompound(TAG);
        var citizen = CitizenState.get(player.server).find(player.getUUID());
        String line = nextLine(npc, tag);
        switch (npc.role()) {
            case "registrar" -> {
                if (citizen.isEmpty()) {
                    say(player, npc, line);
                    RegistrationService.prompt(player);
                } else {
                    say(player, npc, "You are on the register, " + player.getGameProfile().getName() + ". " + nextStep(player, villager, citizen.get()));
                }
            }
            case "employer" -> employer(player, villager, npc, tag, citizen.orElse(null), line);
            default -> say(player, npc, line);
        }
    }

    private static void employer(ServerPlayer player, Villager villager, DistrictCatalog.Npc npc, CompoundTag tag, CitizenState.Citizen citizen, String line) {
        if (citizen == null) {
            say(player, npc, "I only hire registered citizens. Speak to the registrar by the gate, or type /uc start.");
            return;
        }
        if (!citizen.career.equals(npc.career())) {
            say(player, npc, line);
            String own = employerOf(villager, citizen.career);
            if (own != null) player.sendSystemMessage(Component.literal("Your own employer: " + own).withStyle(ChatFormatting.GRAY));
            return;
        }
        BlockPos terminal = tag.contains("terminal") ? BlockPos.of(tag.getLong("terminal")) : null;
        if (terminal == null || !villager.level().getBlockState(terminal).is(UndergroundCity.TERMINAL.get())) {
            say(player, npc, "My terminal is out of order. Use any Operations Terminal.");
            return;
        }
        if (player.distanceToSqr(terminal.getX() + .5, terminal.getY() + .5, terminal.getZ() + .5) > TERMINAL_REACH_SQUARED) {
            say(player, npc, "Come into the office and we will go through the orders.");
            return;
        }
        say(player, npc, line);
        TerminalNetwork.open(player, terminal);
    }

    /** The employer of {@code career} in the same district as {@code from}, named with a rough direction. */
    private static String employerOf(Villager from, String career) {
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

    private static String nextStep(ServerPlayer player, Villager registrar, CitizenState.Citizen citizen) {
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
