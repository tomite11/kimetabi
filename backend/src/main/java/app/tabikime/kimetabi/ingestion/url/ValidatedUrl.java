package app.tabikime.kimetabi.ingestion.url;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

public record ValidatedUrl(URI uri, String hostname, int port, List<InetAddress> addresses) {

    public ValidatedUrl {
        addresses = List.copyOf(addresses);
    }
}
