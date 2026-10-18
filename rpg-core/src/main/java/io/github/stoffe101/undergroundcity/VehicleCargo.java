package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * What a checkpoint guard can search in the vehicle a traveller rides: one list of slots, whatever the vehicle mod stores them in.
 *
 * <p>Vanilla boats, minecarts and Modern Life's Motorboat keep their hold in a {@link Container}. Immersive Vehicles does not: a car, truck or boat carries
 * <b>crate parts</b> (a part slot of type {@code interactable_crate}), and each crate has its own inventory object that is not a Container. This adapter
 * walks the vehicle's parts and exposes every crate's slots, so a Quad with a crate in its rack is searched like a boat with a chest. It reaches Immersive
 * Vehicles by reflection only (the Core never compiles against it), reads a stack through the mod's own item wrapper and removes a seized stack with the
 * mod's own {@code removeFromSlot}, which also keeps its caches right. When the mod's internals are not what this class expects it steps aside with one
 * log line and the vehicle is searched as if it had no hold; it never throws into the search.
 */
public final class VehicleCargo {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean broken;

    private VehicleCargo() {}

    /** The slots of one vehicle's hold. Reads are cheap; {@link #take} removes the whole stack in the slot. */
    public interface Hold {
        int size();
        ItemStack get(int slot);
        void take(int slot);
        /** Called once after the last {@link #take}. */
        void changed();
    }

    /** The hold of the vehicle a traveller rides, or null when the vehicle has none the search can see. */
    public static Hold of(Entity ridden) {
        if (ridden == null) return null;
        if (ridden instanceof Container container) return new ContainerHold(container);
        if (broken || !ModList.get().isLoaded("mts")) return null;
        try {
            return ImmersiveVehicles.holdOf(ridden);
        } catch (ReflectiveOperationException | RuntimeException error) {
            broken = true;
            LOGGER.warn("[UC] Immersive Vehicles cargo could not be read ({}); vehicles of that mod are searched without their crates until this is looked at", error.toString());
            return null;
        }
    }

    private record ContainerHold(Container container) implements Hold {
        @Override public int size() { return container.getContainerSize(); }
        @Override public ItemStack get(int slot) { return container.getItem(slot); }
        @Override public void take(int slot) { container.setItem(slot, ItemStack.EMPTY); }
        @Override public void changed() { container.setChanged(); }
    }

    /** Several crates presented as one hold. */
    private record Crates(List<Crate> crates) implements Hold {
        @Override public int size() { int total = 0; for (Crate crate : crates) total += crate.size; return total; }
        @Override public ItemStack get(int slot) {
            for (Crate crate : crates) { if (slot < crate.size) return crate.get(slot); slot -= crate.size; }
            return ItemStack.EMPTY;
        }
        @Override public void take(int slot) {
            for (Crate crate : crates) { if (slot < crate.size) { crate.take(slot); return; } slot -= crate.size; }
        }
        @Override public void changed() {}
    }

    /** One crate part's inventory (an {@code EntityInventoryContainer}). */
    private static final class Crate {
        final Object inventory;
        final int size;
        final Method getStack, removeFromSlot, stackSize;
        final Field wrapped;

        Crate(Object inventory) throws ReflectiveOperationException {
            this.inventory = inventory;
            Class<?> type = inventory.getClass();
            this.size = (int) type.getMethod("getSize").invoke(inventory);
            this.getStack = type.getMethod("getStack", int.class);
            this.removeFromSlot = type.getMethod("removeFromSlot", int.class, int.class);
            Class<?> wrapperType = Class.forName("mcinterface1201.WrapperItemStack", false, type.getClassLoader());
            this.wrapped = wrapperType.getDeclaredField("stack");
            this.wrapped.setAccessible(true);
            this.stackSize = Class.forName("minecrafttransportsimulator.mcinterface.IWrapperItemStack", false, type.getClassLoader()).getMethod("getSize");
        }

        ItemStack get(int slot) {
            try {
                Object wrapper = getStack.invoke(inventory, slot);
                return wrapper == null ? ItemStack.EMPTY : (ItemStack) wrapped.get(wrapper);
            } catch (ReflectiveOperationException | RuntimeException error) {
                broken = true;
                LOGGER.warn("[UC] Immersive Vehicles crate slot could not be read ({})", error.toString());
                return ItemStack.EMPTY;
            }
        }

        void take(int slot) {
            try {
                Object wrapper = getStack.invoke(inventory, slot);
                int count = wrapper == null ? 0 : (int) stackSize.invoke(wrapper);
                if (count > 0) removeFromSlot.invoke(inventory, slot, count);
            } catch (ReflectiveOperationException | RuntimeException error) {
                broken = true;
                LOGGER.warn("[UC] Immersive Vehicles crate slot could not be emptied ({})", error.toString());
            }
        }
    }

    /** Names and shapes taken from Immersive Vehicles 22.x (see docs/technical/mod-audit.md); everything is looked up by name at the moment of a search. */
    static final class ImmersiveVehicles {
        static Hold holdOf(Entity ridden) throws ReflectiveOperationException {
            String type = ridden.getClass().getName();
            if (!type.startsWith("mcinterface1201.")) return null;
            ClassLoader loader = ridden.getClass().getClassLoader();
            // BuilderEntityExisting (the vehicle itself) and BuilderEntityLinkedSeat (what a rider is attached to) both hold the mod's entity in "entity".
            Object entity = field(ridden.getClass(), "entity").get(ridden);
            return holdOfEntity(entity, loader);
        }

        /** {@code entity} is a vehicle ({@code AEntityF_Multipart}) or one of its parts, a seat for instance; a part leads to the vehicle it sits on. */
        static Hold holdOfEntity(Object entity, ClassLoader loader) throws ReflectiveOperationException {
            if (entity == null) return null;
            Class<?> part = Class.forName("minecrafttransportsimulator.entities.instances.APart", false, loader);
            Object master = part.isInstance(entity) ? field(part, "masterEntity").get(entity) : entity;
            if (master == null) return null;
            Class<?> multipart = Class.forName("minecrafttransportsimulator.entities.components.AEntityF_Multipart", false, loader);
            if (!multipart.isInstance(master)) return null;
            Class<?> interactable = Class.forName("minecrafttransportsimulator.entities.instances.PartInteractable", false, loader);
            Field inventory = field(interactable, "inventory");
            List<Crate> crates = new ArrayList<>();
            for (Object each : (List<?>) field(multipart, "allParts").get(master)) {
                if (!interactable.isInstance(each)) continue;
                Object contents = inventory.get(each);
                if (contents != null) crates.add(new Crate(contents));
            }
            return crates.isEmpty() ? null : new Crates(crates);
        }

        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    Field found = c.getDeclaredField(name);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchFieldException ignored) { /* look in the superclass */ }
            }
            throw new NoSuchFieldException(name + " in " + type.getName());
        }
    }
}
