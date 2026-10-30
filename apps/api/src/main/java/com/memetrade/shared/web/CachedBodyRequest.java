package com.memetrade.shared.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Request wrapper that reads the (small) JSON body once so filters can inspect it and controllers still read it. */
public final class CachedBodyRequest extends HttpServletRequestWrapper {

    /** U1 request bodies are a few hundred bytes; anything above this is refused before parsing. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /** Wraps the request, or returns {@code null} when the body exceeds {@link #MAX_BODY_BYTES}. */
    public static CachedBodyRequest wrap(HttpServletRequest request) throws IOException {
        if (request instanceof CachedBodyRequest cached) {
            return cached;
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            return null;
        }
        return new CachedBodyRequest(request, bytes);
    }

    public byte[] body() {
        return body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream stream = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return stream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Asynchronous reads are not supported");
            }

            @Override
            public int read() {
                return stream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
