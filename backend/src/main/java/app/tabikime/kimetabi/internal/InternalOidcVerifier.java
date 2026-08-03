package app.tabikime.kimetabi.internal;

@FunctionalInterface
public interface InternalOidcVerifier {

    void verify(String token, InternalCaller caller) throws InternalOidcVerificationException;
}
