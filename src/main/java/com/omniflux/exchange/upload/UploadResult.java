package com.omniflux.exchange.upload;

/** Result returned after an object has been committed by object storage. */
public record UploadResult(String key, long bytes, String sha256, String etag) { }
