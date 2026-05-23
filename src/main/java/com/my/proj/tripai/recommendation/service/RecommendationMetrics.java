package com.my.proj.tripai.recommendation.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class RecommendationMetrics {

    private final MeterRegistry meterRegistry;

    void recordCacheLookup(RecommendationCacheType cacheType, boolean hit) {
        meterRegistry.counter(
                "tripai.recommendation.cache.lookup.total",
                "cache_type", cacheType.name().toLowerCase(),
                "result", hit ? "hit" : "miss"
        ).increment();
    }

    void recordCacheReuse(RecommendationCacheType cacheType) {
        meterRegistry.counter(
                "tripai.recommendation.cache.reuse.total",
                "source", cacheType.name().toLowerCase()
        ).increment();
    }

    void recordCachePopulate(RecommendationCacheType cacheType) {
        meterRegistry.counter(
                "tripai.recommendation.cache.populate.total",
                "cache_type", cacheType.name().toLowerCase()
        ).increment();
    }

    <T> T recordAiRequest(Callable<T> callable) {
        meterRegistry.counter("tripai.recommendation.ai.request.total").increment();
        Timer timer = meterRegistry.timer("tripai.recommendation.ai.request.duration");
        try {
            return timer.recordCallable(callable);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to record AI request metrics.", exception);
        }
    }
}
