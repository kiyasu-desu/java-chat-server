package com.kiyasu.chat.service;

import com.kiyasu.chat.util.OnlineRegistryUtil;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static com.kiyasu.chat.service.SendMsgServiceTest.awaitUntil;
import static com.kiyasu.chat.service.SendMsgServiceTest.countWriterThreads;
import static org.junit.Assert.*;

public class ClientServiceReadTimeoutTest {

    @Test(timeout = 5000)
    public void idleConnectionShouldUnregisterAndClose() throws Exception {

        InetAddress loopback = InetAddress.getLoopbackAddress();
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        try (ServerSocket listener = new ServerSocket(0, 50, loopback);
             Socket clientSide = new Socket(loopback, listener.getLocalPort());
             Socket serverSide = listener.accept()) {

            long writerThreadsBefore = countWriterThreads();
            ClientService client = new ClientService(
                    serverSide, new LinkedBlockingQueue<>(), registry, 100);

            assertTrue(registry.register("Alice", client));

            Field userIdField = ClientService.class.getDeclaredField("userId");
            userIdField.setAccessible(true);
            userIdField.set(client, "Alice");

            assertSame(client, registry.getClient("Alice"));

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> session = null;

            try {
                session = executor.submit(client);

                clientSide.getOutputStream().write(
                        "\r\n".getBytes(StandardCharsets.UTF_8));
                clientSide.getOutputStream().flush();

                session.get(2, TimeUnit.SECONDS);
                assertFalse(registry.isOnline("Alice"));
                assertTrue(serverSide.isClosed());

            } finally {
                if (session != null) {
                    session.cancel(true);
                }
                client.close();
                executor.shutdownNow();
            }

            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            boolean stopped = awaitUntil(
                    () -> countWriterThreads() == writerThreadsBefore, 1000);
            assertTrue("写线程未退出", stopped);

        }

    }

    @Test(timeout = 5000)
    public void peerCloseShouldUnregisterBeforeReadTimeout() throws Exception {

        InetAddress loopback = InetAddress.getLoopbackAddress();
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        try (ServerSocket listener = new ServerSocket(0, 50, loopback);
             Socket clientSide = new Socket(loopback, listener.getLocalPort());
             Socket serverSide = listener.accept()) {

            long writerThreadsBefore = countWriterThreads();
            ClientService client = new ClientService(
                    serverSide, new LinkedBlockingQueue<>(), registry, 2000);


            assertTrue(registry.register("Alice", client));

            Field userIdField = ClientService.class.getDeclaredField("userId");
            userIdField.setAccessible(true);
            userIdField.set(client, "Alice");

            assertSame(client, registry.getClient("Alice"));

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> session = null;

            try {
                session = executor.submit(client);

                clientSide.getOutputStream().write(
                        "\r\n".getBytes(StandardCharsets.UTF_8));
                clientSide.getOutputStream().flush();

                clientSide.close();
                session.get(1, TimeUnit.SECONDS);

                assertFalse(registry.isOnline("Alice"));
                assertTrue(serverSide.isClosed());

            } finally {
                if (session != null) {
                    session.cancel(true);
                }
                client.close();
                executor.shutdownNow();
            }

            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            boolean stopped = awaitUntil(
                    () -> countWriterThreads() == writerThreadsBefore, 1000);
            assertTrue("写线程未退出", stopped);

        }

    }

}
