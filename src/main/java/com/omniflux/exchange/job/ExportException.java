package com.omniflux.exchange.job;

public class ExportException extends RuntimeException {
    private final ErrorCode code;
    private final String detail;
    public ExportException(ErrorCode code, String detail) {
        super(code + ": " + detail); this.code = code; this.detail = detail;
    }
    public ExportException(ErrorCode code, String detail, Throwable cause) {
        super(code + ": " + detail, cause); this.code = code; this.detail = detail;
    }
    public ErrorCode code() { return code; }
    /** The message without the code prefix, for responses that carry the code separately. */
    public String detail() { return detail; }
    public boolean isTransient() { return code.errorClass() == ErrorClass.TRANSIENT; }
}
