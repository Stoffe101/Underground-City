package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Vanilla datapack reload hook; invalid data aborts the catalog replacement. */
public final class WorkOrderCatalog extends SimpleJsonResourceReloadListener {
    /** The most work orders the catalogue loads with every optional mod installed (69 with the workshops' contracts of D-163; it was 64, and the full-pack journey refused to start). */
    static final int MAX_ORDERS = 128;
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final WorkOrderCatalog INSTANCE = new WorkOrderCatalog();
    private Map<String, WorkOrder> orders = Map.of();
    private WorkOrderCatalog() { super(new Gson(), "work_orders"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources,
                         ProfilerFiller profiler) {
        Map<String, WorkOrder> next = new LinkedHashMap<>();
        data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            WorkOrder order = WorkOrder.fromJson(entry.getKey(), entry.getValue().getAsJsonObject());
            if (order == null) {
                LOGGER.info("Skipping work order {}: the mod it needs is not installed", entry.getKey());
                return;
            }
            next.put(order.id(), order);
        });
        if (next.size() > MAX_ORDERS) throw new IllegalArgumentException("At most " + MAX_ORDERS + " work orders supported");
        orders = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City work orders", orders.size());
    }

    public WorkOrder find(String id) { return orders.get(id); }
    public List<WorkOrder> all() { return orders.values().stream().sorted(java.util.Comparator.comparing(WorkOrder::id)).toList(); }
    public List<WorkOrder> forCareer(String career) {
        return orders.values().stream().filter(job -> job.career().equals(career))
            .sorted(java.util.Comparator.comparingInt(WorkOrder::level).thenComparing(WorkOrder::id)).toList();
    }
}
