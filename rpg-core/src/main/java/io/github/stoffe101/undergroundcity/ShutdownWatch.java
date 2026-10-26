package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A dedicated server that has stopped and saved leaves the process (ninth pass, measured): Paladin's Furniture 1.5.0 starts a writer on a non-daemon thread whose loop never ends (it goes
 * back to waiting when its "done" signal comes), so after {@code stop} the world was saved but the process stayed open, using its memory, until it was killed. A probe server held 3.8 GB
 * that way and the next test client was killed for want of memory.
 *
 * <p>Only a dedicated server: a client leaving a single-player world posts the same event and must carry on. When the server has stopped, threads of other mods get
 * {@code uc.shutdownGraceSeconds} (20 by default; 0 turns this off) to finish; the ones still there are named in the log, interrupted, and the process exits.
 */
public final class ShutdownWatch {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int GRACE_SECONDS = Integer.getInteger("uc.shutdownGraceSeconds", 20);

    @SubscribeEvent
    public void stopped(ServerStoppedEvent event) {
        if (GRACE_SECONDS <= 0 || !event.getServer().isDedicatedServer()) return;
        Thread watch = new Thread(ShutdownWatch::watch, "uc-shutdown-watch");
        watch.setDaemon(true);
        watch.start();
    }

    /** The threads that keep the process open: alive, not daemons, not this one and not the JVM's own. */
    static List<Thread> lingering() {
        List<Thread> out = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if (t == Thread.currentThread() || t.isDaemon() || !t.isAlive() || t.getName().equals("DestroyJavaVM")) continue;
            out.add(t);
        }
        return out;
    }

    private static void watch() {
        try {
            Thread.sleep(GRACE_SECONDS * 1000L);
            List<Thread> left = lingering();
            if (left.isEmpty()) return;
            StringBuilder names = new StringBuilder();
            for (Thread t : left) {
                StackTraceElement[] stack = t.getStackTrace();
                String where = "";
                for (StackTraceElement frame : stack) if (!frame.getClassName().startsWith("java.") && !frame.getClassName().startsWith("jdk.")) { where = frame.getClassName() + "." + frame.getMethodName(); break; }
                names.append(names.length() == 0 ? "" : ", ").append(t.getName()).append(where.isEmpty() ? "" : " (" + where + ")");
                t.interrupt();
            }
            LOGGER.warn("[UC] the server stopped and saved {} s ago, but {} thread(s) of other mods keep the process open: {}. Leaving.", GRACE_SECONDS, left.size(), names);
            Thread.sleep(2000L);
            if (!lingering().isEmpty()) System.exit(0);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
