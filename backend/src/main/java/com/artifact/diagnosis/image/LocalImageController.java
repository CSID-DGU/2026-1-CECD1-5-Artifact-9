package com.artifact.diagnosis.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/local-images")
@ConditionalOnProperty(name = "image.storage.type", havingValue = "local")
public class LocalImageController {

    private final Path uploadRoot;

    public LocalImageController(
            @Value("${image.local.upload-dir:/tmp/artifact-images}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping("/{filename}")
    public ResponseEntity<ByteArrayResource> get(@PathVariable String filename) throws IOException {
        Path target = uploadRoot.resolve(filename).normalize();

        if (!target.startsWith(uploadRoot) || !Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }

        String contentType = Files.probeContentType(target);
        MediaType mediaType = contentType == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(contentType);

        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(new ByteArrayResource(Files.readAllBytes(target)));
    }
}
