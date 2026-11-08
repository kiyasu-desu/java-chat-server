package com.kiyasu.chat.controller;

import com.kiyasu.chat.entity.Message;
import com.kiyasu.chat.util.OnlineRegistryUtil;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static com.kiyasu.chat.service.SendMsgServiceTest.awaitUntil;
import static com.kiyasu.chat.service.SendMsgServiceTest.countWriterThreads;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServerTest {

    /**
     * 过载连接应在没有额外写入器的情况下关闭
     */
    @Test
    public void overloadConnectionShouldBeClosedWithoutExtraWriter() throws Exception {
        long baseWriters = countWriterThreads();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ExecutorService executorService = Server.newClientPool(2);
        BlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>(10);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        try (ServerSocket listener = new ServerSocket(0, 50, loopback);
             Socket c1 = new Socket(loopback, listener.getLocalPort());
             Socket c2 = new Socket(loopback, listener.getLocalPort());
             Socket c3 = new Socket(loopback, listener.getLocalPort());
             BufferedReader br1 = new BufferedReader(new InputStreamReader(c1.getInputStream()));
             BufferedReader br2 = new BufferedReader(new InputStreamReader(c2.getInputStream()));
             Socket s1 = listener.accept();
             Socket s2 = listener.accept();
             Socket s3 = listener.accept()) {

            c1.setSoTimeout(1000);
            c2.setSoTimeout(1000);
            c3.setSoTimeout(1000);

            Server.dispatchClient(s1, executorService, sendQueue, registry);
            Server.dispatchClient(s2, executorService, sendQueue, registry);

            assertEquals("这里是kiyasu的聊天室, 输入回车继续...", br1.readLine());
            assertEquals("这里是kiyasu的聊天室, 输入回车继续...", br2.readLine());

            long writersWhenFull = countWriterThreads();
            Server.dispatchClient(s3, executorService, sendQueue, registry);

            assertTrue("超额连接的服务端 Socket 应立即关闭", s3.isClosed());
            assertEquals("超额连接对端应读到 EOF", -1, c3.getInputStream().read());
            assertEquals("超额连接不应新增写线程", writersWhenFull, countWriterThreads());

            c1.getOutputStream().write("\r\n".getBytes(StandardCharsets.UTF_8));
            c1.getOutputStream().flush();
            assertEquals("请输入命令: ", br1.readLine());
            c1.getOutputStream().write("004\r\n".getBytes(StandardCharsets.UTF_8));
            c1.getOutputStream().flush();
            assertEquals("104,SUCCESS,当前没有在线用户", br1.readLine());

            c1.close();

            ThreadPoolExecutor pool = (ThreadPoolExecutor) executorService;
            assertTrue(awaitUntil(() -> pool.getCompletedTaskCount() == 1 && pool.getActiveCount() == 1, 1000));

            try (Socket c4 = new Socket(loopback, listener.getLocalPort());
                 BufferedReader br4 = new BufferedReader(new InputStreamReader(c4.getInputStream()));
                 Socket s4 = listener.accept()) {

                c4.setSoTimeout(1000);
                Server.dispatchClient(s4, executorService, sendQueue, registry);
                assertEquals("这里是kiyasu的聊天室, 输入回车继续...", br4.readLine());

            }

        } finally {
            executorService.shutdownNow();
        }
        assertTrue(executorService.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(awaitUntil(() -> countWriterThreads() == baseWriters, 1000));
    }

    /**
     * 初始化失败不应中止后续调度
     */
    @Test
    public void initializationFailureShouldNotAbortSubsequentDispatch() throws Exception {
        long baseWriters = countWriterThreads();
        ExecutorService executorService = Server.newClientPool(1);
        InetAddress loopback = InetAddress.getLoopbackAddress();
        BlockingQueue<Message> sendQueue = new LinkedBlockingQueue<>(10);
        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        try (ServerSocket listener = new ServerSocket(0, 50, loopback)) {

            try (Socket c1 = new Socket(loopback, listener.getLocalPort());
                 Socket s1 = listener.accept()) {
                s1.close();
                Server.dispatchClient(s1, executorService, sendQueue, registry);

                ThreadPoolExecutor pool = (ThreadPoolExecutor) executorService;
                assertTrue(awaitUntil(() -> pool.getCompletedTaskCount() == 1 && pool.getActiveCount() == 0, 1000));
            }

            try (Socket c2 = new Socket(loopback, listener.getLocalPort());
                 BufferedReader br2 = new BufferedReader(new InputStreamReader(c2.getInputStream()));
                 Socket s2 = listener.accept()) {
                c2.setSoTimeout(1000);
                Server.dispatchClient(s2, executorService, sendQueue, registry);
                assertEquals("这里是kiyasu的聊天室, 输入回车继续...", br2.readLine());
            }

        } finally {
            executorService.shutdownNow();
        }
        assertTrue(executorService.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(awaitUntil(() -> countWriterThreads() == baseWriters, 1000));
    }

}
