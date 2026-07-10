package com.aiquantresearch.api.research.provider;

public interface FilingProvider {

    FilingSnapshot fetch(String symbol);
}
