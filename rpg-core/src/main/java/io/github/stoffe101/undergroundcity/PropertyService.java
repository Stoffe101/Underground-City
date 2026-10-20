package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.PropertyRules.Rules;
import io.github.stoffe101.undergroundcity.PropertyRules.Standing;
import io.github.stoffe101.undergroundcity.PropertyRules.Tenure;
import io.github.stoffe101.undergroundcity.PropertyRules.Terms;
import io.github.stoffe101.undergroundcity.PropertyRules.Upgrade;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Renting, buying and keeping a home (D-099): the player-facing flows around {@link CrewLedger}'s tenancy records, and the billing clock.
 *
 * <p>Every flow follows the money protocol of docs/technical/crew-economy.md: validate, write the prepare record, change the wallet to the recorded absolute
 * value, save the player file and read it back, settle, and recover by comparing the wallet with the record. A payment from the Crew bank never touches a
 * wallet and is one atomic ledger write. Nothing here deletes, empties or re-lets a home: a lapsed tenancy keeps its tenant's door and containers (see
 * {@link PropertyRules}); a move-out is refused while a container still holds items.
 *
 * <p>The clock ({@link #tickClock}) counts ACTIVE time only: a home's clock advances while one of its residents is online, once however many are, and never
 * while all of them are offline. Cycles that fall due are settled in the ledger (prepaid hours, the Crew bank when automatic payment is on); a personal home
 * with automatic payment is charged to the owner's wallet here, because only this layer may touch a wallet.
 */
public final class PropertyService {
    private static final Logger LOG = LogUtils.getLogger();
    /** The clock moves in steps of five seconds of server time; its progress is written to disk every five minutes of active play (a crash costs a tenant, at most, five minutes of progress). */
    static final int STEP_TICKS = 100, FLUSH_TICKS = 6000;
    private static final int REMIND_MINUTES = 10;
    /** Homes whose next payment was announced this cycle (transient: an announcement lost to a restart is simply made again). */
    private static final Set<String> ANNOUNCED = new HashSet<>();
    /** How the service finds a person who is online. GameTests replace it with their fake players; production never does. */
    static volatile java.util.function.BiFunction<MinecraftServer, UUID, ServerPlayer> lookup = (server, id) -> server.getPlayerList().getPlayer(id);

    public PropertyService() {}

    // ------------------------------------------------------------------------------------------------------------ data

    static Rules rules() { return PropertyCatalog.INSTANCE.rules(); }

    static Optional<Terms> terms(Residence r) { return PropertyCatalog.INSTANCE.find(r.id); }

    /** The price factor of the city that contains a position (100 outside every city circle). */
    public static int cityFactor(ServerLevel level, BlockPos pos) {
        return rules().factorFor(CityRegions.INSTANCE.cityAt(level, pos).orElse(""));
    }

    /** The facilities a home actually has: those of its listing plus those its upgrades add. A home with no listing has no limits of the new kind. */
    public static Set<String> facilities(Residence r) {
        Optional<Terms> t = terms(r);
        if (t.isEmpty()) return Set.copyOf(PropertyRules.KNOWN_FACILITIES);
        Set<String> all = new HashSet<>(t.get().facilities());
        for (String id : r.upgrades) { Upgrade up = PropertyCatalog.INSTANCE.upgrade(id); if (up != null) all.addAll(up.addFacilities()); }
        return all;
    }

    /** How many containers a home may hold (the listing's number plus the upgrades'); {@code Integer.MAX_VALUE} for a home with no listing. */
    public static int maxContainers(Residence r) {
        Optional<Terms> t = terms(r);
        if (t.isEmpty()) return Integer.MAX_VALUE;
        int total = t.get().maxContainers();
        for (String id : r.upgrades) { Upgrade up = PropertyCatalog.INSTANCE.upgrade(id); if (up != null) total += up.addContainers(); }
        return total;
    }

    /** How many containers each resident may keep private. */
    public static int privateClaims(Residence r) {
        Optional<Terms> t = terms(r);
        if (t.isEmpty()) return CrewRules.MAX_CLAIMS_PER_PLAYER;
        int total = t.get().privateClaims();
        for (String id : r.upgrades) { Upgrade up = PropertyCatalog.INSTANCE.upgrade(id); if (up != null) total += up.addClaims(); }
        return total;
    }

    private static CitizenState.Citizen citizen(ServerPlayer player) {
        return CitizenState.get(player.server).find(player.getUUID()).orElseThrow(() -> new IllegalStateException("Register as a citizen first."));
    }

    private static Residence nearbyHome(ServerPlayer player, CrewLedger ledger, String residenceId, String what) {
        Residence r = ledger.residence(residenceId);
        if (r == null) throw new IllegalStateException("That home does not exist.");
        if (!r.dimension.equals(player.level().dimension().location().toString()) || r.distanceSqr(player.blockPosition()) > 64.0 * 64.0) {
            throw new IllegalStateException("Go to the home first (within 64 blocks) to " + what + " it.");
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------------------------ signing

    /** Rents a home near the player for themselves or their Crew. A motel's welcome stay costs nothing and needs no wallet. */
    public static void rent(ServerPlayer player, UUID tx, String residenceId, boolean forCrew, boolean fromBank) {
        citizen(player);
        CrewLedger ledger = CrewService.need(player.server);
        Residence r = nearbyHome(player, ledger, residenceId, "rent");
        Terms terms = terms(r).orElseThrow(() -> new IllegalStateException("This home has no listing and cannot be rented. It can only be bought."));
        CrewLedger.Quote quote = ledger.rentQuote(player.getUUID(), r, terms, rules());
        if (fromBank) {
            if (!forCrew) throw new IllegalStateException("The Crew bank pays for Crew homes only.");
            if (quote.total() > 0) CrewService.requireMoney(player);
            int paid = ledger.rentForCrewFromBank(tx, player.getUUID(), player.getGameProfile().getName(), residenceId, terms, rules());
            Notice.send(player, Notice.Kind.STEP, "Home rented", r.title + " is rented for the Crew (" + paid + " credits from the bank).");
        } else if (quote.total() > 0) {
            CrewService.transact(player, balance -> ledger.beginRentFromWallet(tx, player.getUUID(), player.getGameProfile().getName(), forCrew, residenceId, balance, terms, rules()));
            Notice.send(player, Notice.Kind.STEP, "Home rented", r.title + " is " + (forCrew ? "the Crew's now." : "yours to live in now."));
        } else {
            ledger.beginRentFromWallet(tx, player.getUUID(), player.getGameProfile().getName(), forCrew, residenceId, 0, terms, rules());
            Notice.send(player, Notice.Kind.STEP, "Welcome", r.title + " is yours for " + rules().welcomeMotelCycles() + " hours of play on the city.");
        }
        announceToCrew(player, ledger, forCrew, "New Crew home", r.title + " is now a Crew home (rented).");
    }

    /** Buys a home near the player (the old word was lease). A home with a listing is sold at its listed price in this city, and then costs its upkeep. */
    public static void buy(ServerPlayer player, UUID tx, String residenceId, boolean forCrew, boolean fromBank) {
        citizen(player);
        CrewLedger ledger = CrewService.need(player.server);
        Residence r = nearbyHome(player, ledger, residenceId, "buy");
        Terms terms = terms(r).orElse(null);
        if (fromBank) {
            CrewService.requireMoney(player);
            if (!forCrew) throw new IllegalStateException("The Crew bank pays for Crew homes only.");
            int price = ledger.buyForCrewFromBank(tx, player.getUUID(), player.getGameProfile().getName(), residenceId, terms);
            Notice.send(player, Notice.Kind.STEP, "Home bought", r.title + " now belongs to the Crew (" + price + " credits from the bank).");
        } else {
            CrewService.transact(player, balance -> ledger.beginBuyFromWallet(tx, player.getUUID(), forCrew, residenceId, balance, terms));
            Notice.send(player, Notice.Kind.STEP, "Home bought", r.title + " is " + (forCrew ? "the Crew's now." : "yours now."));
        }
        announceToCrew(player, ledger, forCrew, "New Crew home", r.title + " is now a Crew home.");
    }

    private static void announceToCrew(ServerPlayer player, CrewLedger ledger, boolean forCrew, String title, String body) {
        Crew crew = ledger.crewOf(player.getUUID());
        if (!forCrew || crew == null) return;
        for (UUID id : crew.members.keySet()) {
            if (id.equals(player.getUUID())) continue;
            ServerPlayer online = player.server.getPlayerList().getPlayer(id);
            if (online != null) Notice.send(online, Notice.Kind.INFO, title, body);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ paying and keeping

    /** Pays {@code cycles} hours of rent or upkeep, from the Crew bank or from the player's own wallet. */
    public static void pay(ServerPlayer player, UUID tx, String residenceId, int cycles, boolean fromBank) {
        citizen(player);
        CrewLedger ledger = CrewService.need(player.server);
        Residence r = ledger.residence(residenceId);
        if (r == null) throw new IllegalStateException("That home does not exist.");
        CrewService.requireMoney(player);
        Standing before = r.standing(rules());
        int paid;
        if (fromBank) paid = ledger.payFromBank(tx, player.getUUID(), player.getGameProfile().getName(), residenceId, cycles, rules());
        else { CrewService.transact(player, balance -> ledger.beginPayFromWallet(tx, player.getUUID(), residenceId, cycles, balance, rules())); paid = cycles * r.charge; }
        String clear = before != Standing.CURRENT && r.standing(rules()) == Standing.CURRENT ? " Everything is paid up again." : "";
        Notice.send(player, Notice.Kind.INFO, "Paid", paid + " credits for " + cycles + " hour" + (cycles == 1 ? "" : "s") + " on " + r.title + "." + clear);
    }

    public static void setAutopay(ServerPlayer player, String residenceId, boolean on) {
        citizen(player);
        CrewService.need(player.server).setAutopay(player.getUUID(), player.getGameProfile().getName(), residenceId, on);
    }

    /** Fits an improvement. {@code index} counts the catalog's upgrades in file order (the screen shows the same list). */
    public static void upgrade(ServerPlayer player, UUID tx, String residenceId, int index, boolean fromBank) {
        citizen(player);
        CrewLedger ledger = CrewService.need(player.server);
        Residence r = nearbyHome(player, ledger, residenceId, "improve");
        List<Upgrade> list = List.copyOf(PropertyCatalog.INSTANCE.upgrades().values());
        if (index < 0 || index >= list.size()) throw new IllegalStateException("Unknown upgrade.");
        Upgrade up = list.get(index).discounted(SkillService.upgradeDiscountPercent(player));   // the acting member's trade skill, recorded in the price they pay
        CrewService.requireMoney(player);
        if (fromBank) ledger.upgradeFromBank(tx, player.getUUID(), player.getGameProfile().getName(), residenceId, up, rules());
        else CrewService.transact(player, balance -> ledger.beginUpgradeFromWallet(tx, player.getUUID(), residenceId, up, balance, rules()));
        Notice.send(player, Notice.Kind.STEP, up.title(), r.title + " now has: " + up.summary());
    }

    // ------------------------------------------------------------------------------------------------------------ leaving

    /**
     * What is still stored in a home: the number of containers (chests, barrels, placed backpacks, machines with an item handler) and of frames, stands and
     * chest vehicles that hold items. A move-out and a sale are refused until this is empty, so belongings are never thrown away with a home.
     */
    public static int storedThings(MinecraftServer server, Residence r) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(r.dimension)));
        if (level == null) throw new IllegalStateException("That home's world is not loaded.");
        int found = 0;
        for (int cx = r.min.getX() >> 4; cx <= r.max.getX() >> 4; cx++) {
            for (int cz = r.min.getZ() >> 4; cz <= r.max.getZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (r.insideInterior(r.dimension, entity.getBlockPos()) && holdsItems(entity)) found++;
                }
            }
        }
        AABB box = new AABB(r.min.getX(), r.min.getY(), r.min.getZ(), r.max.getX() + 1, r.max.getY() + 1, r.max.getZ() + 1);
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box, ResidenceGuard::protectedEntity)) if (ResidenceGuard.holdsThings(entity)) found++;
        return found;
    }

    /** Every storage container inside a home, empty or not (furnaces and hoppers are not storage). */
    public static int containerCount(ServerLevel level, Residence r) {
        int found = 0;
        for (int cx = r.min.getX() >> 4; cx <= r.max.getX() >> 4; cx++) {
            for (int cz = r.min.getZ() >> 4; cz <= r.max.getZ() >> 4; cz++) {
                for (BlockEntity entity : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (r.insideInterior(r.dimension, entity.getBlockPos()) && ResidenceGuard.isStorage(entity)) found++;
                }
            }
        }
        return found;
    }

    static boolean holdsItems(BlockEntity entity) {
        if (!ResidenceGuard.isContainer(entity)) return false;
        if (entity instanceof Container container) return !container.isEmpty();
        return entity.getCapability(ForgeCapabilities.ITEM_HANDLER, null).map(handler -> {
            for (int slot = 0; slot < handler.getSlots(); slot++) if (!handler.getStackInSlot(slot).isEmpty()) return true;
            return false;
        }).orElse(false);
    }

    private static void requireEmpty(ServerPlayer player, Residence r) {
        if (!r.dimension.equals(player.level().dimension().location().toString()) || r.distanceSqr(player.blockPosition()) > 64.0 * 64.0) {
            throw new IllegalStateException("Go to the home first (within 64 blocks) to leave it.");
        }
        int stored = storedThings(player.server, r);
        if (stored > 0) {
            throw new IllegalStateException(stored + " container" + (stored == 1 ? " in this home still holds" : "s in this home still hold") + " items. Take your things out first: nothing stored is ever thrown away. Fixtures you built stay.");
        }
    }

    /** A tenant gives the home back; the deposit returns less what is owed (the refund reaches the wallet by the protocol that already survives a crash). */
    public static int moveOut(ServerPlayer player, String residenceId) {
        citizen(player);
        CrewLedger ledger = CrewService.need(player.server);
        Residence r = ledger.residence(residenceId);
        if (r == null) throw new IllegalStateException("That home does not exist.");
        if (!ledger.canManage(player.getUUID(), r)) throw new IllegalStateException(r.ownerType == OwnerType.CREW ? "Your role may not handle this home for the Crew." : "This is not your home.");
        requireEmpty(player, r);
        String title = r.title;
        int back = ledger.moveOut(player.getUUID(), player.getGameProfile().getName(), residenceId);
        CrewService.claimOwedQuietly(player);
        Notice.send(player, Notice.Kind.INFO, "Moved out", title + " is free again." + (back > 0 ? " " + back + " credits of the deposit came back." : ""));
        return back;
    }

    /** An owner sells the home back to the city for the stated share of what was paid. */
    public static int sellBack(ServerPlayer player, String residenceId) {
        citizen(player);
        CrewLedger ledger = CrewService.need(player.server);
        Residence r = ledger.residence(residenceId);
        if (r == null) throw new IllegalStateException("That home does not exist.");
        if (!ledger.canManage(player.getUUID(), r)) throw new IllegalStateException(r.ownerType == OwnerType.CREW ? "Your role may not handle this home for the Crew." : "This is not your home.");
        requireEmpty(player, r);
        String title = r.title;
        int back = ledger.sellBack(player.getUUID(), player.getGameProfile().getName(), residenceId, rules());
        CrewService.claimOwedQuietly(player);
        Notice.send(player, Notice.Kind.INFO, "Sold", title + " was sold back to the city for " + back + " credits.");
        return back;
    }

    public static void transferToCrew(ServerPlayer player, String residenceId) {
        citizen(player);
        CrewService.need(player.server).transferToCrew(player.getUUID(), player.getGameProfile().getName(), residenceId);
    }

    public static void transferToFounder(ServerPlayer player, String residenceId) {
        citizen(player);
        CrewService.need(player.server).transferToFounder(player.getUUID(), player.getGameProfile().getName(), residenceId);
    }

    // ------------------------------------------------------------------------------------------------------------ the clock

    private int untilFlush;

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % STEP_TICKS != 0) return;
        try {
            tickClock(event.getServer(), STEP_TICKS);
            untilFlush += STEP_TICKS;
            if (untilFlush >= FLUSH_TICKS) {
                untilFlush = 0;
                CrewLedger ledger = CrewService.ledger(event.getServer());
                if (ledger != null) ledger.flushProgress();
            }
        } catch (CrewService.SimulatedCrash crash) {
            throw crash;
        } catch (RuntimeException error) {
            LOG.error("The rent clock failed; it will try again at the next step", error);
        }
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        CrewLedger ledger = CrewService.ledger(player.server);
        if (ledger != null && !ledger.faulted()) ledger.flushProgress();
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        CrewLedger ledger = CrewService.ledger(event.getServer());
        if (ledger != null && !ledger.faulted()) ledger.flushProgress();
    }

    /** The people online who can advance a home's clock: real players, not the fake ones tests and mods create. */
    static Set<UUID> onlineHumans(MinecraftServer server) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (!(player instanceof FakePlayer)) online.add(player.getUUID());
        return online;
    }

    /** One step of the billing clock. Public for GameTests, which drive it with a chosen number of ticks and a chosen set of people online. */
    public static List<CrewLedger.Event> tickClock(MinecraftServer server, long ticks) { return tickClock(server, ticks, onlineHumans(server)); }

    public static List<CrewLedger.Event> tickClock(MinecraftServer server, long ticks, Set<UUID> online) {
        CrewLedger ledger = CrewService.ledger(server);
        if (ledger == null || ledger.faulted() || online.isEmpty()) return List.of();
        Rules rules = rules();
        List<CrewLedger.Event> events = new ArrayList<>(ledger.advance(online, ticks, rules));
        // A personal home with automatic payment from the owner's wallet: only this layer may touch a wallet.
        for (Residence r : List.copyOf(ledger.allResidences())) {
            int guard = 0;
            while (r.due > 0 && r.autopay && r.ownerType == OwnerType.PLAYER && guard++ < 8) {
                ServerPlayer owner = lookup.apply(server, r.owner);
                CrewLedger.Event miss;
                if (owner == null || !online.contains(owner.getUUID()) || !CrewService.moneyReady(server)) { miss = ledger.missDue(r.id, rules); }
                else if (Math.floor(CrewService.wallet.balance(owner)) < r.charge) { miss = ledger.missDue(r.id, rules); }   // the wallet is short: the hour becomes unpaid
                else {
                    try {
                        CrewService.transact(owner, balance -> ledger.beginPayFromWallet(UUID.randomUUID(), owner.getUUID(), r.id, 1, balance, rules));
                        events.add(new CrewLedger.Event(r.id, "PAID_WALLET", String.valueOf(r.charge)));
                        continue;
                    } catch (CrewService.SimulatedCrash crash) {
                        throw crash;
                    } catch (RuntimeException refused) {
                        // The wallet has the credits but the money layer said not now (a recovery or another transaction of this player is in flight). That is not the tenant's failure to pay:
                        // the hour stays due and the next step tries again; four such refusals must never lapse a home.
                        LOG.debug("Automatic rent for {} will be retried: {}", r.id, refused.getMessage());
                        break;
                    }
                }
                if (miss != null) events.add(miss);
            }
        }
        for (CrewLedger.Event event : events) tell(server, ledger, event, rules);
        remind(server, ledger, online, rules);
        return events;
    }

    private static void tell(MinecraftServer server, CrewLedger ledger, CrewLedger.Event event, Rules rules) {
        Residence r = ledger.residence(event.residence());
        if (r == null) return;
        for (UUID id : ledger.residentsOf(r)) {
            ServerPlayer player = lookup.apply(server, id);
            if (player == null) continue;
            long owed = r.owedCredits();
            switch (event.kind()) {
                case "PAID_WALLET" -> Notice.send(player, Notice.Kind.INFO, "Rent paid", r.title + ": " + event.detail() + " credits for the next hour of play, paid automatically.");
                case "PAID_BANK" -> Notice.send(player, Notice.Kind.INFO, "Rent paid", r.title + ": " + event.detail() + " credits from the Crew bank, paid automatically.");
                case "MISSED" -> Notice.send(player, Notice.Kind.WARN, "Rent overdue", r.title + ": " + event.detail() + " hour(s) unpaid, " + owed + " credits. Pay on the Home page (press K). Nothing is lost.");
                case "STANDING" -> {
                    switch (Standing.valueOf(event.detail())) {
                        case NOTICE -> Notice.send(player, Notice.Kind.WARN, "Final notice", r.title + ": pay " + owed + " credits within " + rules.noticeCycles() + " hour of play to keep the comforts of the home.");
                        case LAPSED -> Notice.send(player, Notice.Kind.WARN, "Home lapsed", r.title + " is locked to visiting and taking your things out. Nothing is lost or re-let; pay " + owed + " credits to restore it, or move out.");
                        case CURRENT -> Notice.send(player, Notice.Kind.INFO, "Paid up", r.title + " is in good standing again.");
                        default -> { }
                    }
                }
                default -> { }
            }
        }
    }

    /** "Rent is due in 8 minutes": once per cycle, to the residents who are online, only when it will actually be due (not prepaid, not about to be paid automatically). */
    private static void remind(MinecraftServer server, CrewLedger ledger, Set<UUID> online, Rules rules) {
        for (Residence r : ledger.allResidences()) {
            if (!r.billed()) continue;
            int minutes = PropertyRules.minutesToNextCycle(r.progress, rules);
            if (minutes > REMIND_MINUTES) { ANNOUNCED.remove(r.id); continue; }
            if (r.prepaid > 0 || r.autopay || !ANNOUNCED.add(r.id)) continue;
            for (UUID id : ledger.residentsOf(r)) {
                ServerPlayer player = lookup.apply(server, id);
                if (player != null && online.contains(id)) {
                    Notice.send(player, Notice.Kind.INFO, "Rent soon", r.title + ": " + r.charge + " credits due in " + minutes + " min of play. Pay in advance or switch on automatic payment (press K).");
                }
            }
        }
    }

    /** What a player hears when they log in: one line per home that needs attention. */
    public static void greet(ServerPlayer player, CrewLedger ledger) {
        Rules rules = rules();
        List<Residence> mine = new ArrayList<>(ledger.residencesOf(OwnerType.PLAYER, player.getUUID()));
        Crew crew = ledger.crewOf(player.getUUID());
        if (crew != null) mine.addAll(ledger.residencesOf(OwnerType.CREW, crew.id));
        for (Residence r : mine) {
            if (!r.billed()) continue;
            Standing standing = r.standing(rules);
            if (standing == Standing.CURRENT) {
                if (r.prepaid == 0 && !r.autopay) player.sendSystemMessage(Component.literal(r.title + ": rent is " + r.charge + " credits an hour of play; the next payment is due in " + PropertyRules.minutesToNextCycle(r.progress, rules) + " min of play."));
            } else {
                player.sendSystemMessage(Component.literal(r.title + ": " + r.arrears + " hour(s) unpaid (" + r.owedCredits() + " credits). " + (standing == Standing.LAPSED ? "The home is lapsed; your things are safe." : "Pay on the Home page (press K).")));
            }
        }
    }

    /** The chunk positions a home touches, for callers that need to load them. */
    static List<ChunkPos> chunksOf(Residence r) {
        List<ChunkPos> list = new ArrayList<>();
        for (int cx = r.min.getX() >> 4; cx <= r.max.getX() >> 4; cx++) for (int cz = r.min.getZ() >> 4; cz <= r.max.getZ() >> 4; cz++) list.add(new ChunkPos(cx, cz));
        return list;
    }
}
