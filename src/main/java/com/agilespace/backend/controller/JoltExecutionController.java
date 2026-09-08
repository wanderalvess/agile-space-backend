package com.agilespace.backend.controller;

import com.agilespace.backend.dto.JoltTransformRequestDto;
import com.agilespace.backend.dto.JoltTransformResponseDto;
import com.agilespace.backend.service.JoltJavaEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jolt")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
@Tag(name = "JOLT Engine", description = "Execução oficial de transformações JOLT (Bazaarvoice Java)")
public class JoltExecutionController {

    private final JoltJavaEngineService joltJavaEngineService;

    @Operation(summary = "Executa transformação JOLT utilizando o motor oficial Bazaarvoice em Java")
    @PostMapping("/transform")
    public ResponseEntity<JoltTransformResponseDto> transform(@Valid @RequestBody JoltTransformRequestDto request) {
        JoltTransformResponseDto response = joltJavaEngineService.transform(request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Informações sobre o motor JOLT Java ativo")
    @GetMapping("/engine-info")
    public ResponseEntity<Map<String, Object>> getEngineInfo() {
        return ResponseEntity.ok(Map.of(
                "engine", "JAVA_BAZAARVOICE",
                "version", "0.1.8",
                "vendor", "Bazaarvoice / Apache",
                "supportedOperations", List.of(
                        "shift",
                        "default",
                        "remove",
                        "sort",
                        "cardinality",
                        "modify-overwrite-beta",
                        "modify-default-beta"
                ),
                "totvsSmartHubEnvelopeSupport", true
        ));
    }
}
