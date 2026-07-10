package com.aiquantresearch.api.research.provider;

public interface MarketDataProvider {

    MarketDataSnapshot fetchFiveYearDaily(String symbol);
}
