package com.artifact.diagnosis.gemini;

import com.artifact.diagnosis.llm.LlmProvider;
import com.artifact.diagnosis.llm.LlmRequest;
import com.artifact.diagnosis.llm.LlmResult;

import java.time.Duration;

/**
 * Gemini 모델 하나를 체인의 공급자 하나로 감싼다. 같은 {@link GeminiClient}(같은 키)를 공유하고
 * 모델 ID 만 다르다 — 1차 모델이 과부하일 때 같은 키로 다른 모델을 부르는 것이 이 클래스의 용도다.
 */
public class GeminiModelProvider implements LlmProvider {

    private final GeminiClient client;
    private final String model;

    public GeminiModelProvider(GeminiClient client, String model) {
        this.client = client;
        this.model = model == null ? "" : model.trim();
    }

    @Override
    public String name() {
        return GeminiClient.PROVIDER + ":" + model;
    }

    @Override
    public boolean isConfigured() {
        return client.hasApiKey() && !model.isEmpty();
    }

    @Override
    public LlmResult generate(LlmRequest request, Duration maxWait) {
        return client.generate(model, request, maxWait);
    }

    public String model() {
        return model;
    }
}
