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
        Playtest.queue("spectator camera (no collisions, no hand), noon, no HUD and a platform in the sky", () -> {
            var player = Minecraft.getInstance().player;
            origin[0] = new BlockPos(player.getBlockX() + 8, 220, player.getBlockZ() + 8);
            Playtest.command("gamemode spectator");
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

        // The Core's own blocks on a stone strip: each facing the camera, then at a three-quarter angle.
        Playtest.queue("lay the block showroom", () -> {
            int ox = origin[0].getX(), oy = origin[0].getY(), oz = origin[0].getZ();
            Playtest.command(String.format("fill %d %d %d %d %d %d minecraft:smooth_stone", ox, oy - 1, oz + 14, ox + 12, oy - 1, oz + 18));
            String[][] blocks = {{"operations_terminal", "1"}, {"secure_data_terminal", "4"}, {"property_cache", "7"}, {"public_ledger", "10"}};
            for (String[] block : blocks) {
                Playtest.command(String.format("setblock %d %d %d underground_city:%s[facing=south]", ox + Integer.parseInt(block[1]), oy, oz + 16, block[0]));
            }
        });
        Playtest.wait("let the block meshes build", 60);
        String[] names = {"terminal", "secure", "safe", "ledger"};
        int[] xs = {1, 4, 7, 10};
        for (int i = 0; i < names.length; i++) {
            final int bx = xs[i];
            shot("block-" + names[i] + "-front", at(origin, bx + 0.5, 1.5, 19.0), at(origin, bx + 0.5, 0.55, 16.5));
            shot("block-" + names[i] + "-angle", at(origin, bx + 2.2, 1.8, 18.2), at(origin, bx + 0.5, 0.5, 16.5));
        }
        shot("blocks-row", at(origin, 5.5, 2.4, 22.0), at(origin, 5.5, 0.5, 16.5));
        Playtest.queue("a row of crates as a counter: the safe and terminal in a shop-like setting", () -> {
            int ox = origin[0].getX(), oy = origin[0].getY(), oz = origin[0].getZ();
            Playtest.command(String.format("fill %d %d %d %d %d %d minecraft:oak_planks", ox + 14, oy, oz + 14, ox + 14, oy, oz + 18));
        });

        // Every Core item as the player sees it: the survival inventory, then a hotbar close-up.
        Playtest.queue("survival, GUI back on, the first half of the items", () -> {
            Playtest.command("gamerule sendCommandFeedback false");
            Playtest.command("gamemode survival");
            Playtest.command("clear @s");
            Minecraft.getInstance().options.hideGui = false;
            giveItems(0, 27);
        });
        Playtest.wait("items arrive", 10);
        Playtest.queue("open the inventory", () -> { Playtest.focusWindow(); Playtest.tap("e"); });
        Playtest.wait("inventory draws", 10);
        Playtest.queue("photograph the first page of items", () -> Playtest.screenshotNextFrame("uc-gallery-items-1"));
        Playtest.wait("shot", 6);
        Playtest.queue("close, the second half", () -> {
            Playtest.tap("e");
            Playtest.command("clear @s");
            giveItems(27, 60);
        });
        Playtest.wait("items arrive", 10);
        Playtest.queue("open the inventory again", () -> { Playtest.focusWindow(); Playtest.tap("e"); });
        Playtest.wait("inventory draws", 10);
        Playtest.queue("photograph the second page of items", () -> Playtest.screenshotNextFrame("uc-gallery-items-2"));
        Playtest.wait("shot", 6);
        Playtest.queue("close the inventory", () -> Playtest.tap("e"));
    }

    /**
     * Candidate-mod showroom ({@code -Duc.playtest=modgallery}, with {@code -PucPack=...,vintner,farming}): the crops, vines, presses, barrels
     * and stills of the third-party mods under evaluation, photographed the way {@link #build} photographs ours. It proves nothing about
     * gameplay; it exists so that "does this look good enough to adopt" is answered from the real renderer.
     */
    static void mods() {
        final BlockPos[] origin = new BlockPos[1];
        Playtest.queue("spectator camera, noon, no HUD, no random ticks and a platform in the sky", () -> {
            var player = Minecraft.getInstance().player;
            origin[0] = new BlockPos(player.getBlockX() + 8, 220, player.getBlockZ() + 8);
            Playtest.command("gamemode spectator");
            Playtest.command("time set 6000");
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("gamerule randomTickSpeed 0");
            Playtest.command("weather clear");
            Playtest.command("tp @s " + origin[0].getX() + " " + (origin[0].getY() + 2) + " " + origin[0].getZ());
            Minecraft.getInstance().options.hideGui = true;
        });
        Playtest.wait("let the chunks load", 120);
        // crop name, ages (inclusive), extra state
        String[][] crops = {
            {"farm_and_charm:barley_crop", "4", ""}, {"farm_and_charm:corn_crop", "4", ""}, {"farm_and_charm:oat_crop", "5", ""},
            {"farm_and_charm:tomato_crop", "4", ""}, {"farm_and_charm:strawberry_crop", "5", ""}, {"farm_and_charm:lettuce_crop", "3", ",big=false,giant=false"},
            {"farm_and_charm:onion_crop", "3", ",big=false,giant=false"},
            {"vinery:red_grape_bush", "3", ""}, {"vinery:white_grape_bush", "3", ""},
        };
        Playtest.queue("lay the ground, the water and every crop at every age", () -> {
            int ox = origin[0].getX(), oy = origin[0].getY(), oz = origin[0].getZ();
            Playtest.command(String.format("fill %d %d %d %d %d %d minecraft:grass_block", ox - 6, oy - 1, oz - 6, ox + 24, oy - 1, oz + 56));
            Playtest.command(String.format("fill %d %d %d %d %d %d minecraft:air", ox - 6, oy, oz - 6, ox + 24, oy + 12, oz + 56));
            for (int row = 0; row < crops.length; row++) {
                int z = oz + row * 3;
                Playtest.command(String.format("setblock %d %d %d minecraft:water", ox - 1, oy - 1, z));
                int max = Integer.parseInt(crops[row][1]);
                for (int age = 0; age <= max; age++) {
                    Playtest.command(String.format("setblock %d %d %d minecraft:farmland[moisture=7]", ox + age * 2, oy - 1, z));
                    Playtest.command(String.format("setblock %d %d %d %s[age=%d%s]", ox + age * 2, oy, z, crops[row][0], age, crops[row][2]));
                }
            }
        });
        Playtest.wait("let the meshes build", 160);
        Playtest.queue("log where the showroom stands", () -> Playtest.log("modgallery origin " + origin[0].getX() + " " + origin[0].getY() + " " + origin[0].getZ()));
        for (int row = 0; row < crops.length; row++) {
            final int r = row;
            String name = crops[r][0].replace(':', '-');
            shot("mod-" + name + "-row", at(origin, 5.0, 2.4, r * 3 + 4.5), at(origin, 5.0, 0.7, r * 3 + 0.5));
            shot("mod-" + name + "-ripe", at(origin, Integer.parseInt(crops[r][1]) * 2 + 0.5, 1.7, r * 3 + 2.0), at(origin, Integer.parseInt(crops[r][1]) * 2 + 0.5, 0.8, r * 3 + 0.5));
        }

        // The machines, on a stone strip, each facing the camera (south): cooking, pressing, fermenting, distilling.
        String[][] machines = {
            {"farm_and_charm:stove[facing=south,lit=false]", "farm_and_charm:cooking_pot[facing=south,lit=false,cooking=false,needs_support=false]", "farm_and_charm:mincer", "farm_and_charm:roaster[facing=south,lit=false,roasting=false,hanging=false]", "farm_and_charm:scarecrow[facing=south]", "farm_and_charm:crafting_bowl"},
            {"vinery:apple_press[facing=south,half=lower]", "vinery:fermentation_barrel[facing=south]", "vinery:oak_wine_rack_big[facing=south]", "vinery:basket", "vinery:wine_box[facing=south,open=false]", "vinery:storage_pot[facing=south,open=false]", "vinery:dark_cherry_barrel[facing=south,open=false]"},
            {"kaleidoscope_tavern:barrel", "kaleidoscope_tavern:pressing_tub", "kaleidoscope_tavern:tap", "kaleidoscope_tavern:shaker", "kaleidoscope_tavern:vodka", "kaleidoscope_tavern:whiskey", "kaleidoscope_tavern:rum", "kaleidoscope_tavern:brandy", "kaleidoscope_tavern:wine"},
        };
        Playtest.queue("lay the machine showroom", () -> {
            int ox = origin[0].getX(), oy = origin[0].getY(), oz = origin[0].getZ();
            for (int row = 0; row < machines.length; row++) {
                int z = oz + 40 + row * 6;
                Playtest.command(String.format("fill %d %d %d %d %d %d minecraft:smooth_stone", ox, oy - 1, z - 1, ox + 18, oy - 1, z + 1));
                for (int i = 0; i < machines[row].length; i++) {
                    Playtest.command(String.format("setblock %d %d %d %s", ox + i * 2 + 1, oy, z, machines[row][i]));
                }
            }
        });
        Playtest.wait("let the machine meshes build", 80);
        for (int row = 0; row < machines.length; row++) {
            final int r = row;
            String[] names = {"farm", "vinery", "kaleidoscope"};
            shot("mod-machines-" + names[r] + "-wide", at(origin, 7.5, 2.6, 40 + r * 6 + 6.0), at(origin, 7.5, 0.6, 40 + r * 6 + 0.5));
            for (int i = 0; i < machines[r].length; i++) {
                final int idx = i;
                String id = machines[r][i].replaceAll("\\[.*", "").replace(':', '-');
                shot("mod-block-" + id + "-front", at(origin, idx * 2 + 1.5, 1.7, 40 + r * 6 + 3.2), at(origin, idx * 2 + 1.5, 0.7, 40 + r * 6 + 0.5));
                shot("mod-block-" + id + "-angle", at(origin, idx * 2 + 3.0, 1.9, 40 + r * 6 + 2.6), at(origin, idx * 2 + 1.5, 0.7, 40 + r * 6 + 0.5));
            }
        }
    }

    /** Gives the Core items [from, to) of the registration order, one stack each. */
    private static void giveItems(int from, int to) {
        java.util.List<CoreItems.Definition> all = CoreItems.definitions();
        for (int i = from; i < Math.min(to, all.size()); i++) Playtest.command("give @s underground_city:" + all.get(i).id() + " 1");
    }

    private static java.util.function.Supplier<Vec3> at(BlockPos[] origin, double dx, double dy, double dz) {
        return () -> new Vec3(origin[0].getX() + dx, origin[0].getY() + dy, origin[0].getZ() + dz);
    }
}
