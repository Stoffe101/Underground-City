package io.github.stoffe101.undergroundcity;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The player's own choices for the on-screen status (client config file {@code underground_city-client.toml}; nothing here can change a rule, a price or a balance). The settings screen
 * ({@link HudSettingsScreen}, from the mod list or the HUD settings key) writes these values; a hand-edited file works as well.
 */
final class HudConfig {
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue ENABLED, LOCATION, CLOCK, WALLET, CREW, HOME, CAREER, HEAT, GUIDANCE;
    static final ForgeConfigSpec.EnumValue<HudLayout.Anchor> ANCHOR;
    static final ForgeConfigSpec.DoubleValue SCALE, OPACITY;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("The persistent status on the screen: where you are, the time, your wallet and Crew bank, your home and its rent, your trade and how closely you are watched.").push("hud");
        ENABLED = b.comment("Show the status.").define("enabled", true);
        ANCHOR = b.comment("Where the status and the order cards stand. The top right is below the status effect icons; Xaero's minimap is at the top left unless you move it.").defineEnum("anchor", HudLayout.Anchor.TOP_RIGHT);
        SCALE = b.comment("Size, 0.6 to 1.5. A small window or a large GUI scale shrinks the cards further, and drops the least important, rather than overlap anything.").defineInRange("scale", 1.0, 0.6, 1.5);
        OPACITY = b.comment("How solid the cards are, 0.3 to 1.").defineInRange("opacity", 0.9, 0.3, 1.0);
        LOCATION = b.comment("The city or road you are in.").define("show_location", true);
        CLOCK = b.comment("The time of day.").define("show_clock", true);
        WALLET = b.comment("Your credits.").define("show_wallet", true);
        CREW = b.comment("Your Crew's bank, when you have a Crew.").define("show_crew_bank", true);
        HOME = b.comment("Your home, its rent and when the next payment is due.").define("show_home", true);
        CAREER = b.comment("Your trade, level and progress.").define("show_career", true);
        HEAT = b.comment("How closely the city is watching you, when it is at all.").define("show_heat", true);
        GUIDANCE = b.comment("The accepted order and the next step of the guided first hours, in the same stack.").define("show_guidance", true);
        b.pop();
        SPEC = b.build();
    }

    private HudConfig() {}

    /** The player's choices as plain values: what the overlay and the settings screen read, so that a screen (or a capture) never depends on when the config file was last reloaded. */
    static final class Settings {
        boolean enabled = true, location = true, clock = true, wallet = true, crew = true, home = true, career = true, heat = true, guidance = true;
        HudLayout.Anchor anchor = HudLayout.Anchor.TOP_RIGHT;
        double scale = 1.0, opacity = 0.9;

        static Settings fromConfig() {
            Settings s = new Settings();
            s.enabled = ENABLED.get(); s.location = LOCATION.get(); s.clock = CLOCK.get(); s.wallet = WALLET.get(); s.crew = CREW.get(); s.home = HOME.get(); s.career = CAREER.get(); s.heat = HEAT.get();
            s.guidance = GUIDANCE.get(); s.anchor = ANCHOR.get(); s.scale = SCALE.get(); s.opacity = OPACITY.get();
            return s;
        }

        /** Writes the choices to the config file. */
        void store() {
            ENABLED.set(enabled); LOCATION.set(location); CLOCK.set(clock); WALLET.set(wallet); CREW.set(crew); HOME.set(home); CAREER.set(career); HEAT.set(heat); GUIDANCE.set(guidance);
            ANCHOR.set(anchor); SCALE.set(scale); OPACITY.set(opacity);
            SPEC.save();
        }

        Settings copy() {
            Settings c = new Settings();
            c.enabled = enabled; c.location = location; c.clock = clock; c.wallet = wallet; c.crew = crew; c.home = home; c.career = career; c.heat = heat; c.guidance = guidance;
            c.anchor = anchor; c.scale = scale; c.opacity = opacity;
            return c;
        }
    }
}
