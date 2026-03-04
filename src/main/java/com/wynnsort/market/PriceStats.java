package com.wynnsort.market;

public record PriceStats(long min, long max, long avg, long median, int count, PriceTrend trend, long latestPrice) {}
