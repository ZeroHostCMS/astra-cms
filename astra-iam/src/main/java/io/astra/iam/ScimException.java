package io.astra.iam;

/**
 * A SCIM request that cannot be honoured, rendered as an RFC 7644 section 3.12 error: HTTP status,
 * optional {@code scimType} keyword and a human-readable detail.
 */
public class ScimException extends RuntimeException {

    private final int status;
    private final String scimType;

    ScimException(int status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
    }

    static ScimException invalidSyntax(String detail) { return new ScimException(400, "invalidSyntax", detail); }
    static ScimException invalidFilter(String detail) { return new ScimException(400, "invalidFilter", detail); }
    static ScimException invalidValue(String detail) { return new ScimException(400, "invalidValue", detail); }
    static ScimException invalidPath(String detail) { return new ScimException(400, "invalidPath", detail); }
    static ScimException noTarget(String detail) { return new ScimException(400, "noTarget", detail); }
    static ScimException mutability(String detail) { return new ScimException(400, "mutability", detail); }
    static ScimException uniqueness(String detail) { return new ScimException(409, "uniqueness", detail); }
    static ScimException unauthorized(String detail) { return new ScimException(401, null, detail); }
    static ScimException forbidden(String detail) { return new ScimException(403, null, detail); }
    static ScimException notFound(String detail) { return new ScimException(404, null, detail); }
    static ScimException notImplemented(String detail) { return new ScimException(501, null, detail); }

    public int status() { return status; }

    /** The RFC 7644 {@code scimType} keyword, or {@code null} when the status alone says it. */
    public String scimType() { return scimType; }
}
