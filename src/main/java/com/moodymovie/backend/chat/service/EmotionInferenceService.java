package com.moodymovie.backend.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class EmotionInferenceService {

    @Value("${fastapi.base-url}")
    private String fastApiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, Object> predictSubEmotion(
            String text,
            String repEmotion
    ) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "text", text,
                "rep_emotion", repEmotion
        );

        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(
                            fastApiUrl + "/emotion",
                            request,
                            Map.class
                    );

            return response.getBody();
        } catch (Exception e) {
            return Map.of(
                    "emotion", repEmotion,
                    "sub_emotion", null
            );
        }
    }
}
