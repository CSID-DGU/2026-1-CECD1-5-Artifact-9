package com.artifact.diagnosis.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS SDK v2 S3Client Bean 설정.
 * application.properties 의 cloud.aws.* 환경 변수를 주입받아 빌드한다.
 *
 * 액세스 키를 비워 두면 SDK 기본 순서로 자격 증명을 찾는다 — EC2 에서는 인스턴스에 붙인 IAM 역할이다.
 * 서버에 키를 적어 두지 않아도 되므로 운영(EC2)에서는 키를 비우고 역할을 쓰는 쪽을 권한다
 * (docs/aws-elb-asg-guide.md). 로컬처럼 역할이 없는 곳에서는 지금처럼 키를 넣는다.
 */
@Configuration
public class AwsConfig {

    @Bean
    public S3Client s3Client(
            @Value("${cloud.aws.credentials.access-key:}") String accessKey,
            @Value("${cloud.aws.credentials.secret-key:}") String secretKey,
            @Value("${cloud.aws.region.static}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider(accessKey, secretKey))
                .build();
    }

    /** 키가 둘 다 있으면 그 키를, 하나라도 비어 있으면 기본 순서(환경변수 → 프로필 → EC2 인스턴스 역할)를 쓴다. */
    static AwsCredentialsProvider credentialsProvider(String accessKey, String secretKey) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            // 자격 증명은 첫 S3 호출 때 찾는다 — 로컬 저장소(image.storage.type=local)라 S3 를 안 쓰면 찾지도 않는다.
            return DefaultCredentialsProvider.builder().build();
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }
}
