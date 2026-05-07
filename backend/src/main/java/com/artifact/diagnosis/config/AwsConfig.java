package com.artifact.diagnosis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * AWS SDK v2 빈 설정.
 * S3Client (업로드)와 S3Presigner (Pre-signed URL 생성)를 각각 빈으로 등록한다.
 * 자격증명은 application.properties → 환경변수에서 주입.
 */
@Configuration
public class AwsConfig {

    /** S3 객체 업로드(PUT)에 사용하는 클라이언트. */
    @Bean
    public S3Client s3Client(
            @Value("${cloud.aws.credentials.access-key}") String accessKey,
            @Value("${cloud.aws.credentials.secret-key}") String secretKey,
            @Value("${cloud.aws.region.static}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider(accessKey, secretKey))
                .build();
    }

    /** Pre-signed URL 생성(GET)에 사용하는 서명기. */
    @Bean
    public S3Presigner s3Presigner(
            @Value("${cloud.aws.credentials.access-key}") String accessKey,
            @Value("${cloud.aws.credentials.secret-key}") String secretKey,
            @Value("${cloud.aws.region.static}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider(accessKey, secretKey))
                .build();
    }

    private StaticCredentialsProvider credentialsProvider(String accessKey, String secretKey) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
