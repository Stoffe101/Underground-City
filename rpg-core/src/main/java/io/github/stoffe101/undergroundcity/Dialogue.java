package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Conversation with the named citizens. The server owns every word and every choice: it sends the text and the choices as a packet, the client
 * only draws them (see {@link DialogueScreen}), and each choice comes back as a short id that the server re-validates (the citizen still exists, is
 * in reach, the player is allowed the thing). The registrar, the employers, the gate guard and the residents each have a handful of useful answers,
 * all drawn from game data (the city law, the checkpoint posts, the player's own progress), never invented numbers.
 */
final class Dialogue {
    /** One choice: a stable id, the label, whether it can be taken now and why not. */
    record Choice(String id, String label, boolean enabled, String hint) {}

    private static final double REACH_SQUARED = 64.0;
    private static final Map<UUID, Integer> LAST_CHOICE = new HashMap<>();

    private Dialogue() {}

    /** Opens (or refreshes) the conversation with {@code text} as what the citizen says. */
    static void open(ServerPlayer player, Villager villager, DistrictCatalog.Npc npc, String text) {
        var citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        TerminalNetwork.sendDialogue(player, villager.getId(), npc.name(), subtitle(npc), homeCity(player, villager, citizen), text, choices(npc, citizen));
    }

    static String subtitle(DistrictCatalog.Npc npc) {
        return switch (npc.role()) {
            case "registrar" -> "Registrar";
            case "employer" -> "Employer: " + careerTitle(npc.career());
            case "guard" -> "Gate guard";
            default -> "Resident";
        };
    }

    static String careerTitle(String career) {
        return switch (career == null ? "" : career) {
            case "farmer" -> "Farmer and vintner"; case "smith" -> "Smith and engineer"; case "gunsmith" -> "Gunsmith and arms dealer"; case "technician" -> "Hacker and technician";
            default -> career == null ? "" : career;
        };
    }

    private static String homeCity(ServerPlayer player, Villager villager, CitizenState.Citizen citizen) {
        String region = CityRegions.INSTANCE.cityAt(player.level(), villager.blockPosition()).orElse("");
        return !region.isEmpty() ? region : citizen == null ? "" : citizen.city;
    }

    static List<Choice> choices(DistrictCatalog.Npc npc, CitizenState.Citizen citizen) {
        List<Choice> out = new ArrayList<>();
        switch (npc.role()) {
            case "registrar" -> {
                if (citizen == null) {
                    out.add(new Choice("register", "Register me", true, ""));
                    out.add(new Choice("cities", "Tell me about the three cities", true, ""));
                } else {
                    out.add(new Choice("next", "What should I do next?", true, ""));
                    out.add(new Choice("employer", "Where is my employer?", true, ""));
                    out.add(new Choice("law", "What is the law here?", true, ""));
                }
            }
            case "employer" -> {
                boolean own = citizen != null && citizen.career.equals(npc.career());
                if (citizen == null) out.add(new Choice("register", "How do I register?", true, ""));
                else if (own) {
                    out.add(new Choice("orders", "Open my orders", true, ""));
                    out.add(new Choice("demand", "How does demand work?", true, ""));
                } else out.add(new Choice("employer", "Who employs my trade?", true, ""));
            }
            case "guard" -> {
                out.add(new Choice("law", "What is the law here?", true, ""));
                out.add(new Choice("posts", "Where are the checkpoints?", true, ""));
                out.add(new Choice("search", "How do searches work?", true, ""));
            }
            default -> out.add(new Choice("rumour", "Anything I should know?", true, ""));
        }
        out.add(new Choice("bye", "Goodbye", true, ""));
        return out;
    }

    /** A choice came back from the client. */
    static void choose(ServerPlayer player, int entityId, String id) {
        int now = player.server.getTickCount();
        Integer previous = LAST_CHOICE.get(player.getUUID());
        if (previous != null && now - previous < 3) return;
        LAST_CHOICE.put(player.getUUID(), now);
        Entity entity = player.level().getEntity(entityId);
        if (!(entity instanceof Villager villager) || !CityNpcs.isCityNpc(villager) || player.distanceToSqr(villager) > REACH_SQUARED) return;
        var found = CityNpcs.definition(villager);
        if (found.isEmpty()) return;
        DistrictCatalog.Npc npc = found.get();
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        CompoundTag tag = villager.getPersistentData().getCompound(CityNpcs.TAG);
        villager.getLookControl().setLookAt(player);
        switch (id) {
            case "bye" -> TerminalNetwork.closeDialogue(player);
            case "register" -> { TerminalNetwork.closeDialogue(player); RegistrationService.prompt(player); }
            case "cities" -> open(player, villager, npc, "Ironhaven burns coal and makes steel, Valewood grows and trades, Blackwater ships and brokers. A road joins each pair, about four minutes on foot, and every city has a market and a guard of its own.");
            case "next" -> open(player, villager, npc, citizen == null ? "Register first." : CityNpcs.nextStep(villager, citizen));
            case "employer" -> {
                String own = citizen == null ? null : CityNpcs.employerOf(villager, citizen.career);
                open(player, villager, npc, own == null ? "I do not know of an employer for your trade here." : "Your employer is " + own + ".");
            }
            case "law" -> open(player, villager, npc, lawText(player, villager, citizen));
            case "posts" -> open(player, villager, npc, postsText(player));
            case "search" -> open(player, villager, npc, "A search is a roll weighed by the city's law, your heat and your skills. I cannot see what you carry: only a search can find it, and a hand-off can be seen. Heat fades while you keep to honest work.");
            case "demand" -> open(player, villager, npc, "Every order draws on my shared demand. It refills only while players are active, so nobody can empty it for you while you are away, and nobody can use it up before you return.");
            case "rumour" -> open(player, villager, npc, rumour(npc, tag));
            case "orders" -> {
                String result = CityNpcs.openOrders(player, villager, npc, tag, citizen);
                if (result == null) TerminalNetwork.closeDialogue(player);
                else open(player, villager, npc, result);
            }
            default -> { }
        }
    }

    private static String lawText(ServerPlayer player, Villager villager, CitizenState.Citizen citizen) {
        String city = homeCity(player, villager, citizen);
        CityLaws.Law law = city.isEmpty() ? null : CityLaws.INSTANCE.find(city);
        if (law == null) return "There is no city law posted here.";
        return TerminalScreenText.capitalize(city) + ": " + law.summary();
    }

    private static String postsText(ServerPlayer player) {
        String dimension = player.level().dimension().location().toString();
        CheckpointCatalog.Checkpoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (CheckpointCatalog.Checkpoint post : CheckpointCatalog.INSTANCE.all()) {
            if (!post.dimension().equals(dimension)) continue;
            double d = post.distanceSquared(player.getX(), player.getZ());
            if (d < bestDistance) { best = post; bestDistance = d; }
        }
        if (best == null) return "The posts are on the roads between the cities. Ask again once the roads are marked.";
        String where = CityNpcs.compass(player.blockPosition(), BlockPos.containing(best.x(), player.getY(), best.z()));
        return "The nearest is " + best.title() + ", " + where + ". It is a zone, not a wall: stay on the road and you will pass through it; leave the road and you walk around it, with the wilderness for company.";
    }

    private static String rumour(DistrictCatalog.Npc npc, CompoundTag tag) {
        if (npc.lines().isEmpty()) return "Keep your eyes open and your receipts in order.";
        int n = tag.getInt("talk");
        tag.putInt("talk", n + 1);
        return npc.lines().get(Math.floorMod(n, npc.lines().size()));
    }
}
