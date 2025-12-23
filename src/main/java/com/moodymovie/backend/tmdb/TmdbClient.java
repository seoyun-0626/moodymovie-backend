package com.moodymovie.backend.tmdb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class TmdbClient {

    @Value("${tmdb.api.key:}")
    private String apiKey;

    @Value("${tmdb.base-url:https://api.themoviedb.org/3}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @SuppressWarnings("unchecked")
    public List<String> fetchTopMoviesByGenres(List<Integer> genreIds) {

        if (apiKey == null || apiKey.isBlank()) {
            // 키 없으면 TMDB 호출 안 함 (배포 안정용)
            return Collections.emptyList();
        }

        String genres = String.join(
                ",",
                genreIds.stream().map(String::valueOf).toList()
        );

        List<String> titles = new ArrayList<>();

        for (int page = 1; page <= 3; page++) {

            String url =
                    baseUrl + "/discover/movie" +
                            "?api_key=" + apiKey +
                            "&with_genres=" + genres +
                            "&sort_by=vote_average.desc" +
                            "&vote_count.gte=500" +
                            "&language=ko-KR" +
                            "&page=" + page;

            Map<String, Object> res =
                    restTemplate.getForObject(url, Map.class);

            if (res == null || !res.containsKey("results")) continue;

            List<Map<String, Object>> results =
                    (List<Map<String, Object>>) res.get("results");

            for (Map<String, Object> movie : results) {
                Object title = movie.get("title");
                if (title != null) {
                    titles.add(title.toString());
                }
            }
        }

        return titles;
    }
}
