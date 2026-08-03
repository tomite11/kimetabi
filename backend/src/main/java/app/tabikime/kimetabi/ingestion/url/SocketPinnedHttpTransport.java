package app.tabikime.kimetabi.ingestion.url;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class SocketPinnedHttpTransport implements PinnedHttpTransport {

    private static final int MAX_HEADER_BYTES = 64 * 1024;

    @Override
    public Response execute(
            ValidatedUrl target,
            InetAddress pinnedAddress,
            Duration connectTimeout,
            Duration operationTimeout
    ) throws IOException {
        long operationDeadline = System.nanoTime() + operationTimeout.toNanos();
        Socket socket = new Socket();
        try {
            socket.connect(
                    new InetSocketAddress(pinnedAddress, target.port()),
                    timeoutMillis(connectTimeout));
            socket.setSoTimeout(timeoutMillis(operationTimeout));
            if ("https".equals(target.uri().getScheme())) {
                socket = tlsSocket(socket, target);
            }
            writeRequest(socket.getOutputStream(), target);
            return readResponse(socket, remaining(operationDeadline));
        } catch (IOException | RuntimeException exception) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Preserve the original transport failure.
            }
            throw exception;
        }
    }

    private static Duration remaining(long deadlineNanos) throws SocketTimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("URL operation deadline exceeded");
        }
        return Duration.ofNanos(remaining);
    }

    private static Socket tlsSocket(Socket connected, ValidatedUrl target) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket socket = (SSLSocket) factory.createSocket(
                connected, target.hostname(), target.port(), true);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }

    private static void writeRequest(OutputStream output, ValidatedUrl target) throws IOException {
        String path = target.uri().getRawPath();
        if (target.uri().getRawQuery() != null) {
            path += "?" + target.uri().getRawQuery();
        }
        String host = target.hostname().contains(":")
                ? "[" + target.hostname() + "]" : target.hostname();
        if (target.uri().getPort() != -1) {
            host += ":" + target.port();
        }
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "User-Agent: KimetabiMetadata/1.0\r\n"
                + "Accept: text/html,application/xhtml+xml\r\n"
                + "Accept-Encoding: gzip, deflate\r\n"
                + "Connection: close\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static Response readResponse(Socket socket, Duration operationTimeout)
            throws IOException {
        InputStream deadlineInput = new DeadlineInputStream(
                socket.getInputStream(), socket, operationTimeout);
        BufferedInputStream input = new BufferedInputStream(deadlineInput);
        HeaderReader reader = new HeaderReader(input);
        String statusLine = reader.readLine();
        if (statusLine == null || !statusLine.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?")) {
            throw new InvalidHttpResponseException("Invalid HTTP status line");
        }
        int statusCode = Integer.parseInt(statusLine.substring(9, 12));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator <= 0 || Character.isWhitespace(line.charAt(0))) {
                throw new InvalidHttpResponseException("Invalid HTTP response header");
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        if (line == null) {
            throw new InvalidHttpResponseException("Unexpected end of HTTP headers");
        }

        InputStream body = responseBody(input, headers);
        String contentEncoding = firstHeader(headers, "Content-Encoding");
        if (contentEncoding != null) {
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                body = new GZIPInputStream(body);
            } else if ("deflate".equalsIgnoreCase(contentEncoding)) {
                body = new InflaterInputStream(body);
            }
        }
        return new Response(statusCode, headers, new SocketClosingInputStream(body, socket));
    }

    private static InputStream responseBody(
            InputStream input,
            Map<String, List<String>> headers
    ) throws IOException {
        String transferEncoding = firstHeader(headers, "Transfer-Encoding");
        if (transferEncoding != null
                && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            return new ChunkedInputStream(input);
        }
        String contentLength = firstHeader(headers, "Content-Length");
        if (contentLength != null) {
            try {
                long length = Long.parseLong(contentLength);
                if (length < 0) throw new NumberFormatException();
                return new FixedLengthInputStream(input, length);
            } catch (NumberFormatException exception) {
                throw new InvalidHttpResponseException("Invalid Content-Length", exception);
            }
        }
        return input;
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
    }

    private static int timeoutMillis(Duration timeout) {
        long millis = Math.max(1, timeout.toMillis());
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private static final class HeaderReader {

        private final InputStream input;
        private int bytesRead;

        private HeaderReader(InputStream input) {
            this.input = input;
        }

        private String readLine() throws IOException {
            StringBuilder line = new StringBuilder();
            int previous = -1;
            int current;
            while ((current = input.read()) != -1) {
                if (++bytesRead > MAX_HEADER_BYTES) {
                    throw new IOException("HTTP headers exceed limit");
                }
                if (previous == '\r' && current == '\n') {
                    line.setLength(line.length() - 1);
                    return line.toString();
                }
                line.append((char) current);
                previous = current;
            }
            return null;
        }
    }

    private static final class FixedLengthInputStream extends FilterInputStream {

        private long remaining;

        private FixedLengthInputStream(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) return -1;
            int count = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (count == -1) throw new IOException("Unexpected end of response body");
            remaining -= count;
            return count;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count == -1 ? -1 : Byte.toUnsignedInt(one[0]);
        }
    }

    private static final class ChunkedInputStream extends InputStream {

        private final InputStream input;
        private long remaining;
        private boolean finished;

        private ChunkedInputStream(InputStream input) {
            this.input = input;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (finished) return -1;
            if (remaining == 0) readChunkHeader();
            if (finished) return -1;
            int count = input.read(bytes, offset, (int) Math.min(length, remaining));
            if (count == -1) throw new IOException("Unexpected end of chunked response");
            remaining -= count;
            if (remaining == 0) requireCrLf();
            return count;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count == -1 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        private void readChunkHeader() throws IOException {
            String line = readAsciiLine(input);
            int extension = line.indexOf(';');
            String size = extension < 0 ? line : line.substring(0, extension);
            try {
                remaining = Long.parseLong(size.trim(), 16);
            } catch (NumberFormatException exception) {
                throw new InvalidHttpResponseException("Invalid chunk size", exception);
            }
            if (remaining == 0) {
                while (!readAsciiLine(input).isEmpty()) {
                    // Discard trailers; metadata never consumes external response headers.
                }
                finished = true;
            }
        }

        private void requireCrLf() throws IOException {
            if (input.read() != '\r' || input.read() != '\n') {
                throw new InvalidHttpResponseException("Invalid chunk delimiter");
            }
        }

        private static String readAsciiLine(InputStream input) throws IOException {
            StringBuilder line = new StringBuilder();
            int previous = -1;
            int current;
            while ((current = input.read()) != -1) {
                if (previous == '\r' && current == '\n') {
                    line.setLength(line.length() - 1);
                    return line.toString();
                }
                line.append((char) current);
                previous = current;
                if (line.length() > MAX_HEADER_BYTES) {
                    throw new IOException("Chunk header exceeds limit");
                }
            }
            throw new InvalidHttpResponseException("Unexpected end of chunk header");
        }
    }

    private static final class SocketClosingInputStream extends FilterInputStream {

        private final Socket socket;

        private SocketClosingInputStream(InputStream input, Socket socket) {
            super(input);
            this.socket = socket;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                socket.close();
            }
        }
    }

    private static final class DeadlineInputStream extends FilterInputStream {

        private final Socket socket;
        private final long deadlineNanos;

        private DeadlineInputStream(InputStream input, Socket socket, Duration timeout) {
            super(input);
            this.socket = socket;
            this.deadlineNanos = System.nanoTime() + timeout.toNanos();
        }

        @Override
        public int read() throws IOException {
            updateTimeout();
            return super.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            updateTimeout();
            return super.read(bytes, offset, length);
        }

        private void updateTimeout() throws IOException {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                throw new java.net.SocketTimeoutException("URL operation deadline exceeded");
            }
            socket.setSoTimeout(timeoutMillis(Duration.ofNanos(remaining)));
        }
    }
}
