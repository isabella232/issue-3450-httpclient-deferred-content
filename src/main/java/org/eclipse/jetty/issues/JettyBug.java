package org.eclipse.jetty.issues;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.client.util.OutputStreamContentProvider;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class JettyBug
{
    public static void main(final String[] args) throws Exception
    {
        final Server server = new Server(8080);
        server.setHandler(new MyHandler());

        server.start();

        final HttpClient httpClient = new HttpClient();

        httpClient.setExecutor(new QueuedThreadPool(100, 100));
        httpClient.setMaxConnectionsPerDestination(10);
        httpClient.setTCPNoDelay(true);
        httpClient.setCookieStore(new HttpCookieStore.Empty());
        httpClient.setAddressResolutionTimeout(TimeUnit.SECONDS.toMillis(5));
        httpClient.setConnectTimeout(TimeUnit.SECONDS.toMillis(5));
        httpClient.setIdleTimeout(TimeUnit.SECONDS.toMillis(5));

        httpClient.start();

        try
        {

            for (int loop = 1; loop < 100; loop++)
            {
                System.out.println("non-deferred iteration " + loop);

                sendRequest(httpClient, false);
            }

            for (int loop = 1; loop < 100; loop++)
            {
                System.out.println("deferred iteration " + loop);

                sendRequest(httpClient, true);
            }
        }
        finally
        {
            stop(httpClient);
            stop(server);
        }
    }

    private static void stop(LifeCycle lifeCycle)
    {
        try
        {
            lifeCycle.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static byte[] createPayloadBytes()
    {
        final byte[] someBytes = new byte[1111];
        Arrays.fill(someBytes, (byte) 1);

        return someBytes;
    }

    private static void sendRequest(final HttpClient httpClient,
                                    final boolean isDeferred) throws Exception
    {
        final Request request = httpClient.POST("http://localhost:8080");
        request.timeout(60, TimeUnit.SECONDS);
        request.idleTimeout(60, TimeUnit.SECONDS);

        final byte[] payloadOutBytes = createPayloadBytes();

        final InputStreamResponseListener listener = new InputStreamResponseListener();

        if (isDeferred)
        {
            final OutputStreamContentProvider contentProvider = new OutputStreamContentProvider();
            request.content(contentProvider);
            request.send(listener);

            try (final OutputStream out = contentProvider.getOutputStream())
            {
                out.write(payloadOutBytes);
            }
        }
        else
        {
            final BytesContentProvider content = new BytesContentProvider(payloadOutBytes);
            request.content(content);
            request.send(listener);
        }

        final Response response = listener.get(60, TimeUnit.SECONDS);

        if (response.getStatus() != 200)
        {
            throw new IOException("Status=" + response.getStatus());
        }

        try (ByteArrayOutputStream result = new ByteArrayOutputStream();
             InputStream in = listener.getInputStream())
        {
            IO.copy(in, result);
            if (!Arrays.equals(payloadOutBytes, result.toByteArray()))
            {
                throw new IllegalStateException("Payload bytes not identical");
            }
        }
        System.out.println("Finished");
    }

    public static class MyHandler extends AbstractHandler
    {
        @Override
        public final void handle(final String target,
                                 final org.eclipse.jetty.server.Request request,
                                 final HttpServletRequest servletRequest,
                                 final HttpServletResponse servletResponse) throws IOException
        {
            System.out.printf("handle(): Content-Length: %,d / Transfer-Encoding: %s%n", servletRequest.getContentLength(), servletRequest.getHeader("Transfer-Encoding"));
            ByteArrayOutputStream reqbytes = new ByteArrayOutputStream();
            IO.copy(servletRequest.getInputStream(), reqbytes);
            System.out.printf("read %,d bytes%n", reqbytes.toByteArray().length);
            ByteArrayInputStream respbytes = new ByteArrayInputStream(reqbytes.toByteArray());
            IO.copy(respbytes, servletResponse.getOutputStream());
            servletResponse.setStatus(HttpStatus.OK_200);
            request.setHandled(true);
        }
    }
}