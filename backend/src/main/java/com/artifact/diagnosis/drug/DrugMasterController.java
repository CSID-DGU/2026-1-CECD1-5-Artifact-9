package com.artifact.diagnosis.drug;

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
 * 처방(약품) 코드 검색 API.
 *
 *   GET /api/v1/drugs?query=아스피린&page=0&size=20
 */
@Tag(name = "처방코드", description = "처방(약품) 코드 검색 API")
@RestController
@RequestMapping("/api/v1/drugs")
@RequiredArgsConstructor
public class DrugMasterController {

    private final DrugMasterRepository drugMasterRepository;

    @Operation(summary = "처방(약품) 코드 검색",
               description = "처방코드(050000011) 또는 한글 처방명으로 검색합니다. 페이징 지원.")
    @GetMapping
    public Page<DrugMaster> search(
            @Parameter(description = "검색어 (코드 또는 처방명)", example = "아스피린")
            @RequestParam(defaultValue = "") String query,
            @ParameterObject
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return drugMasterRepository.findByCodeContainingOrNameKrContaining(query, query, pageable);
    }
}
