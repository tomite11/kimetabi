package app.tabikime.kimetabi.identity;

public record VerifiedFirebaseToken(String uid) {

    public VerifiedFirebaseToken {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("uid must not be blank");
        }
    }
}
