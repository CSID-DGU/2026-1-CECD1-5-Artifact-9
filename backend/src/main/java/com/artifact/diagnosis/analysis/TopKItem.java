package com.artifact.diagnosis.analysis;

/**
 * AI 분석 결과의 Top-K 후보 1건.
 * AnalysisResult.topKResults JSON 컬럼 안에 배열로 들어간다.
 *
 *   code: 모델 클래스 코드 (예: "mel", "nv" / facial 은 "acne", "normal" 등).
 *         어느 태스크의 코드인지는 AnalysisResult.analysisType 이 말한다.
 *   confidence: 0.0 ~ 1.0
 */
public record TopKItem(String code, double confidence) {}
