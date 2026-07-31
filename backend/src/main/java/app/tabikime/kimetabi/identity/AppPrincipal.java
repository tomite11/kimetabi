package app.tabikime.kimetabi.identity;

import java.security.Principal;

public record AppPrincipal(String firebaseUid) implements Principal {

    public AppPrincipal {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw new IllegalArgumentException("firebaseUid must not be blank");
        }
    }

    @Override
    public String getName() {
        return firebaseUid;
    }
}
