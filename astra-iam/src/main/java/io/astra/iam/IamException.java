package io.astra.iam;

/** An IAM request that cannot be honoured, carrying the HTTP status to answer with. */
public class IamException extends RuntimeException {

    private final int status;

    IamException(int status, String message) {
        super(message);
        this.status = status;
    }

    static IamException badRequest(String message) { return new IamException(400, message); }
    static IamException forbidden(String message) { return new IamException(403, message); }
    static IamException notFound(String message) { return new IamException(404, "not found: " + message); }
    static IamException conflict(String message) { return new IamException(409, message); }

    public int status() { return status; }
}
