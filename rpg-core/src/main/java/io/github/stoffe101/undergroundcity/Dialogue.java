package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
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

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final double REACH_SQUARED = 64.0;
    private static final int HOLD_TICKS = 600;
    private static final Map<UUID, Integer> LAST_CHOICE = new HashMap<>();
    /** The citizens standing still for a conversation: villager id to the player they face and the tick the hold lapses. */
    private record Hold(UUID player, int expires) {}
    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    private Dialogue() {}

    /** Opens (or refreshes) the conversation with {@code text} as what the citizen says. */
    static void open(ServerPlayer player, Villager villager, DistrictCatalog.Npc npc, String text) { open(player, villager, npc, text, true); }

    static void open(ServerPlayer player, Villager villager, DistrictCatalog.Npc npc, String text, boolean holdCitizen) {
        var citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        if (holdCitizen) hold(player, villager);
        TerminalNetwork.sendDialogue(player, villager.getId(), npc.name(), subtitle(npc), chipCity(player.level(), villager, citizen == null ? "" : citizen.city), text, choices(npc, citizen));
    }

    /**
     * A citizen being spoken to stops and faces the player, the way a vanilla villager does while trading (the brain's own follow-and-look
     * behaviour, switched on by naming the player as the trading partner). It never freezes their AI: the hold lapses on its own after a
     * while, when the player walks away, leaves, or says goodbye, and the citizen goes back to their day.
     */
    static void hold(ServerPlayer player, Villager villager) {
        villager.setTradingPlayer(player);
        villager.getLookControl().setLookAt(player);
        HOLDS.put(villager.getUUID(), new Hold(player.getUUID(), player.server.getTickCount() + HOLD_TICKS));
    }

    static void release(Villager villager) {
        if (villager.getTradingPlayer() != null) villager.setTradingPlayer(null);
        HOLDS.remove(villager.getUUID());
    }

    /** Releases the holds whose time is up, whose player has gone or walked away, and any whose citizen no longer exists. */
    static void tickHolds(net.minecraft.server.MinecraftServer server) {
        if (HOLDS.isEmpty()) return;
        int now = server.getTickCount();
        var iterator = HOLDS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Villager villager = null;
            for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(entry.getKey()) instanceof Villager found) { villager = found; break; }
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getValue().player());
            boolean over = villager == null || !villager.isAlive() || player == null || now >= entry.getValue().expires()
                || player.level() != villager.level() || player.distanceToSqr(villager) > 100.0;
            if (over) {
                if (villager != null && villager.getTradingPlayer() != null) villager.setTradingPlayer(null);
                iterator.remove();
            }
        }
    }

    static void releaseFor(ServerPlayer player) {
        for (net.minecraft.server.level.ServerLevel level : player.server.getAllLevels()) {
            for (var entry : List.copyOf(HOLDS.entrySet())) {
                if (entry.getValue().player().equals(player.getUUID()) && level.getEntity(entry.getKey()) instanceof Villager villager) release(villager);
            }
        }
    }

    static String subtitle(DistrictCatalog.Npc npc) {
        return switch (npc.role()) {
            case "vendor" -> "Market vendor";
            case "instructor" -> "Range instructor";
            case "registrar" -> "Registrar";
            case "employer" -> "Employer: " + careerTitle(npc.career());
            case "guard" -> "Gate guard";
            case "warden" -> "Road warden";
            case "shopkeeper" -> ShopCounters.INSTANCE.forBuilding(npc.shop()).map(ShopCounters.Shop::title).orElse("Shopkeeper");
            default -> "Resident";
        };
    }

    static String careerTitle(String career) {
        return switch (career == null ? "" : career) {
            case "farmer" -> "Farmer"; case "vintner" -> "Vintner"; case "smith" -> "Smith and engineer"; case "gunsmith" -> "Gunsmith and arms dealer"; case "technician" -> "Hacker and technician";
            default -> career == null ? "" : career;
        };
    }

    /**
     * The city on the card's chip: the city the person stands in. A person of a roadside place or a wayside stop is of no city: before, their card showed the player's own home city, so one diner's
     * cook was "Blackwater" to one player and "Valewood" to the next. Elsewhere outside the cities (Founders Row) it is the player's own city, as the vendor's prices there are.
     */
    static String chipCity(net.minecraft.world.level.Level level, Villager villager, String playerCity) {
        String region = CityRegions.INSTANCE.cityAt(level, villager.blockPosition()).orElse("");
        return !region.isEmpty() ? region : CityNpcs.onTheRoad(villager) ? "" : playerCity;
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
            case "warden" -> {
                out.add(new Choice("where", "Where am I on the road?", true, ""));
                out.add(new Choice("posts", "Where is the nearest checkpoint?", true, ""));
                out.add(new Choice("roadlaw", "What does the law say at either end?", true, ""));
                out.add(new Choice("rumour", "Anything I should know?", true, ""));
            }
            case "vendor" -> {
                out.add(new Choice("stock", "What is on the exchange today?", true, ""));
                out.add(new Choice("rumour", "Any news from the market?", true, ""));
            }
            case "shopkeeper" -> {
                out.add(new Choice("shop", "What have you got?", citizen != null, citizen == null ? "Register first" : ""));
                if (npc.shop().equals(Freight.DEPOT_SHOP)) out.add(new Choice("freight", "Any freight going out?", citizen != null, citizen == null ? "Register first" : ""));
                out.add(new Choice("rumour", "Anything I should know?", true, ""));
            }
            case "instructor" -> {
                out.add(new Choice("rules", "How does the range work?", true, ""));
                out.add(new Choice("score", "What is my best score?", citizen != null, citizen == null ? "Register first" : ""));
            }
            default -> out.add(new Choice("rumour", "Anything I should know?", true, ""));
        }
        out.add(new Choice("bye", "Goodbye", true, ""));
        return out;
    }

    /** A choice came back from the client. */
    static void choose(ServerPlayer player, int entityId, String id) {
        LOGGER.info("[UC] dialogue choice '{}' received from {} for entity {}", id, player.getGameProfile().getName(), entityId);
        int now = player.server.getTickCount();
        // a screen closing ("end") must always get through: it follows the choice that closed it by a tick or two, and dropping it would leave the citizen held
        Integer previous = LAST_CHOICE.get(player.getUUID());
        if (!id.equals("end") && previous != null && now - previous < 3) return;
        if (!id.equals("end")) LAST_CHOICE.put(player.getUUID(), now);
        Entity entity = player.level().getEntity(entityId);
        if (entity instanceof TownspersonEntity person) { PopulationService.choose(player, person, id); return; }
        if (entity instanceof Villager stranger && RoadEncounters.isEncounter(stranger)) { RoadEncounters.choose(player, stranger, id); return; }
        if (!(entity instanceof Villager villager) || !CityNpcs.isCityNpc(villager) || player.distanceToSqr(villager) > REACH_SQUARED) return;
        var found = CityNpcs.definition(villager);
        if (found.isEmpty()) return;
        DistrictCatalog.Npc npc = found.get();
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        CompoundTag tag = villager.getPersistentData().getCompound(CityNpcs.TAG);
        LOGGER.info("[UC] dialogue choice '{}' from {} with {}", id, player.getGameProfile().getName(), npc.name());
        villager.getLookControl().setLookAt(player);
        if (!id.equals("end") && !id.equals("bye")) hold(player, villager);
        switch (id) {
            case "end" -> release(villager);   // the screen was closed on the client: no reply, just let the citizen go
            case "bye" -> { release(villager); TerminalNetwork.closeDialogue(player); }
            case "stock" -> open(player, villager, npc, stockText(player, villager, citizen));
            case "freight" -> open(player, villager, npc, npc.shop().equals(Freight.DEPOT_SHOP) ? Freight.ask(player, villager) : "I do not send freight.");
            case "shop" -> {
                release(villager);
                TerminalNetwork.closeDialogue(player);
                if (citizen == null || !ShopCounters.openKept(player, villager, npc.shop())) {
                    player.sendSystemMessage(Component.literal(citizen == null ? "Register first: the counter only serves citizens." : npc.name() + " has nothing to sell right now.").withStyle(ChatFormatting.GRAY));
                }
            }
            case "rules" -> open(player, villager, npc, "Stand behind the line and fire at the targets. Every hit is scored by how close it lands to the centre, and the board keeps your best. Nobody pays for a range: it is practice for the licence, not a job.");
            case "score" -> open(player, villager, npc, citizen == null ? "Register first." : citizen.journey("range_best") > 0
                ? "Your best so far is " + citizen.journey("range_best") + " out of 10 on a single shot, and " + citizen.journey("range_hits") + " hits in all." : "You have not hit a target yet. Take a gun to the line.");
            case "register" -> { release(villager); TerminalNetwork.closeDialogue(player); RegistrationService.prompt(player); }
            case "cities" -> open(player, villager, npc, "Ironhaven burns coal and makes steel, Valewood grows and trades, Blackwater ships and brokers. A road joins each pair, about four minutes on foot, and every city has a market and a guard of its own.");
            case "next" -> open(player, villager, npc, citizen == null ? "Register first." : CityNpcs.nextStep(villager, citizen));
            case "employer" -> {
                String own = citizen == null ? null : CityNpcs.employerOf(villager, citizen.career);
                open(player, villager, npc, own == null ? "I do not know of an employer for your trade here." : "Your employer is " + own + ".");
            }
            case "law" -> open(player, villager, npc, lawText(player, villager, citizen));
            case "posts" -> open(player, villager, npc, postsText(player));
            case "where" -> open(player, villager, npc, WaysideShelters.where(DevCommands.plans(player.server), villager.getX(), villager.getZ()));
            case "roadlaw" -> open(player, villager, npc, WaysideShelters.roadLaw(DevCommands.plans(player.server), villager.getX(), villager.getZ()));
            case "search" -> open(player, villager, npc, "A search is a roll weighed by the city's law, your heat and your skills. I cannot see what you carry: only a search can find it, and a hand-off can be seen. Heat fades while you keep to honest work.");
            case "demand" -> open(player, villager, npc, "Every order draws on my shared demand. It refills only while players are active, so nobody can empty it for you while you are away, and nobody can use it up before you return.");
            case "rumour" -> open(player, villager, npc, rumour(npc, tag));
            case "orders" -> {
                release(villager); // she may have to walk back to her desk: a held citizen would follow the player instead
                String result = CityNpcs.openOrders(player, villager, npc, tag, citizen);
                if (result == null) TerminalNetwork.closeDialogue(player);
                else open(player, villager, npc, result, false);
            }
            default -> { }
        }
    }

    /** What the market is selling today, from the exchange's real stock and prices: the vendor reads it out, it never invents a number. */
    private static String stockText(ServerPlayer player, Villager villager, CitizenState.Citizen citizen) {
        String city = homeCity(player, villager, citizen);
        if (city.isEmpty() || citizen == null) return "Come back once you are on the register and I will tell you what the exchange has.";
        var offers = RegionalMarket.offers(WorkOrderLedger.get(player.server), city, citizen);
        if (offers.isEmpty()) return "Nothing is listed on the " + TerminalScreenText.capitalize(city) + " exchange today.";
        StringBuilder text = new StringBuilder("The " + TerminalScreenText.capitalize(city) + " exchange today: ");
        int shown = 0;
        for (var offer : offers) {
            if (shown++ == 4) break;
            String name = offer.item().substring(offer.item().indexOf(':') + 1).replace('_', ' ');
            text.append(shown > 1 ? "; " : "").append(name).append(' ').append(offer.price()).append(" credits, ")
                .append(offer.stock() == 0 ? "sold out" : offer.stock() + " left");
        }
        return text.append(". Use any Operations Terminal to buy.").toString();
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
