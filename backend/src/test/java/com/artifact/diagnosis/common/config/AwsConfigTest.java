package com.artifact.diagnosis.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 자격 증명 선택 — 키를 주면 그 키, 비우면 EC2 인스턴스 역할(SDK 기본 순서).
 *
 * 운영 EC2 에서는 키를 비우고 IAM 역할을 붙인다(docs/aws-elb-asg-guide.md). 비운 키로 정적 자격 증명을 만들면
 * S3 호출마다 서명 오류가 나므로, "비어 있으면 기본 순서" 라는 갈림길을 여기서 고정한다.
 */
class AwsConfigTest {

	@Test
	@DisplayName("키를 둘 다 주면 그 키로 서명한다")
	void usesStaticKeysWhenBothGiven() {
		var provider = AwsConfig.credentialsProvider("AKIAEXAMPLE", "secret-example");

		assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);
		AwsCredentials credentials = provider.resolveCredentials();
		assertThat(credentials.accessKeyId()).isEqualTo("AKIAEXAMPLE");
	}

	@Test
	@DisplayName("키가 비어 있거나 하나만 있으면 기본 순서(인스턴스 역할 등)로 찾는다")
	void fallsBackToDefaultChainWhenKeysBlank() {
		assertThat(AwsConfig.credentialsProvider("", "")).isInstanceOf(DefaultCredentialsProvider.class);
		assertThat(AwsConfig.credentialsProvider(null, null)).isInstanceOf(DefaultCredentialsProvider.class);
		assertThat(AwsConfig.credentialsProvider("AKIAEXAMPLE", " ")).isInstanceOf(DefaultCredentialsProvider.class);
	}
}
