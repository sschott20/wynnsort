package com.wynnsort.market;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.wynnsort.WynnSortMod;
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
import java.util.concurrent.CopyOnWriteArrayList;

public class SearchPresetStore {

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
                    WynnSortMod.log("Loaded {} search presets from {}", presets.size(), STORE_PATH);
                }
            } catch (IOException e) {
                WynnSortMod.logError("Failed to load search presets", e);
            }
        }
    }

    public static void save() {
        try {
            Files.createDirectories(STORE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(STORE_PATH)) {
                GSON.toJson(presets, LIST_TYPE, writer);
            }
            WynnSortMod.log("Saved {} search presets to {}", presets.size(), STORE_PATH);
        } catch (IOException e) {
            WynnSortMod.logError("Failed to save search presets", e);
        }
    }

    public static List<SearchPreset> getPresets() {
        return Collections.unmodifiableList(new ArrayList<>(presets));
    }

    public static void addPreset(SearchPreset preset) {
        if (presets.size() >= MAX_PRESETS) {
            WynnSortMod.logWarn("Cannot add preset: maximum of {} presets reached", MAX_PRESETS);
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
