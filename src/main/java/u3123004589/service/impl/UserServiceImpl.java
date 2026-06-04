package u3123004589.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import u3123004589.common.Constant;
import u3123004589.dao.UserDao;
import u3123004589.entity.Message;
import u3123004589.entity.Result;
import u3123004589.entity.User;
import u3123004589.service.UserService;
import u3123004589.util.Md5Util;
import u3123004589.util.MyBatisUtil;

import java.util.List;

@Slf4j
public class UserServiceImpl implements UserService {

    @Override
    public Result register(String id, String password) {
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
                    .status(Constant.UserStatus.OFFLINE)
                    .build();

            if (1 == userDao.insertUser(user)) {
                log.info("{} 注册成功", id);
                result.setResult(Constant.ResultType.SUCCESS);
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

    @Override
    public Result login(String id, String password) {
        Result result = new Result();
        try (SqlSession session = MyBatisUtil.getSqlSessionFactory().openSession(true)) {
            UserDao userDao = session.getMapper(UserDao.class);

            if (userDao.loginCheck(id, Md5Util.md5(password)) == null) {
                log.warn("{} 登录失败: 用户ID或密码错误", id);
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("用户ID或密码错误");
            } else if (userDao.getLoginStatus(id) == Constant.UserStatus.ONLINE) {
                log.warn("{} 登录失败: 用户已在线", id);
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("用户已在线");
            } else {
                userDao.updateLoginStatus(id, Constant.UserStatus.ONLINE);
                log.info("{} 登录成功", id);
                result.setResult(Constant.ResultType.SUCCESS);
            }
            return result;

        } catch (Exception e) {
            log.error("{}登录失败: {}", id, e.getMessage());
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo(e.getMessage());
            return result;
        }
    }

    @Override
    public Result logout(String id) {
        Result result = new Result();
        try (SqlSession session = MyBatisUtil.getSqlSessionFactory().openSession(true)) {
            UserDao userDao = session.getMapper(UserDao.class);

            if (userDao.getLoginStatus(id) == Constant.UserStatus.OFFLINE) {
                log.warn("{} 登出失败: 用户已离线", id);
                result.setResult(Constant.ResultType.FAIL);
                result.setInfo("用户已离线");
            } else {
                userDao.updateLoginStatus(id, Constant.UserStatus.OFFLINE);
                log.info("{} 登出成功", id);
                result.setResult(Constant.ResultType.SUCCESS);
            }
            return result;

        } catch (Exception e) {
            log.error("{} 登出失败: {}", id, e.getMessage());
            result.setResult(Constant.ResultType.FAIL);
            result.setInfo(e.getMessage());
            return result;
        }
    }

    @Override
    public List<String> search() {
        try (SqlSession session = MyBatisUtil.getSqlSessionFactory().openSession(true)) {
            UserDao userDao = session.getMapper(UserDao.class);

            List<String> ids = userDao.getOnlineIds();
            if (ids != null && !ids.isEmpty()) {
                log.info("在线用户列表: {}", ids);
                return ids;
            } else {
                log.info("当前没有在线用户");
                return null;
            }
        } catch (Exception e) {
            log.error("查询在线用户列表失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Result sendMessage(Message message) {
        //todo 发消息
        return null;
    }
}
