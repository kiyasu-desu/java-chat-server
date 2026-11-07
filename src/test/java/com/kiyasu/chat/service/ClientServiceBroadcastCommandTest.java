package com.kiyasu.chat.service;

import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.util.OnlineRegistryUtil;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ClientServiceBroadcastCommandTest {

    /**
     * 已登录状态群发消息
     */
    @Test
    public void authenticatedBroadcastCommandShouldEnqueueMessageAndReturnSuccessAck() throws Exception {

        InetAddress loopback = InetAddress.getLoopbackAddress();
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        LinkedBlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>(10);

        try (ServerSocket listener = new ServerSocket(0, 50, loopback);
             Socket clientSide = new Socket(loopback, listener.getLocalPort());
             Socket serverSide = listener.accept()) {

            ClientService client = new ClientService(serverSide, sendQueue, registry, 2000);

            assertTrue(registry.register("Alice", client));

            Field userIdField = ClientService.class.getDeclaredField("userId");
            userIdField.setAccessible(true);
            userIdField.set(client, "Alice");

            assertSame(client, registry.getClient("Alice"));

            ExecutorService executor = Executors.newSingleThreadExecutor();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(clientSide.getInputStream()))) {
                executor.submit(client);

                assertEquals("这里是kiyasu的聊天室, 输入回车继续...", br.readLine());
                clientSide.getOutputStream().write(
                        "\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("请输入命令: ", br.readLine());
                clientSide.getOutputStream().write(
                        "006,hello,world\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("000,SUCCESS,消息已加入发送队列", br.readLine());
                assertEquals(1, sendQueue.size());

                Message tmpMsg = sendQueue.poll();
                assertNotNull(tmpMsg);
                assertTrue(tmpMsg.isBroadcast());
                assertEquals("Alice", tmpMsg.getSrcId());
                assertNull(tmpMsg.getDstId());
                assertEquals("hello,world", tmpMsg.getMsg());


                clientSide.getOutputStream().write(
                        "006,\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("000,SUCCESS,消息已加入发送队列", br.readLine());
                assertEquals(1, sendQueue.size());

                tmpMsg = sendQueue.poll();
                assertNotNull(tmpMsg);
                assertTrue(tmpMsg.isBroadcast());
                assertEquals("Alice", tmpMsg.getSrcId());
                assertNull(tmpMsg.getDstId());
                assertEquals("", tmpMsg.getMsg());


                clientSide.getOutputStream().write(
                        "006\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("000,FAIL,群发消息请求参数为空", br.readLine());
                assertTrue(sendQueue.isEmpty());

            } finally {
                client.close();
                executor.shutdownNow();
            }
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    /**
     * 未登录状态群发消息
     */
    @Test
    public void unauthenticatedBroadcastCommandShouldReturnFailWithoutEnqueueing() throws Exception {

        InetAddress loopback = InetAddress.getLoopbackAddress();
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        LinkedBlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>(10);

        try (ServerSocket listener = new ServerSocket(0, 50, loopback);
             Socket clientSide = new Socket(loopback, listener.getLocalPort());
             Socket serverSide = listener.accept()) {

            ClientService client = new ClientService(serverSide, sendQueue, registry, 2000);

            ExecutorService executor = Executors.newSingleThreadExecutor();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(clientSide.getInputStream()))) {
                executor.submit(client);

                assertEquals("这里是kiyasu的聊天室, 输入回车继续...", br.readLine());
                clientSide.getOutputStream().write(
                        "\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("请输入命令: ", br.readLine());
                clientSide.getOutputStream().write(
                        "006,hello,world\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("000,FAIL,用户未登录", br.readLine());
                assertTrue(sendQueue.isEmpty());

            } finally {
                client.close();
                executor.shutdownNow();
            }
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }

    }

}
