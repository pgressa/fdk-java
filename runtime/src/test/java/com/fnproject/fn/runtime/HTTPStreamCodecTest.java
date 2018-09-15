package com.fnproject.fn.runtime;


import com.fnproject.fn.api.Headers;
import com.fnproject.fn.api.InputEvent;
import com.fnproject.fn.api.OutputEvent;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 * This uses the Jetty client largely as witness of "good HTTP behaviour"
 * Created on 24/08/2018.
 * <p>
 * (c) 2018 Oracle Corporation
 */
public class HTTPStreamCodecTest {


    private static final Map<String, String> defaultEnv;
    private final List<Runnable> cleanups = new ArrayList<>();

    private static File generateSocketFile() {
        File f = null;
        try {

            f = File.createTempFile("socket", ".sock");
            f.delete();
            f.deleteOnExit();
        } catch (IOException e) {
        }

        return f;
    }

    static {
        System.setProperty("com.fnproject.java.native.libdir", new File("src/main/c/").getAbsolutePath());
        System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");
        System.setProperty("org.eclipse.jetty.LEVEL", "WARN");


        Map<String, String> env = new HashMap<>();
        env.put("FN_APP_NAME", "myapp");
        env.put("FN_PATH", "mypath");

        defaultEnv = Collections.unmodifiableMap(env);
    }

    private HttpClient createClient(File unixSocket) throws Exception {
        HttpClient client = new HttpClient(new HttpClientTransportOverUnixSockets(unixSocket.getAbsolutePath()), null);
        client.start();
        cleanups.add(() -> {
            try {
                client.stop();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return client;
    }

    private Request defaultRequest(HttpClient httpClient) {
        return httpClient.newRequest("http://localhost/call")
          .method("POST")
          .header("Fn-Call-Id", "callID")
          .header("Fn-Deadline", "2002-10-02T10:00:00.992Z")
          .header("Custom-header", "v1")
          .header("Custom-header", "v2")
          .header("Content-Type", "text/plain")
          .content(new StringContentProvider("hello "));

    }


    @After
    public void cleanup() throws Exception {
        cleanups.forEach(Runnable::run);
    }


    public File startCodec(Map<String, String> env, EventCodec.Handler h) {
        Map<String, String> newEnv = new HashMap<>(env);
        File socket = generateSocketFile();
        newEnv.put("FN_LISTENER", "unix:" + socket.getAbsolutePath());

        HTTPStreamCodec codec = new HTTPStreamCodec(newEnv);

        Thread t = new Thread(() -> codec.runCodec(h));
        t.start();
        cleanups.add(() -> {
            try {
                codec.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return socket;
    }

    @Test
    public void shouldAcceptDataOnHttp() throws Exception {
        CompletableFuture<InputEvent> lastEvent = new CompletableFuture<>();

        File socketFile = startCodec(defaultEnv, (in) -> {
            lastEvent.complete(in);
            return OutputEvent.fromBytes("hello".getBytes(), OutputEvent.Status.Success, "text/plain", Headers.emptyHeaders().addHeader("x-test", "bar"));
        });

        HttpClient client = createClient(socketFile);
        ContentResponse resp = client.newRequest("http://localhost/call")
          .method("POST")
          .header("Fn-Call-Id", "callID")
          .header("Fn-Deadline", "2002-10-02T10:00:00.992Z")
          .header("Custom-header", "v1")
          .header("Custom-header", "v2")
          .header("Content-Type", "text/plain")
          .content(new StringContentProvider("hello ")).send();

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getContent()).isEqualTo("hello".getBytes());
        assertThat(resp.getHeaders().get("x-test")).isEqualTo("bar");

        InputEvent evt = lastEvent.get(1, TimeUnit.MILLISECONDS);
        assertThat(evt.getCallID()).isEqualTo("callID");
        assertThat(evt.getDeadline().toEpochMilli()).isEqualTo(1033552800992L);
        assertThat(evt.getHeaders()).isEqualTo(Headers.emptyHeaders().addHeader("Fn-Call-Id", "callID").addHeader("Fn-Deadline", "2002-10-02T10:00:00.992Z").addHeader("Custom-header", "v1", "v2").addHeader("Content-Type", "text/plain"));

    }


    @Test
    public void shouldHandleMultipleRequests() throws Exception {
        AtomicReference<String> lastInput = new AtomicReference<>();
        AtomicInteger count = new AtomicInteger(0);

        File socket = startCodec(defaultEnv, (in) -> {
            lastInput.set(in.consumeBody((is) -> {
                try {
                    return IOUtils.toString(is, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }));
            return OutputEvent.fromBytes(String.format("%d", count.getAndIncrement()).getBytes(), OutputEvent.Status.Success, "text/plain", Headers.emptyHeaders());
        });

        HttpClient httpClient = createClient(socket);

        for (int i = 0; i < 100; i++) {
            ContentResponse resp = httpClient.newRequest("http://localhost/call")
              .method("POST")
              .header("Fn-Call-Id", "callID")
              .header("Fn-Deadline", "2002-10-02T10:00:00.992Z")
              .header("Custom-header", "v1")
              .header("Custom-header", "v2")
              .header("Content-Type", "text/plain")
              .content(new StringContentProvider(String.format("%d", i))).send();

            assertThat(resp.getStatus()).isEqualTo(200);
            assertThat(new String(resp.getContent())).isEqualTo(String.format("%d", i));
            assertThat(lastInput).isNotNull();
            assertThat(lastInput.get()).isEqualTo(String.format("%d", i));
        }

    }

    @Test
    public void shouldHandleLargeBodies() throws Exception {
        // Round trips 10 meg of data through the codec and validates it got the right stuff back
        Random sr = new Random();
        byte[] part = new byte[1024];
        sr.nextBytes(part);

        int size = 1024 * 1024 * 10;
        byte[] randomString = new byte[size];
        for(int i =0 ; i < randomString.length; i += part.length){
            System.arraycopy(part,0,randomString,i,part.length);
        }
        byte[] inDigest = MessageDigest.getInstance("SHA-256").digest(randomString);


        File socket = startCodec(defaultEnv, (in) -> {
            byte[] content = in.consumeBody((is) -> {
                try {
                    return IOUtils.toByteArray(is);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            return OutputEvent.fromBytes(content, OutputEvent.Status.Success, "application/octet-binary", Headers.emptyHeaders());
        });

        HttpClient client = createClient(socket);

        CompletableFuture<Result> cdl = new CompletableFuture<>();
        MessageDigest readDigest = MessageDigest.getInstance("SHA-256");
        defaultRequest(client)
          .content(new BytesContentProvider(randomString))
          .onResponseContent((response, byteBuffer) -> {
              readDigest.update(byteBuffer);
          })
          .send(cdl::complete);
        Result r = cdl.get();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(readDigest.digest()).isEqualTo(inDigest);
    }

    @Test
    public void shouldConvertStatusResponses() throws Exception {

        for (OutputEvent.Status s : OutputEvent.Status.values()) {
            CompletableFuture<InputEvent> lastEvent = new CompletableFuture<>();

            File socket = startCodec(defaultEnv, (in) -> {
                lastEvent.complete(in);
                return OutputEvent.fromBytes("hello".getBytes(), s, "text/plain", Headers.emptyHeaders());
            });

            HttpClient client = createClient(socket);

            ContentResponse resp = defaultRequest(client).send();

            assertThat(resp.getStatus()).isEqualTo(s.getCode());

        }

    }

    @Test
    public void shouldStripHopToHopHeadersFromFunctionInput() throws Exception {

        for (String header[] : new String[][]{
          {"Content-Length", "0"},
          {"Transfer-encoding", "chunked"},
          {"Connection", "close"},
        }) {
            CompletableFuture<InputEvent> lastEvent = new CompletableFuture<>();

            File socket = startCodec(defaultEnv, (in) -> {
                lastEvent.complete(in);
                return OutputEvent.fromBytes("hello".getBytes(), OutputEvent.Status.Success, "text/plain", Headers.emptyHeaders().addHeader(header[0], header[1]));
            });
            HttpClient client = createClient(socket);
            ContentResponse resp = defaultRequest(client).send();

            assertThat(resp.getHeaders().get(header[0])).isNull();

        }
    }

    @Test
    public void socketShouldHaveCorrectPermissions() throws Exception {
        File listener = startCodec(defaultEnv, (in) -> OutputEvent.fromBytes("hello".getBytes(), OutputEvent.Status.Success, "text/plain", Headers.emptyHeaders()));

        assertThat(Files.getPosixFilePermissions(listener.toPath())).isEqualTo(PosixFilePermissions.fromString("rw-rw-rw-"));

        cleanup();
    }
}
