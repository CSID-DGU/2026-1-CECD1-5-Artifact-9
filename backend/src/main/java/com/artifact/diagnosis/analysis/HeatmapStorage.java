package com.artifact.diagnosis.analysis;

import com.artifact.diagnosis.image.ImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * FastAPI 가 준 히트맵(전체 + 확대)을 스토리지에 올린다.
 *
 * 진료실 분석(AnalysisService)과 키오스크 예비분석(KioskService)이 함께 쓴다. 두 곳에 사본을 두면
 * "확대 히트맵 하나가 실패하면 그것만 건너뛴다"는 규칙이나 키 이름이 언젠가 한쪽에서만 바뀐다.
 * 스토리지 왕복이므로 트랜잭션 밖에서 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeatmapStorage {

    private final ImageStorageService imageStorageService;

    /**
     * @param keyPrefix 확장자 없는 키 접두어. 전체 히트맵은 {@code {prefix}.jpg}, 확대 히트맵은
     *                  {@code {prefix}_crop{n}.jpg} 로 올라가 스토리지에서 한 분석의 그림끼리 나란히 보인다.
     *
     * 확대 히트맵은 하나가 실패하면 그것만 건너뛴다. 확대 보기는 전체 히트맵을 거들 뿐이라 그 때문에
     * 이미 끝난 추론까지 버리면 안 된다. 전체 히트맵은 지금까지처럼 실패하면 예외가 그대로 올라간다.
     */
    public StoredHeatmap upload(String keyPrefix, FastApiPredictResponse prediction) {
        String key = prediction.heatmapBase64() == null ? null
                : imageStorageService.uploadBytes(keyPrefix + ".jpg",
                        Base64.getDecoder().decode(prediction.heatmapBase64()), "image/jpeg");

        List<HeatmapCrop> crops = new ArrayList<>();
        for (FastApiCrop crop : Objects.requireNonNullElse(prediction.crops(), List.<FastApiCrop>of())) {
            try {
                String cropKey = imageStorageService.uploadBytes(keyPrefix + "_crop" + crop.rank() + ".jpg",
                        Base64.getDecoder().decode(crop.heatmapBase64()), "image/jpeg");
                crops.add(new HeatmapCrop(crop.rank(), cropKey, crop.boxNorm(), crop.peak(), crop.source()));
            } catch (RuntimeException e) {
                log.warn("확대 히트맵 저장 실패 — 이 영역만 건너뛴다 key={} rank={}", keyPrefix, crop.rank(), e);
            }
        }
        return new StoredHeatmap(key, prediction.heatmapStyle(), List.copyOf(crops));
    }

    /**
     * 저장된 확대 히트맵을 화면용으로. 이미지 경로는 {@code imageUrlPrefix + rank} 다 —
     * 같은 그림이라도 호출자마다 들어갈 수 있는 입구(JWT 경로·키오스크 토큰 경로)가 달라서 접두어를 받는다.
     * 이 기능 전의 행·확대 보기가 없는 분석은 빈 목록이다.
     */
    public static List<AnalysisResponse.HeatmapCropResult> cropResults(List<HeatmapCrop> crops, String imageUrlPrefix) {
        if (crops == null) return List.of();
        return crops.stream()
                .map(c -> new AnalysisResponse.HeatmapCropResult(c.rank(), imageUrlPrefix + c.rank(), c.boxNorm(), c.peak(), c.source()))
                .toList();
    }

    /** 저장된 확대 히트맵 목록에서 번호로 하나를 찾는다. 없으면 null. */
    public static HeatmapCrop findCrop(List<HeatmapCrop> crops, int rank) {
        if (crops == null) return null;
        return crops.stream().filter(c -> c.rank() == rank).findFirst().orElse(null);
    }
}
