package com.github.skanga.ajent.provider.ollama;

public record OllamaRequestOptions(int maxTokens, int contextWindow, boolean jsonProtocol) {}
