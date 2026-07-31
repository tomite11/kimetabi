package app.tabikime.kimetabi.identity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {

    @GetMapping("/api/session")
    SessionResponse getSession(@AuthenticationPrincipal AppPrincipal principal) {
        return new SessionResponse(principal.firebaseUid());
    }

    record SessionResponse(String firebaseUid) {
    }
}
