package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Art review scenario ({@code -Duc.playtest=gallery}): builds a small, bright showroom on a platform high above the test
 * world and photographs the Core's own blocks and plants from several distances, the way a player meets them. It exists
 * so that every texture, model and stage is judged in the real renderer, not in an editor preview.
 */
final class Gallery {
    private Gallery() {}

    private static net.minecraft.server.level.ServerLevel level() { return Minecraft.getInstance().getSingleplayerServer().overworld(); }

    /** Eye position and look target in world coordinates; the player is stood so that the eyes are at {@code eye}. */
    private static void stand(Vec3 eye, Vec3 target) {
        Vec3 feet = eye.subtract(0, 1.62, 0);
        Vec3 delta = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        Playtest.command(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", feet.x, feet.y, feet.z, yaw, pitch));
    }

    private static void shot(String label, java.util.function.Supplier<Vec3> eye, java.util.function.Supplier<Vec3> target) {
        Playtest.queue("stand for " + label, () -> stand(eye.get(), target.get()));
        Playtest.wait("settle " + label, 14);
        Playtest.queue("photograph " + label, () -> Playtest.screenshotNextFrame("uc-gallery-" + label));
        Playtest.wait("after " + label, 6);
    }

    static void build() {
        final BlockPos[] origin = new BlockPos[1];
        Playtest.queue("creative mode, noon, no HUD and a platform in the sky", () -> {
            var player = Minecraft.getInstance().player;
            origin[0] = new BlockPos(player.getBlockX() + 8, 220, player.getBlockZ() + 8);
            Playtest.command("gamemode creative");
            Playtest.command("time set 6000");
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("weather clear");
            Playtest.command("tp @s " + origin[0].getX() + " " + (origin[0].getY() + 2) + " " + origin[0].getZ());
            Minecraft.getInstance().options.hideGui = true;
        });
        Playtest.wait("let the chunks load", 120);
        Playtest.queue("lay the ground, the water and the plants", () -> {
            int ox = origin[0].getX(), oy = origin[0].getY(), oz = origin[0].getZ();
            Playtest.command(String.format("fill %d %d %d %d %d %d minecraft:grass_block", ox - 6, oy - 1, oz - 6, ox + 22, oy - 1, oz + 22));
            Playtest.command(String.format("fill %d %d %d %d %d %d minecraft:air", ox - 6, oy, oz - 6, ox + 22, oy + 12, oz + 22));
            // the four growth stages in a row, irrigated by a source block at the left end
            Playtest.command(String.format("setblock %d %d %d minecraft:water", ox - 1, oy - 1, oz));
            for (int stage = 0; stage < 4; stage++) {
                Playtest.command(String.format("setblock %d %d %d minecraft:farmland[moisture=7]", ox + stage, oy - 1, oz));
                Playtest.command(String.format("setblock %d %d %d underground_city:sable_leaf_crop[age=%d,nourished=false]", ox + stage, oy, oz, stage));
            }
            // a fed plant (with its nutrient stake) and a small field of mature plants
            Playtest.command(String.format("setblock %d %d %d minecraft:farmland[moisture=7]", ox + 5, oy - 1, oz));
            Playtest.command(String.format("setblock %d %d %d underground_city:sable_leaf_crop[age=2,nourished=true]", ox + 5, oy, oz));
            Playtest.command(String.format("setblock %d %d %d minecraft:water", ox + 11, oy - 1, oz + 4));
            for (int x = 8; x <= 14; x++) for (int z = 2; z <= 6; z++) {
                if (x == 11 && z == 4) continue;
                Playtest.command(String.format("setblock %d %d %d minecraft:farmland[moisture=7]", ox + x, oy - 1, oz + z));
                Playtest.command(String.format("setblock %d %d %d underground_city:sable_leaf_crop[age=%d,nourished=false]", ox + x, oy, oz + z, (x + z) % 4 == 0 ? 2 : 3));
            }
        });
        Playtest.wait("let the meshes build", 160);

        Playtest.queue("crops", () -> {
            int ox = origin[0].getX(), oy = origin[0].getY(), oz = origin[0].getZ();
            Playtest.log("gallery origin " + ox + " " + oy + " " + oz);
        });
        // The row from the front at walking distance, from above and from the side.
        shot("crops-row-front", at(origin, 2.5, 2.2, 5.5), at(origin, 2.5, 0.8, 0.5));
        shot("crops-row-high", at(origin, 2.5, 5.0, 4.5), at(origin, 2.5, 0.6, 0.5));
        shot("crops-row-side", at(origin, 7.5, 1.9, 0.5), at(origin, 1.5, 0.9, 0.5));
        for (int stage = 0; stage < 4; stage++) {
            final int st = stage;
            shot("crop-stage" + stage + "-close", at(origin, st + 0.5, 1.6 + st * 0.2, 3.0 - 0.0), at(origin, st + 0.5, 0.45 + 0.28 * st, 0.5));
        }
        shot("crop-stage3-closeup-a", at(origin, 3.5, 1.7, 2.3), at(origin, 3.5, 1.0, 0.5));
        shot("crop-stage3-closeup-b", at(origin, 5.0, 1.7, 1.2), at(origin, 3.5, 1.0, 0.5));
        shot("crop-stage3-closeup-top", at(origin, 3.5, 3.4, 1.3), at(origin, 3.5, 0.9, 0.5));
        shot("crop-fed-stage2", at(origin, 5.5, 1.5, 2.8), at(origin, 5.5, 0.6, 0.5));
        shot("crops-field-wide", at(origin, 11.5, 3.2, 12.0), at(origin, 11.5, 0.5, 4.5));
        shot("crops-field-low", at(origin, 11.5, 1.8, 9.5), at(origin, 11.5, 1.0, 4.5));
    }

    private static java.util.function.Supplier<Vec3> at(BlockPos[] origin, double dx, double dy, double dz) {
        return () -> new Vec3(origin[0].getX() + dx, origin[0].getY() + dy, origin[0].getZ() + dz);
    }
}
