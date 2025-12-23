package com.moodymovie.backend.chat;

import com.moodymovie.backend.chat.service.EmotionInferenceService;
import com.moodymovie.backend.chat.service.OpenAiClient;
import com.moodymovie.backend.tmdb.TmdbClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatService {

    /* =========================
       의존성 주입
    ========================= */

    private final EmotionInferenceService inferenceService;
    private final OpenAiClient openAiClient;
    private final TmdbClient tmdbClient;

    /* =========================
       세션 저장소 (메모리)
       sessionId 기준
    ========================= */

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    /* =========================
       대표감정 → TMDB 장르 ID 매핑
       (정책이므로 하드코딩 허용)
    ========================= */

    private static final Map<String, List<Integer>> EMOTION_GENRES = Map.of(
            "행복", List.of(35, 10749),      // 코미디, 로맨스
            "슬픔", List.of(18),             // 드라마
            "불안", List.of(53, 27),         // 스릴러, 공포
            "분노", List.of(28, 80),         // 액션, 범죄
            "심심", List.of(12, 35),         // 모험, 코미디
            "탐구", List.of(878, 9648),      // SF, 미스터리
            "스트레스", List.of(16, 10751)  // 애니메이션, 가족
    );

    /* =========================
       생성자
    ========================= */

    public ChatService(
            EmotionInferenceService inferenceService,
            OpenAiClient openAiClient,
            TmdbClient tmdbClient
    ) {
        this.inferenceService = inferenceService;
        this.openAiClient = openAiClient;
        this.tmdbClient = tmdbClient;
    }

    /* =========================
       메인 핸들러
    ========================= */

    public Map<String, Object> handle(Map<String, Object> body) {

        String sessionId = (String) body.get("sessionId");
        String text = (String) body.get("text");
        String mode = (String) body.getOrDefault("mode", "emotion");

        ChatSession session =
                sessions.computeIfAbsent(sessionId, k -> new ChatSession());

        /* ==================================================
           [1] 추천 이후 자유대화
           - 프론트에서 mode = "chat" 으로 들어옴
           - 감정 재분석 / 재추천 절대 안 함
        ================================================== */
        if ("chat".equals(mode)) {

            String systemPrompt =
                    "너는 감정 기반 영화 추천 이후의 '영화 전문 챗봇'이다.\n" +
                            "이미 사용자의 감정을 바탕으로 1차 영화 추천은 완료된 상태다.\n\n" +

                            "다음 규칙을 따른다:\n" +
                            "1. 감정 분석이나 감정 재판단은 하지 않는다.\n" +
                            "2. 이전에 추천한 영화와 대화 맥락을 기억한다.\n" +
                            "3. 사용자가 영화가 마음에 들지 않는다고 하면,\n" +
                            "   조건(장르, 분위기, 국가, 속도감, 최근작 등)을 바탕으로\n" +
                            "   새로운 영화를 추천할 수 있다.\n" +
                            "4. 영화 추천, 비교, 설명, 질문은 자유롭게 허용된다.\n" +
                            "5. 영화와 무관한 주제로 벗어나지 않는다.\n\n" +

                            "너의 역할은 영화에 대해 대화하고 추천하는 것이다.";

            String userPrompt =
                    "이전 대화 요약:\n" + session.getSummary() + "\n\n" +
                            "추천한 영화 목록:\n" + String.join(", ", session.getMovies()) + "\n\n" +
                            "사용자 발화:\n" + text;

            String reply =
                    openAiClient.call(systemPrompt, userPrompt);

            return Map.of("reply", reply);
        }

        /* ==================================================
           [2] 감정 탐색 단계
           - 1~2턴: GPT가 질문 이어감
        ================================================== */

        session.increaseTurn();
        session.addHistory(text);

        if (session.getTurn() < 3) {

            String systemPrompt =
                    "너는 감정 요약을 위한 3턴짜리 감정 탐색 챗봇이다.\n" +
                            "이미 첫 질문(오늘 기분이 어때?)은 끝난 상태다.\n\n" +

                            "규칙:\n" +
                            "1. 지금까지의 대화를 하나의 상황으로 이해한다.\n" +
                            "2. 사용자의 말을 그대로 반복하거나 재진술하지 않는다.\n" +
                            "3. 상담사처럼 말하지 않는다.\n" +
                            "4. 현재 감정 상태를 더 분명히 하기 위한 질문만 한다.\n" +
                            "5. 질문은 짧고 자연스럽게 하나만 한다.\n" +
                            "6. 영화, 추천, 활동, 조언은 절대 언급하지 않는다.";


            String userPrompt =
                    "지금까지의 사용자 발화:\n" +
                            String.join("\n", session.getHistory());

            String reply =
                    openAiClient.call(systemPrompt, userPrompt);

            return Map.of("reply", reply);
        }

        /* ==================================================
           [3] turn == 3
           - 요약
           - 대표감정 확정
           - 세부감정(FastAPI)
           - TMDB 기반 영화 추천
        ================================================== */

        // ① 대화 요약 (GPT)
        String summary = summarize(session.getHistory());

        // ② 대표감정 추론 (GPT, 7개 중 1개)
        String repEmotion = inferRepresentativeEmotion(summary);

        // ③ 세부감정 추론 (FastAPI)
        Map<String, Object> emotionResult =
                inferenceService.predictSubEmotion(summary, repEmotion);

        // ④ 감정 → 장르 → TMDB 영화 풀 생성
        List<Integer> genreIds = EMOTION_GENRES.get(repEmotion);

        List<String> pool =
                tmdbClient.fetchTopMoviesByGenres(genreIds);

        if (pool.isEmpty()) {
            return Map.of(
                    "reply", "추천할 영화를 찾지 못했어.",
                    "final", true,
                    "summary", summary,
                    "emotion", repEmotion,
                    "sub_emotion", emotionResult.get("sub_emotion"),
                    "movies", List.of()
            );
        }

        // ⑤ 랜덤 5개 선택
        Collections.shuffle(pool);

        List<Map<String, String>> movies = pool.stream()
                .limit(5)
                .map(title -> Map.of("title", title))
                .toList();

        /* ==================================================
           [4] 세션에 추천 결과 저장
           - 이후 자유대화에서 기억용
        ================================================== */

        session.setRecommended(true);
        session.setSummary(summary);
        session.setMovies(
                movies.stream()
                        .map(m -> m.get("title"))
                        .toList()
        );

        /* ==================================================
           [5] 프론트 최종 응답
        ================================================== */

        return Map.of(
                "reply", "지금까지 이야기한 걸 정리해봤어.",
                "final", true,
                "summary", summary,
                "emotion", repEmotion,
                "sub_emotion", emotionResult.get("sub_emotion"),
                "movies", movies
        );
    }

    /* =========================
       GPT 요약
    ========================= */

    private String summarize(List<String> history) {

        String systemPrompt =
                "너는 감정 분류 모델에 입력될 요약 문장을 생성한다.\n" +
                        "다음 규칙을 반드시 지켜라:\n\n" +
                        "1. 결과는 반드시 한 문장이다.\n" +
                        "2. 사용자의 현재 감정 상태만 서술한다.\n" +
                        "3. 추측, 평가, 해석, 조언을 포함하지 않는다.\n" +
                        "4. '사용자는', '말했다', '느끼는 것 같다' 같은 표현을 쓰지 않는다.\n" +
                        "5. 영화, 추천, 활동, 원인 분석은 포함하지 않는다.\n\n" +
                        "형식 예시:\n" +
                        "- 전반적으로 기분이 좋고 특별한 사건은 없다고 느끼고 있다.\n" +
                        "- 이유 없이 무기력하고 의욕이 낮은 상태다.";

        String userPrompt = String.join("\n", history);

        return openAiClient.call(systemPrompt, userPrompt);
    }

    /* =========================
       GPT 대표감정 추론
    ========================= */

    private String inferRepresentativeEmotion(String summary) {

        String systemPrompt =
                "너는 사용자의 감정을 분석한다.\n" +
                        "아래 7개 중 하나만 반드시 선택해서 단어 하나로만 답해라.\n\n" +
                        "탐구\n분노\n슬픔\n행복\n심심\n불안\n스트레스\n\n" +
                        "다른 말, 설명, 문장은 절대 금지한다.";

        String result =
                openAiClient.call(systemPrompt, summary);

        return switch (result.trim()) {
            case "탐구", "분노", "슬픔", "행복", "심심", "불안", "스트레스" -> result.trim();
            default -> "불안";
        };
    }
}
