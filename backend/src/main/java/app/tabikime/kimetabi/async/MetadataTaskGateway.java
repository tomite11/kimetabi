package app.tabikime.kimetabi.async;

import java.io.IOException;
import java.util.UUID;

public interface MetadataTaskGateway {

    void create(UUID eventId, long candidateId) throws IOException;
}
