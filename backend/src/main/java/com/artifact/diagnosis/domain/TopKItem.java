package com.artifact.diagnosis.domain;

/**
 * AI 분석 결과의 Top-K 후보 1건.
 * AnalysisResult.topKResults JSON 컬럼 안에 배열로 들어간다.
 *
 *   code: HAM10000 클래스 코드 (예: "mel", "nv")
 *   confidence: 0.0 ~ 1.0
 */
public record TopKItem(String code, double confidence) {}
