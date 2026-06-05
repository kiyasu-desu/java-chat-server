package u3123004589.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import u3123004589.common.Constant;
import u3123004589.dao.UserDao;
import u3123004589.entity.*;
import u3123004589.service.impl.MessageServiceImpl;
import u3123004589.service.impl.OrderServiceImpl;
import u3123004589.util.Md5Util;
import u3123004589.util.MyBatisUtil;
import u3123004589.util.OnlineRegistryUtil;

import java.io.*;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.BlockingQueue;

@Slf4j
public class ClientService implements Runnable {
    private final Socket client;
    private final BlockingQueue<Message> sendQueue; //发送消息队列
    private String userId;
    private final OnlineRegistryUtil registry;
    private final PrintWriter out;

    public ClientService(Socket client, BlockingQueue<Message> sendQueue, OnlineRegistryUtil registry) throws IOException {
        this.client = client;
        this.sendQueue = sendQueue;
        this.registry = registry;
        this.out = new PrintWriter(new OutputStreamWriter(client.getOutputStream()), true);
    }

    public synchronized boolean send(String text) {
        try {
            out.println(text);
            out.flush();
            return true;
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void run() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(client.getInputStream()))) {

            out.println("这里是3123004589的聊天室，输入回车继续...");
            br.readLine();
            out.println("请输入命令：");

            while (true) {
                String inputLine = br.readLine();
                if (inputLine == null) {
                    log.info("客户端 {} 连接已关闭", client.getRemoteSocketAddress());
                    break;
                }

                OrderService orderService = new OrderServiceImpl();
                Order order = orderService.splitOrder(inputLine);
                if (order == null) {
                    out.println(Constant.ServerOrder.NO_MEANS_REQ + "，命令格式错误");
                    continue;
                }

                Response response = runOrder(order);
                out.println(response);
                if (response.getOrderType() == Constant.ServerOrder.LOGOUT_RES
                        && response.getInfo().getResult() == Constant.ResultType.SUCCESS) {
                    break;
                }
            }
        } catch (IOException e) {
            log.error("处理客户端 {} 的请求时发生错误: {}", client.getRemoteSocketAddress(), e.getMessage());
        } finally {
            if (userId != null) {
                registry.unregister(userId);
            }
            try {
                client.close();
            } catch (IOException e) {
                log.error("关闭客户端 {} 连接时发生错误: {}", client.getRemoteSocketAddress(), e.getMessage());
            }
        }
    }

    private Response runOrder(Order order) {
        Response response = new Response();
        Result result = new Result();

        if (order == null || order.getOrder() == null) {
            log.error("命令解析错误: {}", order);
            response.setOrderType(Constant.ServerOrder.NO_MEANS_REQ);
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo("命令解析错误");
            response.setInfo(result);
            return response;
        }

        switch (order.getOrder()) {
            //注册
            case REGISTER_REQ -> {
                response.setOrderType(Constant.ServerOrder.REGISTER_RES);
                String[] params = order.getContent().split(",");
                if (params.length != 2) {
                    log.warn("注册请求参数错误: {}", order.getContent());
                    result.setResult(Constant.ResultType.FAIL);
                    result.setInfo("注册请求参数错误");
                } else {
                    result = register(params[0].trim(), params[1].trim());
                }
            }

            //登录
            case LOGIN_REQ -> {
                response.setOrderType(Constant.ServerOrder.LOGIN_RES);
                String[] params = order.getContent().split(",");
                if (params.length != 2) {
                    log.warn("登录请求参数错误: {}", order.getContent());
                    result.setResult(Constant.ResultType.FAIL);
                    result.setInfo("登录请求参数错误");
                } else {
                    result = login(params[0].trim(), params[1].trim());
                }
            }

            //登出
            case LOGOUT_REQ -> {
                if (!isLogin()) {
                    log.warn("登出请求失败: 用户未登录");
                    response.setOrderType(Constant.ServerOrder.LOGOUT_RES);
                    result.setResult(Constant.ResultType.FAIL);
                    result.setInfo("登出请求失败: 用户未登录");
                    response.setInfo(result);
                    return response;
                }
                response.setOrderType(Constant.ServerOrder.LOGOUT_RES);
                String id = order.getContent();

                if (id == null || id.isBlank()) {
                    log.warn("登出请求参数错误: {}", order.getContent());
                    result.setResult(Constant.ResultType.FAIL);
                    result.setInfo("登出请求参数错误");
                } else if (!userId.equals(id.trim())) {
                    log.warn("登出请求参数错误: 用户 {} 试图登出用户 {}", userId, id);
                    result.setResult(Constant.ResultType.FAIL);
                    result.setInfo("登出请求参数错误: 无权登出其他用户");
                } else {
                    result = logout(id.trim());
                    if (result.getResult() == Constant.ResultType.SUCCESS) {
                        userId = null;
                    }
                }
            }

            //搜索在线
            case FIND_REQ -> {
                response.setOrderType(Constant.ServerOrder.FIND_RES);
                List<String> ids = search();
                result.setResult(Constant.ResultType.SUCCESS);
                if (ids != null) {
                    result.setInfo(ids.isEmpty() ? "当前没有在线用户" : String.join(",", ids));
                }
            }

            //发消息
            case SEND_MSG_REQ -> {
                response.setOrderType(Constant.ServerOrder.NO_MEANS_REQ);
                MessageService messageService = new MessageServiceImpl();
                Message message = messageService.splitMessage(order.getContent());
                if (null == message) {
                    log.warn("发送消息请求参数错误: {}", order.getContent());
                    result.setResult(Constant.ResultType.FAIL);
                    result.setInfo("发送消息请求参数错误");
                } else {
                    result = sendMessage(message);
                }
            }
        }
        response.setInfo(result);
        return response;
    }

    private Result register(String id, String password) {
        Result result = new Result();
        try (SqlSession session = MyBatisUtil.getSqlSessionFactory().openSession(true)) {
            UserDao userDao = session.getMapper(UserDao.class);

            if (userDao.getUserById(id) != null) {
                log.warn("{} 注册失败: 用户ID已存在", id);
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("用户ID已存在");
                return result;
            }

            User user = User.builder()
                    .id(id)
                    .password(Md5Util.md5(password))
                    .build();

            if (1 == userDao.insertUser(user)) {
                log.info("{} 注册成功", id);
                result.setResult(Constant.ResultType.SUCCESS);
                result.setInfo("注册成功");
            } else {
                log.warn("{} 注册失败: 数据库插入失败", id);
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("数据库插入失败");
            }
            return result;

        } catch (Exception e) {
            log.error("{} 注册失败: {}", id, e.getMessage());
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo(e.getMessage());
            return result;
        }
    }

    private Result login(String id, String password) {
        Result result = new Result();
        try (SqlSession session = MyBatisUtil.getSqlSessionFactory().openSession(true)) {
            UserDao userDao = session.getMapper(UserDao.class);

            if (userDao.loginCheck(id, Md5Util.md5(password)) == null) {
                log.warn("{} 登录失败: 用户ID或密码错误", id);
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("用户ID或密码错误");
            } else if (registry.isOnline(id)) {
                log.warn("{} 登录失败: 用户已在线", id);
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("用户已在线");
            } else {
                userId = id;
                registry.register(id, this);
                log.info("{} 登录成功", id);
                result.setResult(Constant.ResultType.SUCCESS);
                result.setInfo("登录成功");
            }
            return result;

        } catch (Exception e) {
            log.error("{}登录失败: {}", id, e.getMessage());
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo(e.getMessage());
            return result;
        }
    }

    private Result logout(String id) {
        Result result = new Result();
        try {

            if (!registry.isOnline(id)) {
                log.warn("{} 登出失败: 用户已离线", id);
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("用户已离线");
            } else {
                registry.unregister(id);
                log.info("{} 登出成功", id);
                result.setResult(Constant.ResultType.SUCCESS);
                result.setInfo("登出成功");
            }
            return result;

        } catch (Exception e) {
            log.error("{} 登出失败: {}", id, e.getMessage());
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo(e.getMessage());
            return result;
        }
    }

    private List<String> search() {
        try {

            List<String> ids = registry.getOnlineIds();
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.error("查询在线用户列表失败: {}", e.getMessage());
            return null;
        }
    }

    private Result sendMessage(Message message) {
        Result result = new Result();
        try {
            if (registry.isOnline(message.getDstId())) {
                log.warn("{} 发送消息失败: 接收者 {} 离线", message.getSrcId(), message.getDstId());
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("接收者离线");
            } else {
                sendQueue.put(message);
                log.info("{} 发送消息给 {}", message.getSrcId(), message.getDstId());
                result.setResult(Constant.ResultType.SUCCESS);
                result.setInfo("消息已发送");
            }
            return result;

        } catch (Exception e) {
            log.error("{} 发送消息失败: {}", message.getSrcId(), e.getMessage());
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo(e.getMessage());
            return result;
        }
    }

    private Boolean isLogin() {
        return userId != null;
    }
}
