package com.aiquantresearch.api.research.worker;

public interface ResearchStepProcessor {

    void process(QueueClaim claim);
}
