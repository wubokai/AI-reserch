package com.aiquantresearch.api.research.provider;

public interface FundamentalDataProvider {

    FundamentalDataSnapshot fetch(String symbol);
}
