# TripAI

TripAI는 사용자의 여행 조건을 바탕으로 여행지를 추천해주는 백엔드 프로젝트입니다.
OpenAI API를 활용해 추천 결과와 추천 이유를 생성합니다.



## MVP

- 사용자가 자유 텍스트와 선택형 조건으로 여행 요청을 입력한다.
- 서버가 조건에 맞는 여행지 1곳과 추천 이유를 생성한다.
- 생성된 추천 결과를 DB에 저장한다.
- 저장된 추천 결과를 목록/단건으로 조회한다.

입력 예시는 아래와 같습니다.

- 사용자 텍스트 입력
- 동행 유형 (선택)
- 예산 수준 (선택)
- 여행 스타일 (선택)
- 여행 계절 (선택)

## Tech Stack

- Java 17
- Spring Boot 3.5
- Spring Web
- Spring Data JPA
- H2 Database
- Spring Validation
- Spring AI
- Swagger UI (`springdoc-openapi`)
- Lombok

## Features

- `POST /api/recommendations`
  - 자유 텍스트 요청과 선택형 조건을 받아 추천 결과를 생성하고 저장합니다.
- `GET /api/recommendations`
  - 저장된 추천 결과 목록을 조회합니다.
- `GET /api/recommendations/{id}`
  - 저장된 추천 결과 단건을 조회합니다.
- `/`
  - Thymeleaf 기반 입력 페이지에서 추천 생성 흐름을 테스트 할 수 있습니다.

## AI Provider Mode

이 프로젝트는 추천 생성 로직을 인터페이스로 분리해두었습니다.

- `mock`
  - 기본값입니다.
  - 실제 OpenAI를 호출하지 않고, 미리 정한 규칙으로 추천 결과를 반환합니다.
  - 결제 전, 프론트 연동 전, API 설계 단계에서 빠르게 개발하기 좋습니다.
- `openai`
  - Spring AI를 통해 OpenAI API를 실제 호출합니다.

관련 코드는 아래에 있습니다.

- [RecommendationAiClient.java](/src/main/java/com/my/proj/tripai/recommendation/service/RecommendationAiClient.java)
- [MockRecommendationAiClient.java](/src/main/java/com/my/proj/tripai/recommendation/service/MockRecommendationAiClient.java)
- [OpenAiRecommendationClient.java](/src/main/java/com/my/proj/tripai/recommendation/service/OpenAiRecommendationClient.java)

## Project Structure

```text
src/main/java/com/my/proj/tripai
├── recommendation
│   ├── controller
│   ├── domain
│   ├── dto
│   ├── repository
│   └── service
└── TripaiApplication.java
```

역할은 아래처럼 나뉩니다.

- `controller`
  - HTTP 요청/응답 처리
- `domain`
  - JPA 엔티티
- `dto`
  - 요청/응답 데이터 구조
- `repository`
  - DB 접근
- `service`
  - 추천 생성, 저장, 조회 비즈니스 로직

## Run

### 1. 기본 실행

기본 실행은 `mock` 모드입니다.

```bash
./gradlew bootRun
```

### 2. OpenAI 모드 실행

환경변수에 API 키를 넣고 `openai` 프로필로 실행합니다.

```bash
export OPENAI_API_KEY=your_api_key
./gradlew bootRun --args='--spring.profiles.active=openai'
```


## API Example

### Create Recommendation

`POST /api/recommendations`

Request

```json
{
  "userPrompt": "부모님 모시고 조용하고 걷기 편한 국내 여행지 추천해줘",
  "companionType": "가족",
  "budgetLevel": "중간",
  "travelStyle": "힐링",
  "season": "가을"
}
```

Response

```json
{
  "id": 1,
  "destination": "공주",
  "companionType": "가족",
  "budgetLevel": "중간",
  "travelStyle": "힐링",
  "season": "가을",
  "userPrompt": "부모님 모시고 조용하고 걷기 편한 국내 여행지 추천해줘",
  "promptSummary": "가족와 함께 중간 예산으로 힐링 여행을 가을에 가고 싶어 함. 추가 요청사항은 \"부모님 모시고 조용하고 걷기 편한 국내 여행지 추천해줘\"입니다.",
  "reason": "공주는 힐링 여행에 잘 맞고, 사용자가 남긴 추가 요청사항인 \"부모님 모시고 조용하고 걷기 편한 국내 여행지 추천해줘\"도 함께 반영하기 좋은 추천지입니다.",
  "createdAt": "2026-05-07T21:00:00"
}
```

## Token Optimization

OpenAI 호출 비용을 줄이기 위해 추천 생성 흐름에 아래 최적화를 적용했습니다.

- `promptSummary`는 AI가 만들지 않고 서버에서 생성합니다.
- `reason`은 짧은 문장으로 제한하고, 너무 길면 서버에서 보정합니다.
- 선택하지 않은 값은 프롬프트에서 아예 생략합니다.
- `userPrompt`는 최대 200자로 제한하고, OpenAI에 전달할 때도 200자로 한 번 더 잘라 보냅니다.
- OpenAI 프롬프트는 JSON 응답만 반환하도록 고정해 불필요한 출력 토큰을 줄였습니다.

또한 같은 요청 재사용을 위해 2단계 캐시를 두었습니다.

- `원문 기반 캐시`
  - 선택값 + 정규화된 `userPrompt` 기준으로 조회합니다.
- `태그 기반 캐시`
  - `userPrompt`에서 추출한 태그를 기준으로 더 넓게 재사용합니다.
- 둘 다 miss일 때만 OpenAI를 호출합니다.

관련 코드는 아래에 있습니다.

- [RecommendationService.java](/src/main/java/com/my/proj/tripai/recommendation/service/RecommendationService.java)
- [OpenAiRecommendationClient.java](/src/main/java/com/my/proj/tripai/recommendation/service/OpenAiRecommendationClient.java)
- [RecommendationRequestCacheKeyGenerator.java](/src/main/java/com/my/proj/tripai/recommendation/service/RecommendationRequestCacheKeyGenerator.java)
- [UserPromptTagExtractor.java](/src/main/java/com/my/proj/tripai/recommendation/service/UserPromptTagExtractor.java)
- [RecommendationCache.java](/src/main/java/com/my/proj/tripai/recommendation/domain/RecommendationCache.java)

## Test

```bash
./gradlew test
```

## Notes

- 현재 기본 `mock` 구현은 실제 AI 추론이 아니라 규칙 기반 예시 응답입니다.
- OpenAI 응답은 JSON 문자열 형식으로 받도록 프롬프트를 구성해두었습니다.
- OpenAI 호출 시 선택형 입력은 코드값으로 압축해 전달하고, 비어 있으면 프롬프트에서 생략합니다.
- 추천 캐시는 `recommendation_cache` 테이블에 스냅샷 형태로 저장하며, 원문 기반 캐시와 태그 기반 캐시를 함께 사용합니다.
- MVP 단계이므로 인증/인가, 사용자 계정, 운영 DB, 외부 여행 데이터 연동은 아직 포함하지 않았습니다.
