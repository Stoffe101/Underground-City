package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The operator's commands (D-191), all under {@code /uc admin} and all needing permission level 2 (an operator, or the dedicated server's console). Credits go through Q Shop's own wallet (the
 * only currency, D-081), never a second balance; progression goes through the citizen record. Every change is checked first (a player who is not online or not registered, a settlement or a
 * Crew payment in flight, a balance that would go below nothing, a level that would leave unlocked skills without the level or the points they need) and refused with a reason, and every change
 * that is made is written to the server log ({@code [UC-ADMIN]}) and to the world's audit record ({@value Audit#NAME}, the last {@value Audit#KEEP} changes, {@code /uc admin audit}), and told to
 * the player. Skill points an operator grants or takes are kept apart from the points levels give, so they never change what a level unlocks.
 *
 * <pre>
 * /uc admin info &lt;player&gt;
 * /uc admin credits &lt;player&gt; [add|remove|set &lt;amount&gt;]
 * /uc admin career &lt;player&gt; xp add|set &lt;amount&gt;     /uc admin career &lt;player&gt; level &lt;level&gt;     /uc admin career &lt;player&gt; points add|remove &lt;n&gt;
 * /uc admin thief &lt;player&gt; xp add|set &lt;amount&gt;      /uc admin thief &lt;player&gt; level &lt;level&gt;      /uc admin thief &lt;player&gt; points add|remove &lt;n&gt;
 * /uc admin general &lt;player&gt; [&lt;skill&gt; xp add|set &lt;amount&gt;]
 * /uc admin relocate &lt;player&gt; &lt;city&gt;
 * /uc admin audit [&lt;count&gt;]
 * </pre>
 * The older {@code /uc dev career_xp} and {@code /uc dev general_xp} stay as they were (adding XP); {@code /uc_prepare} stays the playtest guide's Track B setup.
 */
public final class AdminCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** One command moves at most this many credits, and no wallet is set above the largest balance Core accepts from Q Shop. */
    static final int MAX_CREDITS_STEP = 1_000_000;
    static final double MAX_BALANCE = 1_000_000_000d;
    static final int MAX_XP_STEP = ProgressionRules.MAX_XP;

    private AdminCommands() {}

    /** Builds the {@code admin} branch of {@code /uc}. */
    static LiteralArgumentBuilder<CommandSourceStack> branch() {
        LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin").requires(source -> source.hasPermission(2));
        admin.then(Commands.literal("info").then(player().executes(ctx -> info(ctx, target(ctx)))));
        admin.then(Commands.literal("credits").then(player()
            .executes(ctx -> { ServerPlayer p = target(ctx); return ok(ctx, p.getGameProfile().getName() + " has " + credits(QShopEconomy.balance(p)) + " credits."); })
            .then(Commands.literal("add").then(amount(MAX_CREDITS_STEP).executes(ctx -> credits(ctx, "add"))))
            .then(Commands.literal("remove").then(amount(MAX_CREDITS_STEP).executes(ctx -> credits(ctx, "remove"))))
            .then(Commands.literal("set").then(Commands.argument("amount", IntegerArgumentType.integer(0, Integer.MAX_VALUE)).executes(ctx -> credits(ctx, "set"))))));
        admin.then(track("career", false));
        admin.then(track("thief", true));
        admin.then(Commands.literal("general").then(player()
            .executes(ctx -> general(ctx))
            .then(Commands.argument("skill", StringArgumentType.word()).suggests((ctx, b) -> SharedSuggestionProvider.suggest(new TreeSet<>(ProgressionRules.GENERAL_SKILLS), b))
                .then(Commands.literal("xp")
                    .then(Commands.literal("add").then(amount(MAX_XP_STEP).executes(ctx -> generalXp(ctx, false))))
                    .then(Commands.literal("set").then(Commands.argument("amount", IntegerArgumentType.integer(0, ProgressionRules.MAX_XP)).executes(ctx -> generalXp(ctx, true))))))));
        admin.then(Commands.literal("relocate").then(player().then(Commands.argument("city", StringArgumentType.word())
            .suggests((ctx, b) -> SharedSuggestionProvider.suggest(new TreeSet<>(ProgressionRules.CITIES), b)).executes(AdminCommands::relocate))));
        // the cities generated ahead of play, for test and preparation worlds (ChunkPregen)
        admin.then(Commands.literal("pregen")
            .then(Commands.literal("status").executes(ctx -> ok(ctx, ChunkPregen.status(ctx.getSource().getServer()))))
            .then(Commands.literal("stop").executes(ctx -> ok(ctx, ChunkPregen.stop(ctx.getSource().getServer()))))
            .then(Commands.literal("start").executes(ctx -> ok(ctx, ChunkPregen.start(ctx.getSource().getServer(), "islands", 32)))
                .then(Commands.argument("scope", StringArgumentType.word()).suggests((ctx, b) -> SharedSuggestionProvider.suggest(ChunkPregen.scopes(), b))
                    .executes(ctx -> ok(ctx, ChunkPregen.start(ctx.getSource().getServer(), StringArgumentType.getString(ctx, "scope"), 32)))
                    .then(Commands.argument("margin", IntegerArgumentType.integer(0, ChunkPregen.MAX_MARGIN))
                        .executes(ctx -> ok(ctx, ChunkPregen.start(ctx.getSource().getServer(), StringArgumentType.getString(ctx, "scope"), IntegerArgumentType.getInteger(ctx, "margin"))))))));
        admin.then(Commands.literal("audit").executes(ctx -> audit(ctx, 10)).then(Commands.argument("count", IntegerArgumentType.integer(1, Audit.KEEP)).executes(ctx -> audit(ctx, IntegerArgumentType.getInteger(ctx, "count")))));
        return admin;
    }

    private static ArgumentBuilder<CommandSourceStack, ?> player() { return Commands.argument("player", EntityArgument.player()); }
    private static ArgumentBuilder<CommandSourceStack, ?> amount(int max) { return Commands.argument("amount", IntegerArgumentType.integer(1, max)); }

    /** The career or the Thief branch: XP added or set, a level set, points added or removed. */
    private static LiteralArgumentBuilder<CommandSourceStack> track(String name, boolean thief) {
        int maxLevel = thief ? ProgressionRules.THIEF_MAX_LEVEL : SkillRules.MAX_LEVEL;
        return Commands.literal(name).then(player()
            .executes(ctx -> { ServerPlayer p = target(ctx); CitizenState.Citizen c = citizen(ctx, p); return ok(ctx, trackLine(c, thief)); })
            .then(Commands.literal("xp")
                .then(Commands.literal("add").then(amount(MAX_XP_STEP).executes(ctx -> xp(ctx, thief, false))))
                .then(Commands.literal("set").then(Commands.argument("amount", IntegerArgumentType.integer(0, ProgressionRules.MAX_XP)).executes(ctx -> xp(ctx, thief, true)))))
            .then(Commands.literal("level").then(Commands.argument("level", IntegerArgumentType.integer(1, maxLevel)).executes(ctx -> level(ctx, thief))))
            .then(Commands.literal("points")
                .then(Commands.literal("add").then(Commands.argument("n", IntegerArgumentType.integer(1, CitizenState.MAX_BONUS)).executes(ctx -> points(ctx, thief, 1))))
                .then(Commands.literal("remove").then(Commands.argument("n", IntegerArgumentType.integer(1, CitizenState.MAX_BONUS)).executes(ctx -> points(ctx, thief, -1))))));
    }

    // ------------------------------------------------------------------------------------------------ the commands

    private static int info(CommandContext<CommandSourceStack> ctx, ServerPlayer p) throws CommandSyntaxException {
        CitizenState.Citizen c = CitizenState.get(ctx.getSource().getServer()).find(p.getUUID()).orElse(null);
        StringBuilder out = new StringBuilder(p.getGameProfile().getName() + " (" + p.getUUID() + "): ");
        if (c == null) out.append("not registered. ");
        else {
            out.append(CityArrival.title(c.city)).append(", ").append(Dialogue.careerTitle(c.career)).append(". ").append(trackLine(c, false)).append(" ").append(trackLine(c, true)).append(" ");
            out.append("Skills: ").append(c.skills().isEmpty() ? "none" : String.join(", ", c.skills())).append(". ");
        }
        try { out.append("Credits: ").append(credits(QShopEconomy.balance(p))).append(". "); }
        catch (RuntimeException error) { out.append("Credits: unavailable (").append(error.getMessage()).append("). "); }
        WorkOrderLedger ledger = WorkOrderLedger.get(ctx.getSource().getServer());
        if (!ledger.faulted()) {
            StringBuilder heat = new StringBuilder();
            for (String city : new TreeSet<>(ProgressionRules.CITIES)) { int h = ledger.heat(p.getUUID(), city); if (h > 0) heat.append(CityArrival.title(city)).append(' ').append(h).append(' '); }
            out.append("Heat: ").append(heat.length() == 0 ? "none" : heat.toString().trim()).append(". ");
            if (ledger.player(p.getUUID()).contains("pending")) out.append("A work-order settlement is in flight. ");
        }
        var placed = CityArrival.placed(ctx.getSource().getServer(), p.getUUID());
        out.append("Arrival: ").append(placed.map(a -> a.city() + " at " + a.pos().toShortString() + " (" + a.how() + ")").orElse(CityArrival.pending(ctx.getSource().getServer(), p.getUUID()) ? "pending" : "none recorded")).append(". ");
        out.append("At ").append(p.blockPosition().toShortString()).append(" in ").append(p.level().dimension().location()).append(" (")
            .append(CityRegions.INSTANCE.cityAt(p.level(), p.blockPosition()).map(CityArrival::title).orElse("no city")).append("); respawn ")
            .append(p.getRespawnPosition() == null ? "world spawn" : p.getRespawnPosition().toShortString()).append(PoliceResponse.wanted(p.getUUID()) ? "; the police are after them" : "").append('.');
        return ok(ctx, out.toString());
    }

    private static String trackLine(CitizenState.Citizen c, boolean thief) {
        if (thief) return "Thief level " + c.thiefLevel() + " (" + c.thiefXp() + " XP), points " + SkillService.thiefPointsAvailable(c) + " free of " + SkillService.thiefPointsEarned(c)
            + (c.thiefBonus() != 0 ? " (" + signed(c.thiefBonus()) + " from operators)" : "") + ".";
        return "Career level " + c.careerLevel() + " (" + c.careerXp() + " XP), points " + SkillService.pointsAvailable(c) + " free of " + SkillService.pointsEarned(c)
            + (c.careerBonus() != 0 ? " (" + signed(c.careerBonus()) + " from operators)" : "") + ".";
    }

    private static int credits(CommandContext<CommandSourceStack> ctx, String how) throws CommandSyntaxException {
        ServerPlayer p = target(ctx);
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        String busy = walletBusy(ctx.getSource().getServer(), p);
        if (busy != null) return fail(ctx, busy);
        double before = QShopEconomy.balance(p);
        double after = switch (how) { case "add" -> before + amount; case "remove" -> before - amount; default -> amount; };
        if (after < 0) return fail(ctx, p.getGameProfile().getName() + " has only " + credits(before) + " credits; " + amount + " cannot be removed.");
        if (after > MAX_BALANCE) return fail(ctx, "A balance above " + credits(MAX_BALANCE) + " credits is not allowed.");
        QShopEconomy.applyBalance(p, after);
        return changed(ctx, p, "credits " + how + " " + amount, credits(before) + " -> " + credits(after) + " credits");
    }

    /** Why the wallet must not be touched now (a settlement or a Crew payment between its two halves), or null. */
    static String walletBusy(MinecraftServer server, ServerPlayer p) {
        WorkOrderLedger ledger = WorkOrderLedger.get(server);
        if (ledger.faulted()) return "The work-order ledger is faulted; credits cannot be changed until it is repaired.";
        if (ledger.player(p.getUUID()).contains("pending")) return "A work-order settlement for " + p.getGameProfile().getName() + " is in flight; try again in a moment.";
        CrewLedger crews = CrewService.ledger(server);
        if (crews != null && crews.pendingFor(p.getUUID()) != null) return "A Crew payment for " + p.getGameProfile().getName() + " is in flight; try again in a moment.";
        return null;
    }

    private static int xp(CommandContext<CommandSourceStack> ctx, boolean thief, boolean set) throws CommandSyntaxException {
        ServerPlayer p = target(ctx);
        CitizenState state = CitizenState.get(ctx.getSource().getServer());
        CitizenState.Citizen c = citizen(ctx, p);
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        int before = thief ? c.thiefXp() : c.careerXp();
        int target = set ? amount : (int) Math.min(ProgressionRules.MAX_XP, (long) before + amount);
        String why = lowering(c, thief, thief ? ProgressionRules.thiefLevelForXp(target) : ProgressionRules.levelForXp(target), thief ? c.thiefBonus() : c.careerBonus());
        if (why != null) return fail(ctx, why);
        int now = thief ? state.setGeneralXp(p.getUUID(), ProgressionRules.THIEVERY, target) : state.setCareerXp(p.getUUID(), target);
        return changed(ctx, p, (thief ? "thief" : "career") + " xp " + (set ? "set " : "add ") + amount, before + " -> " + now + " XP (level " + (thief ? c.thiefLevel() : c.careerLevel()) + ")");
    }

    private static int level(CommandContext<CommandSourceStack> ctx, boolean thief) throws CommandSyntaxException {
        ServerPlayer p = target(ctx);
        CitizenState state = CitizenState.get(ctx.getSource().getServer());
        CitizenState.Citizen c = citizen(ctx, p);
        int level = IntegerArgumentType.getInteger(ctx, "level");
        int target = thief ? ProgressionRules.thiefXpAtLevel(level) : ProgressionRules.xpAtLevel(level);
        String why = lowering(c, thief, level, thief ? c.thiefBonus() : c.careerBonus());
        if (why != null) return fail(ctx, why);
        int before = thief ? c.thiefXp() : c.careerXp();
        int now = thief ? state.setGeneralXp(p.getUUID(), ProgressionRules.THIEVERY, target) : state.setCareerXp(p.getUUID(), target);
        return changed(ctx, p, (thief ? "thief" : "career") + " level " + level, before + " -> " + now + " XP");
    }

    private static int points(CommandContext<CommandSourceStack> ctx, boolean thief, int sign) throws CommandSyntaxException {
        ServerPlayer p = target(ctx);
        CitizenState state = CitizenState.get(ctx.getSource().getServer());
        CitizenState.Citizen c = citizen(ctx, p);
        int n = IntegerArgumentType.getInteger(ctx, "n") * sign;
        int bonus = thief ? c.thiefBonus() : c.careerBonus();
        int next = bonus + n;
        if (Math.abs(next) > CitizenState.MAX_BONUS) return fail(ctx, "Operators' points stay within " + CitizenState.MAX_BONUS + " either way; " + p.getGameProfile().getName() + " has " + signed(bonus) + ".");
        String why = lowering(c, thief, thief ? c.thiefLevel() : c.careerLevel(), next);
        if (why != null) return fail(ctx, why);
        state.setBonusPoints(p.getUUID(), thief, next);
        return changed(ctx, p, (thief ? "thief" : "career") + " points " + (sign > 0 ? "add " : "remove ") + Math.abs(n), "operators' points " + signed(bonus) + " -> " + signed(next) + "; " + trackLine(c, thief));
    }

    /**
     * Why a citizen may not be put at {@code level} with {@code bonus} operator points on that track, or null: every skill they have unlocked on it must still have its level, and the points
     * spent must still be covered. Unlocked skills are never taken away by a command; an operator who wants that does it on purpose some other way.
     */
    static String lowering(CitizenState.Citizen c, boolean thief, int level, int bonus) {
        int earned = Math.max(0, (thief ? SkillRules.thiefPointsEarned(Math.max(1, level)) : SkillRules.pointsEarned(Math.max(1, level))) + bonus);
        int spent = thief ? SkillService.thiefPointsSpent(c) : SkillService.pointsSpent(c);
        if (spent > earned) return "That would leave " + spent + " points spent on unlocked " + (thief ? "Thief" : "career") + " skills with only " + earned + " to cover them. Unlocked skills are never taken away by a command.";
        for (SkillNode node : SkillCatalog.INSTANCE.tree(thief ? SkillNode.THIEF : c.career)) {
            if (c.skills().contains(node.id()) && node.minLevel() > level) return "That would leave " + node.id() + " (needs level " + node.minLevel() + ") unlocked below its level.";
        }
        return null;
    }

    private static int general(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = target(ctx);
        CitizenState.Citizen c = citizen(ctx, p);
        StringBuilder out = new StringBuilder(p.getGameProfile().getName() + ": ");
        for (String skill : new TreeSet<>(ProgressionRules.GENERAL_SKILLS)) out.append(skill).append(" L").append(c.generalLevel(skill)).append(" (").append(c.generalXp.getOrDefault(skill, 0)).append(" XP)  ");
        return ok(ctx, out.toString().trim());
    }

    private static int generalXp(CommandContext<CommandSourceStack> ctx, boolean set) throws CommandSyntaxException {
        ServerPlayer p = target(ctx);
        String skill = StringArgumentType.getString(ctx, "skill").toLowerCase(Locale.ROOT);
        if (!ProgressionRules.GENERAL_SKILLS.contains(skill)) return fail(ctx, "Unknown general skill '" + skill + "'. The skills are " + String.join(", ", new TreeSet<>(ProgressionRules.GENERAL_SKILLS)) + ".");
        CitizenState state = CitizenState.get(ctx.getSource().getServer());
        CitizenState.Citizen c = citizen(ctx, p);
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        int before = c.generalXp.getOrDefault(skill, 0);
        int target = set ? amount : (int) Math.min(ProgressionRules.MAX_XP, (long) before + amount);
        if (skill.equals(ProgressionRules.THIEVERY)) {
            String why = lowering(c, true, ProgressionRules.thiefLevelForXp(target), c.thiefBonus());
            if (why != null) return fail(ctx, why);
        }
        int now = state.setGeneralXp(p.getUUID(), skill, target);
        return changed(ctx, p, "general " + skill + " xp " + (set ? "set " : "add ") + amount, before + " -> " + now + " XP (level " + c.generalLevel(skill) + ")");
    }

    private static int relocate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = target(ctx);
        String city = StringArgumentType.getString(ctx, "city").toLowerCase(Locale.ROOT);
        if (!ProgressionRules.validCity(city)) return fail(ctx, "Unknown city '" + city + "'. The cities are " + String.join(", ", new TreeSet<>(ProgressionRules.CITIES)) + ".");
        BlockPosNote from = new BlockPosNote(p);
        var stand = CityArrival.relocate(p, city);
        if (stand.isEmpty()) return fail(ctx, "No safe ground was found at " + CityArrival.title(city) + "'s welcome; nobody was moved.");
        return changed(ctx, p, "relocate " + city, from + " -> " + stand.get().pos().toShortString() + " (" + stand.get().how() + "); citizenship unchanged");
    }

    private record BlockPosNote(ServerPlayer p) { @Override public String toString() { return p.blockPosition().toShortString(); } }

    private static int audit(CommandContext<CommandSourceStack> ctx, int count) {
        List<String> lines = Audit.get(ctx.getSource().getServer()).last(count);
        if (lines.isEmpty()) return ok(ctx, "No operator changes are recorded in this world.");
        for (String line : lines) ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return lines.size();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static ServerPlayer target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException { return EntityArgument.getPlayer(ctx, "player"); }

    private static CitizenState.Citizen citizen(CommandContext<CommandSourceStack> ctx, ServerPlayer p) throws CommandSyntaxException {
        return CitizenState.get(ctx.getSource().getServer()).find(p.getUUID()).orElseThrow(() -> new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
            Component.literal(p.getGameProfile().getName() + " has not registered yet.")).create());
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String text) { ctx.getSource().sendSuccess(() -> Component.literal(text), false); return 1; }
    private static int fail(CommandContext<CommandSourceStack> ctx, String text) { ctx.getSource().sendFailure(Component.literal(text)); return 0; }

    /** A change that was made: logged, audited, told to the operator (and the other operators, as vanilla commands are) and to the player. */
    private static int changed(CommandContext<CommandSourceStack> ctx, ServerPlayer p, String what, String result) {
        String actor = ctx.getSource().getTextName();
        String line = String.format(Locale.ROOT, "[UC-ADMIN] %s: %s on %s: %s", actor, what, p.getGameProfile().getName(), result);
        LOGGER.info(line);
        Audit.get(ctx.getSource().getServer()).add(ctx.getSource().getServer().overworld().getGameTime(), actor, p.getGameProfile().getName(), what, result);
        ctx.getSource().sendSuccess(() -> Component.literal(p.getGameProfile().getName() + ": " + what + ": " + result), true);
        if (ctx.getSource().getEntity() != p) p.sendSystemMessage(Component.literal("An operator (" + actor + ") changed your " + what.split(" ")[0] + ": " + result + ".").withStyle(ChatFormatting.GOLD));
        return 1;
    }

    static String credits(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value); }
    private static String signed(int n) { return n > 0 ? "+" + n : String.valueOf(n); }

    // ------------------------------------------------------------------------------------------------ the audit record

    /** The last operator changes in this world, kept with it. */
    static final class Audit extends SavedData {
        static final String NAME = "underground_city_admin_audit";
        static final int KEEP = 200;
        final List<CompoundTag> entries = new ArrayList<>();

        static Audit get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Audit::load, Audit::new, NAME); }

        static Audit load(CompoundTag tag) {
            Audit a = new Audit();
            ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size() && i < KEEP; i++) a.entries.add(list.getCompound(i));
            return a;
        }

        void add(long time, String actor, String target, String what, String result) {
            CompoundTag e = new CompoundTag();
            e.putLong("time", time); e.putString("actor", actor); e.putString("target", target); e.putString("what", what); e.putString("result", result);
            entries.add(e);
            while (entries.size() > KEEP) entries.remove(0);
            setDirty();
        }

        List<String> last(int count) {
            List<String> out = new ArrayList<>();
            for (int i = Math.max(0, entries.size() - count); i < entries.size(); i++) {
                CompoundTag e = entries.get(i);
                out.add("tick " + e.getLong("time") + ": " + e.getString("actor") + " - " + e.getString("what") + " on " + e.getString("target") + ": " + e.getString("result"));
            }
            return out;
        }

        @Override public CompoundTag save(CompoundTag tag) {
            tag.putInt("schema_version", 1);
            ListTag list = new ListTag();
            entries.forEach(list::add);
            tag.put("entries", list);
            return tag;
        }
    }
}
