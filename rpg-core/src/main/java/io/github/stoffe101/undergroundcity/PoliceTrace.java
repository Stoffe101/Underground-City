package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * What the police did about a crime, step by step (twelfth pass): which crime, who noticed it or why nobody did, the report, the dispatch, where the officers came on foot from, whether their route
 * reached the suspect, the chase, the search, and how it ended (an arrest and what it cost, an escape and why, a warrant). The owner's playtests found crimes that "went unnoticed"; without a trace a
 * report that was refused, a route that ended at a wall and a response that timed out all look the same from the street.
 *
 * <p>Bounded and quiet: the last {@link #KEEP} lines are kept in memory (for {@code /uc_police trace}); they are written to the log only when the trace is on (the system property
 * {@code uc.policeTrace}, a scenario's {@code -PucPtPolicetrace=true}, or {@code /uc_police trace on}). It holds names, cities and short reasons, never an inventory or a wallet.
 */
final class PoliceTrace {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int KEEP = 200;
    static volatile boolean on = Boolean.getBoolean("uc.policeTrace") || Boolean.getBoolean("uc.playtest.policetrace");
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static long total;

    private PoliceTrace() {}

    /** One step of one case: {@code stage} is a word from a short vocabulary (crime, refused, undetected, witnessed, reported, dispatch, arrival, route, pursuit, search, arrest, escape, warrant, dropped, checkpoint, warning). */
    static void note(ServerPlayer player, String city, String stage, String detail) {
        note(player == null ? "?" : player.getGameProfile().getName(), city, stage, detail);
    }

    static synchronized void note(String who, String city, String stage, String detail) {
        String line = who + " " + (city == null || city.isEmpty() ? "-" : city) + " " + stage + ": " + detail;
        RECENT.addLast(line);
        total++;
        while (RECENT.size() > KEEP) RECENT.removeFirst();
        if (on) LOGGER.info("[UC-POLICE-TRACE] {}", line);
    }

    /** The latest {@code n} lines, oldest first. */
    static synchronized List<String> recent(int n) {
        List<String> all = new ArrayList<>(RECENT);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    /** Every line noted so far: a mark for {@link #since}. */
    static synchronized long count() { return total; }

    /** The lines noted since {@code mark} (as many of them as are still kept), oldest first. */
    static synchronized List<String> since(long mark) { return recent((int) Math.min(KEEP, Math.max(0, total - mark))); }

    /** Test seam. */
    static synchronized void reset() { RECENT.clear(); total = 0; }
}
