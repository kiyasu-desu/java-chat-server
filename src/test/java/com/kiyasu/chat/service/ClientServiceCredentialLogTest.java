package com.kiyasu.chat.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kiyasu.chat.util.OnlineRegistryUtil;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClientServiceCredentialLogTest {

    @Test(timeout = 5000)
    public void malformedCredentialAndUnknownCommandsShouldNotLogPlaintextSecrets() throws Exception {

        InetAddress loopback = InetAddress.getLoopbackAddress();
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Logger logger = (Logger) LoggerFactory.getLogger(ClientService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try (ServerSocket listener = new ServerSocket(0, 50, loopback);
             Socket clientSide = new Socket(loopback, listener.getLocalPort());
             Socket serverSide = listener.accept();
             ClientService service = new ClientService(serverSide, new LinkedBlockingQueue<>(10), registry)) {

            pool.submit(service);

            try (BufferedReader br = new BufferedReader(new InputStreamReader(clientSide.getInputStream()))) {
                assertEquals("这里是kiyasu的聊天室, 输入回车继续...", br.readLine());
                clientSide.getOutputStream().write(
                        "\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("请输入命令: ", br.readLine());

                clientSide.getOutputStream().write(
                        "001,Alice,regSecret123,extra\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("101,FAIL,注册请求参数错误", br.readLine());

                clientSide.getOutputStream().write(
                        "002,Alice,loginSecret456,extra\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("102,FAIL,登录请求参数错误", br.readLine());

                clientSide.getOutputStream().write(
                        "999,Alice,unknownSecret789\r\n".getBytes(StandardCharsets.UTF_8));
                assertEquals("000,FAIL,命令解析错误", br.readLine());

                assertEquals(3, appender.list.size());
                ILoggingEvent event;

                event = appender.list.getFirst();
                assertEquals(Level.WARN, event.getLevel());
                assertEquals(
                        "注册请求参数错误",
                        event.getFormattedMessage()
                );

                event = appender.list.get(1);
                assertEquals(Level.WARN, event.getLevel());
                assertEquals(
                        "登录请求参数错误",
                        event.getFormattedMessage()
                );

                event = appender.list.get(2);
                assertEquals(Level.ERROR, event.getLevel());
                assertEquals(
                        "命令解析错误",
                        event.getFormattedMessage()
                );

            }

        } finally {
            logger.detachAppender(appender);
            appender.stop();
            pool.shutdownNow();
        }
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));

    }

}
