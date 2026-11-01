package com.kiyasu.chat.service;

import com.kiyasu.chat.util.OnlineRegistryUtil;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static com.kiyasu.chat.service.SendMsgServiceTest.awaitUntil;
import static com.kiyasu.chat.service.SendMsgServiceTest.countWriterThreads;
import static org.junit.Assert.*;

public class ClientServiceProtocolLineEndingTest {

    @Test(timeout = 5000)
    public void protocolLineShouldEndWithLFWithoutCR() throws Exception {

        InetAddress loopback = InetAddress.getLoopbackAddress();
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        try (ServerSocket listener = new ServerSocket(0, 50, loopback);
             Socket clientSide = new Socket(loopback, listener.getLocalPort());
             Socket serverSide = listener.accept()) {

            long writerThreadsBefore = countWriterThreads();
            ClientService client = new ClientService(
                    serverSide, new LinkedBlockingQueue<>(), registry, 1000);

            assertTrue(registry.register("Alice", client));

            Field userIdField = ClientService.class.getDeclaredField("userId");
            userIdField.setAccessible(true);
            userIdField.set(client, "Alice");

            assertSame(client, registry.getClient("Alice"));

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> session = null;

            try {
                session = executor.submit(client);
                clientSide.setSoTimeout(1000);

                InputStream in = clientSide.getInputStream();
                byte[] line = readOneLine(in);

                assertFalse(containsCR(line));

                assertEquals("这里是kiyasu的聊天室, 输入回车继续...",
                        new String(line, StandardCharsets.UTF_8));
                clientSide.getOutputStream().write(
                        "\r\n".getBytes(StandardCharsets.UTF_8));
                clientSide.getOutputStream().flush();
                line = readOneLine(clientSide.getInputStream());
                assertEquals("请输入命令: ", new String(line, StandardCharsets.UTF_8));
                assertFalse(containsCR(line));
                clientSide.getOutputStream().write(
                        "004\r\n".getBytes(StandardCharsets.UTF_8));
                clientSide.getOutputStream().flush();
                line = readOneLine(clientSide.getInputStream());
                assertEquals("104,SUCCESS,Alice", new String(line, StandardCharsets.UTF_8));
                assertFalse(containsCR(line));

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

    private static boolean containsCR(byte[] line) {
        boolean withR = false;
        for (byte value : line) {
            if (value == '\r') {
                withR = true;
                break;
            }
        }
        return withR;
    }

    /**
     * 循环读字节直到 '\n'，返回不含 '\n' 的字节；未遇 '\n' 就 -1 时抛 AssertionError
     */
    private static byte[] readOneLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return buf.toByteArray();
            buf.write(b);
        }
        throw new AssertionError("对端在行尾前关闭连接");
    }

}
