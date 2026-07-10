package com.aiquantresearch.api.research.application;

public record CommandResult<T>(T value, boolean idempotencyReplayed) {
}
