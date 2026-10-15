package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Original commodity/product/tool item IDs, generated into {@code underground_city_items.json} by
 * scripts/generate_regional_content.py. They are plain items; gameplay lives in datapack data
 * (regional stock, recipes, work orders) so compatible mod items can replace them later.
 */
public final class CoreItems {
    public record Definition(String id, String kind, int stack) {}
    private static final String RESOURCE = "/underground_city_items.json";
    private CoreItems() {}

    public static List<Definition> definitions() {
        try (var stream = CoreItems.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing generated item list " + RESOURCE);
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schema_version").getAsInt() != 1) throw new IllegalStateException("Unsupported item list schema");
            List<Definition> result = new ArrayList<>();
            for (var element : root.getAsJsonArray("items")) {
                var entry = element.getAsJsonObject();
                Definition definition = new Definition(entry.get("id").getAsString(), entry.get("kind").getAsString(), entry.get("stack").getAsInt());
                if (!definition.id().matches("[a-z0-9_]{1,48}") || definition.stack() < 1 || definition.stack() > 64) {
                    throw new IllegalStateException("Invalid generated item " + definition);
                }
                result.add(definition);
            }
            return List.copyOf(result);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read generated item list", error);
        }
    }
}
