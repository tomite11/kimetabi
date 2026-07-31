package app.tabikime.kimetabi.identity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

final class FirebaseAdminTokenVerifier implements FirebaseTokenVerifier {

    private final FirebaseAuth firebaseAuth;

    FirebaseAdminTokenVerifier(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    @Override
    public VerifiedFirebaseToken verify(String idToken)
            throws FirebaseTokenVerificationException {
        try {
            FirebaseToken token = firebaseAuth.verifyIdToken(idToken);
            return new VerifiedFirebaseToken(token.getUid());
        } catch (FirebaseAuthException | IllegalArgumentException exception) {
            throw new FirebaseTokenVerificationException(
                    "Firebase ID token verification failed", exception);
        }
    }
}
