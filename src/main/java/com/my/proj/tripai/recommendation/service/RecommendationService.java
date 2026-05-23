package com.my.proj.tripai.recommendation.service;

import com.my.proj.tripai.recommendation.domain.Recommendation;
import com.my.proj.tripai.recommendation.domain.RecommendationCache;
import com.my.proj.tripai.recommendation.dto.RecommendationCreateRequest;
import com.my.proj.tripai.recommendation.dto.RecommendationResponse;
import com.my.proj.tripai.recommendation.exception.RecommendationNotFoundException;
import com.my.proj.tripai.recommendation.repository.RecommendationCacheRepository;
import com.my.proj.tripai.recommendation.repository.RecommendationRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private static final int MAX_REASON_LENGTH = 80;
    private static final long CACHE_TTL_DAYS = 1L;

    private final RecommendationRepository recommendationRepository;
    private final RecommendationCacheRepository recommendationCacheRepository;
    private final RecommendationAiClient recommendationAiClient;
    private final RecommendationMetrics recommendationMetrics;

    @Transactional
    public RecommendationResponse createRecommendation(RecommendationCreateRequest request) {
        // 먼저 더 정확한 원문 기반 캐시를 조회하고, 없을 때만 태그 기반 캐시로 넓게 본다.
        RecommendationCache cachedRecommendation = findCachedRecommendation(
                RecommendationCacheType.TEXT,
                RecommendationRequestCacheKeyGenerator.generateTextKey(request)
        );
        if (cachedRecommendation == null) {
            cachedRecommendation = findCachedRecommendation(
                    RecommendationCacheType.TAG,
                    RecommendationRequestCacheKeyGenerator.generateTagKey(request)
            );
        }
        if (cachedRecommendation != null) {
            recommendationMetrics.recordCacheReuse(RecommendationCacheType.valueOf(cachedRecommendation.getCacheType()));
            return saveRecommendation(request, cachedRecommendation.getDestination(), cachedRecommendation.getReason());
        }

        RecommendationDraft draft = recommendationMetrics.recordAiRequest(
                () -> recommendationAiClient.generateRecommendation(request)
        );
        String normalizedReason = buildReason(request, draft);
        // 새 응답은 두 캐시에 모두 저장해 이후에는 원문/태그 어느 쪽으로도 재사용 가능하게 둔다.
        cacheRecommendation(
                RecommendationCacheType.TEXT,
                RecommendationRequestCacheKeyGenerator.generateTextKey(request),
                draft.destination(),
                normalizedReason
        );
        cacheRecommendation(
                RecommendationCacheType.TAG,
                RecommendationRequestCacheKeyGenerator.generateTagKey(request),
                draft.destination(),
                normalizedReason
        );
        return saveRecommendation(request, draft.destination(), normalizedReason);
    }

    public List<RecommendationResponse> getRecommendations() {
        return recommendationRepository.findAll().stream()
                .map(RecommendationResponse::from)
                .toList();
    }

    public RecommendationResponse getRecommendation(Long id) {
        return recommendationRepository.findById(id)
                .map(RecommendationResponse::from)
                .orElseThrow(() -> new RecommendationNotFoundException(id));
    }

    private RecommendationResponse saveRecommendation(
            RecommendationCreateRequest request,
            String destination,
            String reason
    ) {
        // 캐시 hit 여부와 무관하게 사용자 요청 이력은 recommendation 테이블에 남긴다.
        Recommendation recommendation = Recommendation.builder()
                .destination(destination)
                .companionType(request.companionType())
                .budgetLevel(request.budgetLevel())
                .travelStyle(request.travelStyle())
                .season(request.season())
                .userPrompt(request.userPrompt())
                .promptSummary(buildPromptSummary(request))
                .reason(reason)
                .createdAt(LocalDateTime.now())
                .build();

        return RecommendationResponse.from(recommendationRepository.save(recommendation));
    }

    // 캐시 테이블은 TTL이 남아 있는 최신 스냅샷 한 건만 재사용한다.
    private RecommendationCache findCachedRecommendation(RecommendationCacheType cacheType, String cacheKey) {
        RecommendationCache recommendationCache = recommendationCacheRepository
                .findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                        cacheType.name(),
                        cacheKey,
                        LocalDateTime.now()
                )
                .orElse(null);
        recommendationMetrics.recordCacheLookup(cacheType, recommendationCache != null);
        logTagCacheLookup(cacheType, cacheKey, recommendationCache);
        return recommendationCache;
    }

    private void cacheRecommendation(
            RecommendationCacheType cacheType,
            String cacheKey,
            String destination,
            String reason
    ) {
        LocalDateTime now = LocalDateTime.now();
        RecommendationCache recommendationCache = RecommendationCache.builder()
                .cacheType(cacheType.name())
                .cacheKey(cacheKey)
                .destination(destination)
                .reason(reason)
                .expiresAt(now.plusDays(CACHE_TTL_DAYS))
                .createdAt(now)
                .build();
        recommendationCacheRepository.save(recommendationCache);
        recommendationMetrics.recordCachePopulate(cacheType);
    }

    private void logTagCacheLookup(
            RecommendationCacheType cacheType,
            String cacheKey,
            RecommendationCache recommendationCache
    ) {
        if (cacheType != RecommendationCacheType.TAG) {
            return;
        }

        if (recommendationCache == null) {
            log.info("tag-cache miss key={}", cacheKey);
            return;
        }

        log.info(
                "tag-cache hit key={} destination={} cacheCreatedAt={}",
                cacheKey,
                recommendationCache.getDestination(),
                recommendationCache.getCreatedAt()
        );
    }

    // promptSummary는 AI 응답이 아니라 서버 규칙으로 생성해 토큰 사용량을 줄인다.
    private String buildPromptSummary(RecommendationCreateRequest request) {
        return "%s와 함께 %s 예산으로 %s 여행을 %s에 가고 싶어 함. 추가 요청사항은 \"%s\"입니다."
                .formatted(
                        normalizeOptionalValue(request.companionType()),
                        normalizeOptionalValue(request.budgetLevel()),
                        normalizeOptionalValue(request.travelStyle()),
                        normalizeOptionalValue(request.season()),
                        request.userPrompt().trim()
                );
    }

    // AI가 비거나 너무 긴 reason을 주더라도 화면에는 짧고 안정적인 문구만 남긴다.
    private String buildReason(RecommendationCreateRequest request, RecommendationDraft draft) {
        String normalizedReason = normalizeReason(draft.reason());
        if (StringUtils.hasText(normalizedReason)) {
            return normalizedReason;
        }

        return "%s는 %s 여행에 잘 어울리는 곳입니다."
                .formatted(
                        draft.destination(),
                        normalizeOptionalValue(request.travelStyle())
                );
    }

    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "";
        }

        String normalized = reason.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= MAX_REASON_LENGTH) {
            return normalized;
        }

        return normalized.substring(0, MAX_REASON_LENGTH).trim();
    }

    private String normalizeOptionalValue(String value) {
        return StringUtils.hasText(value) ? value.trim() : "미입력";
    }
}
