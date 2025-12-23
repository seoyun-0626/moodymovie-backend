package com.moodymovie.backend.chat;

import java.util.ArrayList;
import java.util.List;

public class ChatSession {

    private int turn = 0;
    private boolean recommended = false;
    private List<String> history = new ArrayList<>();

    // ✅ 추가
    private String summary;
    private List<String> movies = new ArrayList<>();

    public void increaseTurn() {
        this.turn++;
    }

    public int getTurn() {
        return turn;
    }

    public boolean isRecommended() {
        return recommended;
    }

    public void setRecommended(boolean recommended) {
        this.recommended = recommended;
    }

    public List<String> getHistory() {
        return history;
    }

    public void addHistory(String text) {
        history.add(text);
    }

    // ✅ getter / setter
    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getMovies() {
        return movies;
    }

    public void setMovies(List<String> movies) {
        this.movies = movies;
    }
}
