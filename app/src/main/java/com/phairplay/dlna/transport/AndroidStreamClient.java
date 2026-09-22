package com.phairplay.dlna.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.jupnp.model.message.StreamRequestMessage;
import org.jupnp.model.message.StreamResponseMessage;
import org.jupnp.model.message.UpnpHeaders;
import org.jupnp.model.message.UpnpMessage;
import org.jupnp.model.message.UpnpRequest;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.transport.spi.StreamClient;
import org.jupnp.transport.spi.StreamClientConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link StreamClient} built on {@link HttpURLConnection} — pure java.net,
 * replacing jUPnP's Jetty-based client so no Jetty library is needed on device.
 *
 * <p>The DLNA renderer mostly receives control commands over HTTP, so this
 * client is only exercised when the jUPnP stack itself initiates HTTP
 * (e.g. pings, descriptor fetches).</p>
 */
public class AndroidStreamClient implements StreamClient<StreamClientConfiguration> {

    private static final Logger logger = LoggerFactory.getLogger(AndroidStreamClient.class.getName());

    private final StreamClientConfiguration configuration;

    public AndroidStreamClient(StreamClientConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public StreamResponseMessage sendRequest(StreamRequestMessage requestMessage) throws InterruptedException {
        try {
            return sendInternal(requestMessage);
        } catch (Exception e) {
            logger.debug("Stream client request failed: {}", e.getMessage());
            return new StreamResponseMessage(UpnpResponse.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private StreamResponseMessage sendInternal(StreamRequestMessage requestMessage) throws IOException {
        URI uri = requestMessage.getUri();
        if (uri == null) {
            return new StreamResponseMessage(UpnpResponse.Status.INTERNAL_SERVER_ERROR);
        }

        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(configuration.getTimeoutSeconds() * 1000);
            connection.setInstanceFollowRedirects(true);

            UpnpRequest.Method method = requestMessage.getOperation().getMethod();
            connection.setRequestMethod(method.getHttpName());

            // Headers
            UpnpHeaders headers = requestMessage.getHeaders();
            if (headers != null) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    String name = entry.getKey();
                    List<String> values = entry.getValue();
                    if (values != null) {
                        for (String value : values) {
                            connection.setRequestProperty(name, value);
                        }
                    }
                }
            }
            connection.setRequestProperty("User-Agent", configuration.getUserAgentValue(2, 0));

            // Body
            if (requestMessage.hasBody()) {
                connection.setDoOutput(true);
                byte[] body = requestMessage.getBodyBytes();
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }
            }

            int status = connection.getResponseCode();

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream()) {
                if (is != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                }
            }

            StreamResponseMessage response = new StreamResponseMessage(
                    new UpnpResponse(status, connection.getResponseMessage()),
                    bos.toByteArray());

            UpnpHeaders responseHeaders = new UpnpHeaders();
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() != null) {
                    responseHeaders.put(entry.getKey(), entry.getValue());
                }
            }
            response.setHeaders(responseHeaders);

            return response;
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public StreamClientConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public void stop() {
        // Nothing to release — HttpURLConnection is stateless.
    }
}
