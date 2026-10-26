package io.github.stoffe101.undergroundcity;

import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;

/**
 * A test world with its islands generated ahead of play ({@code -PucPlaytest=pregen [-PucPtScope=islands] [-PucPtMargin=32]}): a new world is made as every scenario makes one, the islands
 * (or a city) are generated through {@link ChunkPregen}, the world is saved and the client leaves. The saved world ({@code run-<dir>/saves/<world>}) is a template: a later run that finds a world
 * of its own name opens it instead of making one, so a copy of the template saves that run the wait for Lost Cities. Nobody plays in this run; it only measures how long the islands take.
 */
final class PregenScenario {
    private PregenScenario() {}

    static void build() {
        String scope = System.getProperty("uc.playtest.scope", "islands");
        int margin = Integer.getInteger("uc.playtest.margin", 32);
        int budget = Integer.getInteger("uc.playtest.limitseconds", 3600) * 20;
        ThiefScenario.tag = "PREGEN";
        Playtest.queue("a still spectator at the world's spawn", () -> { Playtest.command("gamemode spectator"); scripted("the player is a still spectator at the spawn (nobody plays in this run)"); });
        Playtest.wait("the world settles", 100);
        long[] began = {0};
        Playtest.queue("start generating " + scope, () -> {
            began[0] = System.nanoTime();
            Playtest.log("PREGEN " + server(() -> ChunkPregen.start(mc().getSingleplayerServer(), scope, margin)));
        });
        int[] tick = {0};
        Playtest.queue("until it has finished", () -> { }, () -> {
            if (++tick[0] % 200 != 0) return false;
            String[] status = {""};
            boolean done = server(() -> { var store = ChunkPregen.store(mc().getSingleplayerServer()); status[0] = ChunkPregen.status(mc().getSingleplayerServer()); return store.finished; });
            if (tick[0] % 1200 == 0 || done) Playtest.log("PREGEN " + status[0]);
            return done;
        }, budget);
        Playtest.queue("the result", () -> {
            var store = server(() -> ChunkPregen.store(mc().getSingleplayerServer()));
            int seconds = (int) ((System.nanoTime() - began[0]) / 1_000_000_000L);
            Playtest.log("PREGEN " + store.done + " of " + store.total + " chunks done, " + store.skipped + " let go unfinished, in " + seconds + " s");
            if (!store.finished) Playtest.fail("the pre-generation did not finish");
            else if (store.skipped > store.total / 100) Playtest.fail(store.skipped + " chunks were let go unfinished (more than 1 in 100)");
            else pass("1 " + store.done + " chunks of " + scope + " generated in " + seconds + " s");
        });
        Playtest.queue("save the world", () -> Playtest.command("save-all flush"));
        Playtest.wait("saved", 200);
        Playtest.queue("summary", ThiefScenario::summary);
    }
}
