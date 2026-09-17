package com.finplay.api.domain.community.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Profile("prod & web")
@Configuration
@EnableConfigurationProperties(CommunityS3StorageProperties.class)
public class S3ClientConfig {

	@Bean
	public S3Client s3Client(@Value("${aws.region}")
	String awsRegion) {
		return S3Client.builder().region(Region.of(awsRegion)).build();
	}
}
