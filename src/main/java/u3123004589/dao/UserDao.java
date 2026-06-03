package u3123004589.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import u3123004589.common.Constant;
import u3123004589.entity.User;

@Mapper
public interface UserDao {
    //增加用户
    int insertUser(User user);

    //按id查找
    User getUserById(@Param("id") String id);

    //修改登录状态
    int updateLoginStatus(@Param("id") String id, @Param("status") Constant.UserStatus status);
}
