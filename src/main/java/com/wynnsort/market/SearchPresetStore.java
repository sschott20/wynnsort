package com.wynnsort.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.wynnsort.WynnSortMod;
import com.wynnsort.util.DiagnosticLog;
import com.wynnsort.util.FeatureLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class SearchPresetStore {

    private static final FeatureLogger LOG = new FeatureLogger("Preset", DiagnosticLog.Category.PERSISTENCE);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORE_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("wynnsort").resolve("search_presets.json");
    private static final Type LIST_TYPE = new TypeToken<ArrayList<SearchPreset>>() {}.getType();
    private static final int MAX_PRESETS = 10;

    private static final CopyOnWriteArrayList<SearchPreset> presets = new CopyOnWriteArrayList<>();

    public static void load() {
        if (Files.exists(STORE_PATH)) {
            try (Reader reader = Files.newBufferedReader(STORE_PATH)) {
                List<SearchPreset> loaded = GSON.fromJson(reader, LIST_TYPE);
                if (loaded != null) {
                    presets.clear();
                    presets.addAll(loaded);
                    LOG.info("Loaded {} search presets from {}", presets.size(), STORE_PATH);
                    LOG.event("store_loaded", Map.of("count", presets.size()));
                }
            } catch (JsonSyntaxException e) {
                LOG.warn("Corrupt JSON in search presets file, starting fresh: {}", e.getMessage());
                presets.clear();
            } catch (IOException e) {
                LOG.error("Failed to load search presets", e);
            }
        }
    }

    public static void save() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(STORE_PATH)) {
                GSON.toJson(presets, LIST_TYPE, writer);
            }
            LOG.info("Saved {} search presets to {}", presets.size(), STORE_PATH);
            LOG.event("store_saved", Map.of("count", presets.size()));
        } catch (IOException e) {
            LOG.error("Failed to save search presets", e);
        }
    }

    public static List<SearchPreset> getPresets() {
        return Collections.unmodifiableList(new ArrayList<>(presets));
    }

    public static void addPreset(SearchPreset preset) {
        if (presets.size() >= MAX_PRESETS) {
            LOG.warn("Cannot add preset: maximum of {} presets reached", MAX_PRESETS);
            return;
        }
        presets.add(preset);
        save();
    }

    /**
     * Sets a preset at a specific index, replacing if it exists or adding if the
     * index equals the current size (up to MAX_PRESETS).
     */
    public static void setPreset(int index, SearchPreset preset) {
        if (index < 0 || index >= MAX_PRESETS) return;

        // Expand list with nulls if needed
        while (presets.size() <= index) {
            presets.add(null);
        }
        presets.set(index, preset);
        save();
    }

    public static void removePreset(int index) {
        if (index < 0 || index >= presets.size()) return;
        presets.remove(index);
        save();
    }

    public static SearchPreset getPreset(int index) {
        if (index < 0 || index >= presets.size()) return null;
        return presets.get(index);
    }

    public static int size() {
        return presets.size();
    }
}
