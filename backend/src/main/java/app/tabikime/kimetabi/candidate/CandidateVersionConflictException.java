package app.tabikime.kimetabi.candidate;

public class CandidateVersionConflictException extends RuntimeException {

    private final CandidateResource current;

    public CandidateVersionConflictException(CandidateResource current) {
        this.current = current;
    }

    public CandidateResource current() {
        return current;
    }
}
