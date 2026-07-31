package app.tabikime.kimetabi.identity;

@FunctionalInterface
public interface FirebaseTokenVerifier {

    VerifiedFirebaseToken verify(String idToken) throws FirebaseTokenVerificationException;
}
