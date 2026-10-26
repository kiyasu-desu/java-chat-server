package com.kiyasu.chat.util;

import com.kiyasu.chat.service.ClientService;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class OnlineRegistryUtilTest {

    /**
     * 测试同一账号并发登录时，只允许一个连接成功
     */
    @Test
    public void concurrentLoginShouldAllowOnlyOneConnection() throws Exception {
        OnlineRegistryUtil registry = new OnlineRegistryUtil();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try (Socket socketA = new MemorySocket();
             Socket socketB = new MemorySocket();
             ClientService clientA = new ClientService(socketA, new LinkedBlockingQueue<>(), registry);
             ClientService clientB = new ClientService(socketB, new LinkedBlockingQueue<>(), registry)) {


            Future<Boolean> resultA = pool.submit(() -> attemptLogin(registry, barrier, clientA));
            Future<Boolean> resultB = pool.submit(() -> attemptLogin(registry, barrier, clientB));

            boolean successA = resultA.get(5, TimeUnit.SECONDS);
            boolean successB = resultB.get(5, TimeUnit.SECONDS);

            int successCount = (successA ? 1 : 0) + (successB ? 1 : 0);

            assertEquals("同一账号只能有一个连接登录成功", 1, successCount);

            ClientService winner = successA ? clientA : clientB;
            assertSame("注册表应保留成功连接", winner, registry.getClient("Alice"));

        } finally {
            pool.shutdown();
        }

        assertTrue("测试线程池未能停止", pool.awaitTermination(5, TimeUnit.SECONDS));
    }


    /**
     * 测试注销时核对连接身份的保护
     */
    @Test
    public void staleConnectionShouldNotRemoveNewConnection() throws IOException {

        OnlineRegistryUtil registry = new OnlineRegistryUtil();

        try (Socket oldSocket = new MemorySocket();
             Socket newSocket = new MemorySocket();
             ClientService oldClient = new ClientService(oldSocket, new LinkedBlockingQueue<>(), registry);
             ClientService newClient = new ClientService(newSocket, new LinkedBlockingQueue<>(), registry)) {


            assertTrue(registry.register("Alice", oldClient));
            assertTrue(registry.unregister("Alice", oldClient));

            assertTrue(registry.register("Alice", newClient));

            // 模拟旧连接延迟或重复执行清理
            assertFalse(registry.unregister("Alice", oldClient));

            assertSame("旧连接清理不能移除新连接", newClient, registry.getClient("Alice"));

        }

    }

    private boolean attemptLogin(
            OnlineRegistryUtil registry,
            CyclicBarrier barrier,
            ClientService client) throws BrokenBarrierException, InterruptedException, TimeoutException {

        barrier.await(3, TimeUnit.SECONDS);

        return registry.register("Alice", client);
    }

    private static class MemorySocket extends Socket {
        private final OutputStream outputStream = new ByteArrayOutputStream();

        @Override
        public void close() throws IOException {
            outputStream.close();
            super.close();
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }
    }

}
