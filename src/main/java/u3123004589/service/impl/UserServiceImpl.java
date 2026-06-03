package u3123004589.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import u3123004589.common.Constant;
import u3123004589.dao.UserDao;
import u3123004589.entity.Message;
import u3123004589.entity.User;
import u3123004589.service.UserService;
import u3123004589.util.Md5Util;
import u3123004589.util.MyBatisUtil;

@Slf4j
public class UserServiceImpl implements UserService {

    @Override
    public Constant.ResultType register(String id, String password) {
        try (SqlSession session = MyBatisUtil.getSqlSessionFactory().openSession(true)) {
            UserDao userDao = session.getMapper(UserDao.class);

            if (userDao.getUserById(id) != null) {
                log.warn("{} 注册失败: 用户ID已存在", id);
                return Constant.ResultType.FAIL;
            }

            User user = User.builder()
                    .id(id)
                    .password(Md5Util.md5(password))
                    .status(Constant.UserStatus.OFFLINE)
                    .build();

            if (1 == userDao.insertUser(user)) {
                log.info("{} 注册成功", id);
                return Constant.ResultType.SUCCESS;
            } else {
                log.warn("{} 注册失败: 数据库插入失败", id);
                return Constant.ResultType.FAIL;
            }

        } catch (Exception e) {
            log.error("{}注册失败: {}", id, e.getMessage());
            return Constant.ResultType.FAIL;
        }
    }

    @Override
    public Constant.ResultType login(String id, String password) {
        return null;
    }

    @Override
    public Constant.ResultType logout(String id) {
        return null;
    }

    @Override
    public Constant.ResultType search() {
        return null;
    }

    @Override
    public Constant.ResultType sendMessage(Message message) {
        return null;
    }
}
