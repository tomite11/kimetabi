package app.tabikime.kimetabi.ingestion.url;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@FunctionalInterface
public interface AddressResolver {

    List<InetAddress> resolve(String hostname) throws UnknownHostException;

    static AddressResolver system() {
        return hostname -> List.of(InetAddress.getAllByName(hostname));
    }
}
