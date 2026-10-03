package com.omniflux.exchange.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {
    @Bean(destroyMethod = "close")
    public S3AsyncClient s3AsyncClient(OmnifluxProperties props) {
        var storage = props.storage();
        return S3AsyncClient.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())))
                .forcePathStyle(storage.pathStyle())
                .multipartEnabled(true)
                .multipartConfiguration(c -> c
                        .minimumPartSizeInBytes(storage.partSize().toBytes())
                        .apiCallBufferSizeInBytes(storage.uploadBuffer().toBytes()))
                .overrideConfiguration(c -> c.apiCallTimeout(storage.apiCallTimeout()))
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(OmnifluxProperties props) {
        var storage = props.storage();
        return S3Presigner.builder()
                // Uploads run from the service network; presigned URLs are
                // consumed by the caller, so they need the caller-reachable
                // endpoint. In Compose these are seaweedfs:8333 and
                // localhost:9005 respectively.
                .endpointOverride(URI.create(storage.publicEndpoint()))
                .region(Region.of(storage.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.accessKey(), storage.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(storage.pathStyle())
                        .build())
                .build();
    }
}
