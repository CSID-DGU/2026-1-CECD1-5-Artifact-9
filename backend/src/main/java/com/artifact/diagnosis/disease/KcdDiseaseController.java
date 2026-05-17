package com.artifact.diagnosis.disease;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

/**
 * KCD 상병코드 검색 API.
 *
 *   GET /api/v1/kcd-diseases?query=콜레라&page=0&size=20
 */
@Tag(name = "상병코드", description = "KCD 상병코드 검색 API")
@RestController
@RequestMapping("/api/v1/kcd-diseases")
@RequiredArgsConstructor
public class KcdDiseaseController {

    private final KcdDiseaseRepository kcdDiseaseRepository;

    @Operation(summary = "상병코드 검색",
               description = "코드(A000) 또는 한글 상병명으로 검색합니다. 페이징 지원.")
    @GetMapping
    public Page<KcdDisease> search(
            @Parameter(description = "검색어 (코드 또는 상병명)", example = "콜레라")
            @RequestParam(defaultValue = "") String query,
            @ParameterObject
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return kcdDiseaseRepository.findByCodeContainingOrNameKrContaining(query, query, pageable);
    }
}
