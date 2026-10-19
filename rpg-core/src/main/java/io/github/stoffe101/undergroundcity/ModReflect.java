package io.github.stoffe101.undergroundcity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reaches the public API of an optional mod without a compile-time dependency on it: the class loader is taken from one of the mod's registered items, so
 * nothing here can break the default pack. Shared by the survival GameTests and the combined journey. A missing method fails loudly (an exception), never quietly.
 */
final class ModReflect {
    private ModReflect() {}

    static Item item(String id) { return ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id)); }

    static ClassLoader loaderOf(String itemId) { return item(itemId).getClass().getClassLoader(); }

    static Class<?> cls(String itemId, String name) throws Exception { return Class.forName(name, true, loaderOf(itemId)); }

    /** Calls a public static method by name, choosing the overload whose parameters accept the arguments. */
    static Object call(Class<?> type, String method, Object... args) throws Exception {
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(method) || !Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != args.length) continue;
            if (accepts(m.getParameterTypes(), args)) return m.invoke(null, args);
        }
        throw new NoSuchMethodException(type.getName() + "." + method + " for " + args.length + " arguments");
    }

    static Object callOn(Object target, String method, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            if (accepts(m.getParameterTypes(), args)) return m.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + method + " for " + args.length + " arguments");
    }

    static boolean accepts(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            Object a = args[i];
            if (a == null) { if (t.isPrimitive()) return false; continue; }
            Class<?> boxed = t == int.class ? Integer.class : t == float.class ? Float.class : t == boolean.class ? Boolean.class : t == long.class ? Long.class : t == double.class ? Double.class : t;
            if (!boxed.isInstance(a)) return false;
        }
        return true;
    }

    static int staticInt(Class<?> type, String field) throws Exception { return type.getField(field).getInt(null); }

    /** Reads a field (public or private, own or inherited) of an object, or of a class when {@code target} is null. */
    static Object field(Class<?> type, Object target, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Object field(Object target, String name) throws Exception { return field(target.getClass(), target, name); }

    /** The value of a Forge config entry (a ForgeConfigSpec value) held in a field. */
    static Object configValue(Object holder, String name) throws Exception { return callOn(field(holder, name), "get"); }
}
