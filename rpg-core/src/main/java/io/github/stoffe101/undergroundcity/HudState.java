package io.github.stoffe101.undergroundcity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraftforge.common.util.FakePlayer;

import java.util.UUID;

/**
 * What the persistent on-screen status shows, as the server sees it (D-109). Nothing here is decided by the client: it only draws these numbers and its own clock. A snapshot is small
 * (a few strings and numbers) and is built from state the server already keeps (the citizen record, the Q Shop wallet, the Crew ledger, the household's tenancy, the heat ledger), at most
 * once every two seconds per player and sent only when something in it changed.
 *
 * @param place      where the player stands: a city id, or "" outside every city
 * @param placeKind  0 inside a city's circle, 1 on its outskirts (within {@link #OUTSKIRTS} blocks of it), 2 in the open
 * @param credits    the wallet in credits, or -1 when the wallet cannot be read (no Q Shop, no currency)
 * @param crewBank   the Crew bank in credits, or -1 when the player has no Crew
 * @param homePrepaid cycles of the charge already paid ahead
 * @param homeMinutes minutes of active household time until the next charge, for a home with a charge
 * @param homeStanding CURRENT, LATE or GRACE words of the property rules ("" without a home)
 */
public record HudState(String place, int placeKind, String homeCity, String career, int level, float xpFraction, int xp, int skillPoints, double credits,
                       String crewName, long crewBank, String homeTitle, int homeCharge, int homePrepaid, int homeMinutes, String homeStanding, int homeOwed, int heat, String heatCity) {
    /** How far beyond a city's circle the player is still "on its outskirts". */
    public static final int OUTSKIRTS = 300;

    public static final HudState EMPTY = new HudState("", 2, "", "", 0, 0f, 0, 0, -1, "", -1, "", 0, 0, 0, "", 0, 0, "");

    public boolean registered() { return !homeCity.isEmpty(); }
    public boolean hasCrew() { return crewBank >= 0; }
    public boolean hasHome() { return !homeTitle.isEmpty(); }

    public void write(FriendlyByteBuf b) {
        b.writeUtf(place, 24); b.writeVarInt(placeKind); b.writeUtf(homeCity, 24); b.writeUtf(career, 16); b.writeVarInt(level); b.writeFloat(xpFraction); b.writeVarInt(xp); b.writeVarInt(skillPoints);
        b.writeDouble(credits); b.writeUtf(crewName, 32); b.writeVarLong(crewBank); b.writeUtf(homeTitle, 40); b.writeVarInt(homeCharge); b.writeVarInt(homePrepaid); b.writeVarInt(homeMinutes);
        b.writeUtf(homeStanding, 8); b.writeVarInt(homeOwed); b.writeVarInt(heat); b.writeUtf(heatCity, 24);
    }

    public static HudState read(FriendlyByteBuf b) {
        return new HudState(b.readUtf(24), b.readVarInt(), b.readUtf(24), b.readUtf(16), b.readVarInt(), b.readFloat(), b.readVarInt(), b.readVarInt(), b.readDouble(), b.readUtf(32),
            b.readVarLong(), b.readUtf(40), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(8), b.readVarInt(), b.readVarInt(), b.readUtf(24));
    }

    /** The snapshot for one player right now. A fake player (a test double or a machine) has no screen, so it gets none. */
    static HudState of(ServerPlayer player) { return player instanceof FakePlayer ? EMPTY : compute(player); }

    /** Builds the snapshot from the server's state; a part that cannot be read stays at its empty value. */
    static HudState compute(ServerPlayer player) {
        UUID id = player.getUUID();
        String place = "";
        int kind = 2;
        BlockPos pos = player.blockPosition();
        String dimension = player.level().dimension().location().toString();
        double nearest = Double.MAX_VALUE;
        for (CityRegions.Region region : CityRegions.INSTANCE.all()) {
            if (!region.dimension().equals(dimension)) continue;
            double distance = Math.sqrt(region.distanceSquared(pos));
            if (distance <= region.radius()) { place = region.id(); kind = 0; nearest = -1; break; }
            if (distance - region.radius() < nearest) { nearest = distance - region.radius(); place = region.id(); }
        }
        if (kind != 0) { if (nearest <= OUTSKIRTS) kind = 1; else place = ""; }
        var citizen = CitizenState.get(player.server).find(id);
        String homeCity = "", career = "";
        int level = 0, xp = 0, points = 0;
        float fraction = 0f;
        if (citizen.isPresent()) {
            var c = citizen.get();
            homeCity = c.city;
            career = c.career;
            xp = c.careerXp();
            level = c.careerLevel();
            fraction = ProgressionRules.levelProgress(xp);
            points = SkillService.pointsAvailable(c);
        }
        double credits = -1;
        try { if (citizen.isPresent() && QShopEconomy.available() && QShopEconomy.configured(player.server)) credits = QShopEconomy.balance(player); } catch (RuntimeException ignored) { credits = -1; }
        String crewName = "";
        long bank = -1;
        String homeTitle = "", standing = "";
        int charge = 0, minutes = 0, owed = 0, prepaid = 0;
        CrewLedger ledger = CrewService.ledger(player.server);
        if (ledger != null && !ledger.faulted()) {
            CrewLedger.Crew crew = ledger.crewOf(id);
            if (crew != null) { crewName = crew.name; bank = crew.bank; }
            var rules = PropertyCatalog.INSTANCE.rules();
            java.util.List<CrewLedger.Residence> held = new java.util.ArrayList<>(ledger.residencesOf(CrewLedger.OwnerType.PLAYER, id));
            if (crew != null) held.addAll(ledger.residencesOf(CrewLedger.OwnerType.CREW, crew.id));
            CrewLedger.Residence shown = null;
            for (var r : held) {
                // the home the player is in, else the one whose bill comes first
                if (shown == null || r.insideShell(dimension, pos) || (!shown.insideShell(dimension, pos) && r.charge > 0 && PropertyRules.minutesToNextCycle(r.progress, rules) < PropertyRules.minutesToNextCycle(shown.progress, rules))) shown = r;
            }
            if (shown != null) {
                homeTitle = shown.title;
                charge = shown.charge;
                minutes = shown.billed() ? PropertyRules.minutesToNextCycle(shown.progress, rules) : 0;
                prepaid = shown.prepaid;
                standing = shown.standing(rules).name();
                owed = (int) Math.min(Integer.MAX_VALUE, shown.owedCredits());
            }
        }
        String heatCity = place.isEmpty() ? homeCity : place;
        int heat = heatCity.isEmpty() ? 0 : WorkOrderLedger.get(player.server).heat(id, heatCity);
        return new HudState(place, kind, homeCity, career, level, fraction, xp, points, credits, crewName, bank, homeTitle, charge, prepaid, minutes, standing, owed, heat, heatCity);
    }
}
