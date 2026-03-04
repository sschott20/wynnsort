package com.wynnsort.market;

public class SearchPreset {
    public String name;      // user-given name, e.g. "Mythic Helmets"
    public String query;     // the search query text
    public String sortToken; // optional sort:X token

    public SearchPreset() {}

    public SearchPreset(String name, String query, String sortToken) {
        this.name = name;
        this.query = query;
        this.sortToken = sortToken;
    }
}
