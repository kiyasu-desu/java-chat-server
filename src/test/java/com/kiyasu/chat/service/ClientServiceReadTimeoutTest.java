package com.kiyasu.chat.service;

import com.kiyasu.chat.util.OnlineRegistryUtil;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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

    @Test(timeout = 5000)
    public void commandBeforeTimeoutShouldKeepConnectionOnline() throws Exception {

        InetAddress loopback = InetAddress.getLoopbackAddress();
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        try (ServerSocket listener = new ServerSocket(0, 50, loopback);
             Socket clientSide = new Socket(loopback, listener.getLocalPort());
             Socket serverSide = listener.accept()) {

            long writerThreadsBefore = countWriterThreads();
            ClientService client = new ClientService(
                    serverSide, new LinkedBlockingQueue<>(), registry, 2000);

            clientSide.setSoTimeout(1000);

            assertTrue(registry.register("Alice", client));

            Field userIdField = ClientService.class.getDeclaredField("userId");
            userIdField.setAccessible(true);
            userIdField.set(client, "Alice");

            assertSame(client, registry.getClient("Alice"));

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> session = null;

            try (BufferedReader br = new BufferedReader(new InputStreamReader(clientSide.getInputStream()))) {
                session = executor.submit(client);

                assertEquals("这里是kiyasu的聊天室, 输入回车继续...", br.readLine());
                clientSide.getOutputStream().write(
                        "\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("请输入命令: ", br.readLine());
                clientSide.getOutputStream().write(
                        "004\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("104,SUCCESS,Alice", br.readLine());

                assertTrue(registry.isOnline("Alice"));
                assertFalse(session.isDone());
                clientSide.getOutputStream().write(
                        ("004," + "a".repeat(1020) + "\r\n").getBytes(StandardCharsets.UTF_8));
                clientSide.getOutputStream().flush();
                assertEquals("104,SUCCESS,Alice", br.readLine());

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

    /**
     * 不含换行符的慢输入应超时并关闭
     */
    @Test
    public void slowDripInputWithoutNewlineShouldTimeoutAndClose() throws Exception {

        InetAddress loopback = InetAddress.getLoopbackAddress();
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        try (ServerSocket listener = new ServerSocket(0, 50, loopback);
             Socket clientSide = new Socket(loopback, listener.getLocalPort());
             Socket serverSide = listener.accept()) {

            long writerThreadsBefore = countWriterThreads();
            ClientService client = new ClientService(
                    serverSide, new LinkedBlockingQueue<>(), registry, 200);

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

                for (int i = 0; i < 10; i++) {
                    if (session.isDone()) break;
                    try {
                        clientSide.getOutputStream().write(
                                "a".getBytes(StandardCharsets.UTF_8));
                        clientSide.getOutputStream().flush();
                    } catch (IOException e) {
                        break;
                    }
                    Thread.sleep(40);
                }

                session.get(50, TimeUnit.MILLISECONDS);
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

    /**
     * 超长输入行应注销并关闭
     */
    @Test
    public void oversizedInputLineShouldUnregisterAndClose() throws Exception {

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

                clientSide.getOutputStream().write(
                        "a".repeat(1025).getBytes(StandardCharsets.UTF_8));
                clientSide.getOutputStream().flush();

                session.get(500, TimeUnit.MILLISECONDS);

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
