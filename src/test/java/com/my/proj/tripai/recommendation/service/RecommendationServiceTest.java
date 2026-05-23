package com.my.proj.tripai.recommendation.service;

import com.my.proj.tripai.recommendation.domain.Recommendation;
import com.my.proj.tripai.recommendation.domain.RecommendationCache;
import com.my.proj.tripai.recommendation.dto.RecommendationCreateRequest;
import com.my.proj.tripai.recommendation.dto.RecommendationResponse;
import com.my.proj.tripai.recommendation.repository.RecommendationCacheRepository;
import com.my.proj.tripai.recommendation.repository.RecommendationRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    private static final String LONG_REASON = "공주는 한적한 산책길과 무난한 이동 동선이 있어 힐링 여행에 잘 맞고 부모님과 함께 가기에도 편안한 여행지이며 계절 풍경도 좋아 여유롭게 머물기 좋습니다.";

    @Mock
    private RecommendationRepository recommendationRepository;

    @Mock
    private RecommendationCacheRepository recommendationCacheRepository;

    @Mock
    private RecommendationAiClient recommendationAiClient;

    @Mock
    private RecommendationMetrics recommendationMetrics;

    @InjectMocks
    private RecommendationService recommendationService;

    @Test
    void createRecommendationBuildsPromptSummaryInService() {
        RecommendationCreateRequest request = new RecommendationCreateRequest(
                "가족",
                "중간",
                "힐링",
                "가을",
                "부모님 모시고 조용하고 걷기 편한 국내 여행지 추천해줘"
        );
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TEXT.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTextKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.empty());
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TAG.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTagKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.empty());
        given(recommendationAiClient.generateRecommendation(request))
                .willReturn(new RecommendationDraft("공주", "공주는 힐링 여행에 잘 맞는 추천지입니다."));
        given(recommendationMetrics.recordAiRequest(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> ((java.util.concurrent.Callable<RecommendationDraft>) invocation.getArgument(0)).call());
        given(recommendationCacheRepository.save(org.mockito.ArgumentMatchers.any(RecommendationCache.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(recommendationRepository.save(org.mockito.ArgumentMatchers.any(Recommendation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RecommendationResponse response = recommendationService.createRecommendation(request);

        ArgumentCaptor<Recommendation> recommendationCaptor = ArgumentCaptor.forClass(Recommendation.class);
        verify(recommendationRepository).save(recommendationCaptor.capture());

        Recommendation savedRecommendation = recommendationCaptor.getValue();
        assertThat(savedRecommendation.getPromptSummary())
                .isEqualTo("가족와 함께 중간 예산으로 힐링 여행을 가을에 가고 싶어 함. 추가 요청사항은 \"부모님 모시고 조용하고 걷기 편한 국내 여행지 추천해줘\"입니다.");
        assertThat(response.promptSummary())
                .isEqualTo(savedRecommendation.getPromptSummary());
    }

    @Test
    void createRecommendationNormalizesReasonToShortText() {
        RecommendationCreateRequest request = new RecommendationCreateRequest(
                "가족",
                "중간",
                "힐링",
                "가을",
                "부모님 모시고 조용하고 걷기 편한 국내 여행지 추천해줘"
        );
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TEXT.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTextKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.empty());
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TAG.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTagKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.empty());
        given(recommendationAiClient.generateRecommendation(request))
                .willReturn(new RecommendationDraft("공주", LONG_REASON));
        given(recommendationMetrics.recordAiRequest(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> ((java.util.concurrent.Callable<RecommendationDraft>) invocation.getArgument(0)).call());
        given(recommendationCacheRepository.save(org.mockito.ArgumentMatchers.any(RecommendationCache.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(recommendationRepository.save(org.mockito.ArgumentMatchers.any(Recommendation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RecommendationResponse response = recommendationService.createRecommendation(request);

        assertThat(response.reason()).hasSizeLessThanOrEqualTo(80);
        assertThat(response.reason()).isEqualTo(LONG_REASON.substring(0, 80).trim());
    }

    @Test
    void createRecommendationFallsBackToTemplateWhenReasonIsBlank() {
        RecommendationCreateRequest request = new RecommendationCreateRequest(
                "가족",
                "중간",
                "힐링",
                "가을",
                "부모님 모시고 조용하고 걷기 편한 국내 여행지 추천해줘"
        );
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TEXT.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTextKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.empty());
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TAG.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTagKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.empty());
        given(recommendationAiClient.generateRecommendation(request))
                .willReturn(new RecommendationDraft("공주", " "));
        given(recommendationMetrics.recordAiRequest(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> ((java.util.concurrent.Callable<RecommendationDraft>) invocation.getArgument(0)).call());
        given(recommendationCacheRepository.save(org.mockito.ArgumentMatchers.any(RecommendationCache.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(recommendationRepository.save(org.mockito.ArgumentMatchers.any(Recommendation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RecommendationResponse response = recommendationService.createRecommendation(request);

        assertThat(response.reason()).isEqualTo("공주는 힐링 여행에 잘 어울리는 곳입니다.");
    }

    @Test
    void createRecommendationReusesTextCacheBeforeTagCache() {
        RecommendationCreateRequest request = new RecommendationCreateRequest(
                " 가족 ",
                "중간",
                "힐링",
                "가을",
                "부모님  모시고\n조용한 곳 추천"
        );
        RecommendationCache cachedRecommendation = RecommendationCache.builder()
                .cacheType(RecommendationCacheType.TEXT.name())
                .cacheKey(RecommendationRequestCacheKeyGenerator.generateTextKey(
                        new RecommendationCreateRequest("가족", "중간", "힐링", "가을", "부모님 모시고 조용한 곳 추천")))
                .destination("공주")
                .reason("공주는 힐링 여행에 잘 어울리는 곳입니다.")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .createdAt(LocalDateTime.now())
                .build();
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TEXT.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTextKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.of(cachedRecommendation));
        given(recommendationRepository.save(org.mockito.ArgumentMatchers.any(Recommendation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RecommendationResponse response = recommendationService.createRecommendation(request);

        assertThat(response.destination()).isEqualTo("공주");
        assertThat(response.reason()).isEqualTo("공주는 힐링 여행에 잘 어울리는 곳입니다.");
        verify(recommendationAiClient, never()).generateRecommendation(request);
        verify(recommendationRepository).save(org.mockito.ArgumentMatchers.any(Recommendation.class));
        verify(recommendationCacheRepository, never()).save(org.mockito.ArgumentMatchers.any(RecommendationCache.class));
        verify(recommendationMetrics).recordCacheReuse(RecommendationCacheType.TEXT);
    }

    @Test
    void createRecommendationReusesTagCacheWhenTextCacheMisses() {
        RecommendationCreateRequest request = new RecommendationCreateRequest(
                "가족",
                "중간",
                "힐링",
                "가을",
                "부모님 모시고 조용히 걷기 좋은 곳 추천"
        );
        RecommendationCache cachedRecommendation = RecommendationCache.builder()
                .cacheType(RecommendationCacheType.TAG.name())
                .cacheKey(RecommendationRequestCacheKeyGenerator.generateTagKey(request))
                .destination("공주")
                .reason("공주는 힐링 여행에 잘 어울리는 곳입니다.")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .createdAt(LocalDateTime.now())
                .build();
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TEXT.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTextKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.empty());
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TAG.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTagKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.of(cachedRecommendation));
        given(recommendationRepository.save(org.mockito.ArgumentMatchers.any(Recommendation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RecommendationResponse response = recommendationService.createRecommendation(request);

        assertThat(response.destination()).isEqualTo("공주");
        verify(recommendationAiClient, never()).generateRecommendation(request);
        verify(recommendationRepository).save(org.mockito.ArgumentMatchers.any(Recommendation.class));
        verify(recommendationCacheRepository, never()).save(org.mockito.ArgumentMatchers.any(RecommendationCache.class));
        verify(recommendationMetrics).recordCacheReuse(RecommendationCacheType.TAG);
    }

    @Test
    void createRecommendationCachesBothTextAndTagKeysWhenAiIsCalled() {
        RecommendationCreateRequest request = new RecommendationCreateRequest(
                "가족",
                "중간",
                "힐링",
                "가을",
                "부모님 모시고 조용한 곳 추천"
        );
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TEXT.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTextKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.empty());
        given(recommendationCacheRepository.findTopByCacheTypeAndCacheKeyAndExpiresAtAfterOrderByCreatedAtDesc(
                org.mockito.ArgumentMatchers.eq(RecommendationCacheType.TAG.name()),
                org.mockito.ArgumentMatchers.eq(RecommendationRequestCacheKeyGenerator.generateTagKey(request)),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .willReturn(java.util.Optional.empty());
        given(recommendationAiClient.generateRecommendation(request))
                .willReturn(new RecommendationDraft("공주", "공주는 힐링 여행에 잘 맞는 추천지입니다."));
        given(recommendationMetrics.recordAiRequest(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> ((java.util.concurrent.Callable<RecommendationDraft>) invocation.getArgument(0)).call());
        given(recommendationCacheRepository.save(org.mockito.ArgumentMatchers.any(RecommendationCache.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(recommendationRepository.save(org.mockito.ArgumentMatchers.any(Recommendation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RecommendationResponse response = recommendationService.createRecommendation(request);

        assertThat(response.destination()).isEqualTo("공주");
        verify(recommendationAiClient).generateRecommendation(request);
        ArgumentCaptor<RecommendationCache> cacheCaptor = ArgumentCaptor.forClass(RecommendationCache.class);
        verify(recommendationCacheRepository, times(2)).save(cacheCaptor.capture());
        List<RecommendationCache> savedCaches = cacheCaptor.getAllValues();
        assertThat(savedCaches).extracting(RecommendationCache::getCacheType)
                .containsExactlyInAnyOrder(
                        RecommendationCacheType.TEXT.name(),
                        RecommendationCacheType.TAG.name()
                );
        verify(recommendationRepository).save(org.mockito.ArgumentMatchers.any(Recommendation.class));
    }
}
