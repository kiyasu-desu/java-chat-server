package u3123004589.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import u3123004589.entity.User;

@Mapper
public interface UserDao {
    //增加用户
    int insertUser(User user);

    //按id查找
    User getUserById(@Param("id") String id);

    //登录验证
    User loginCheck(@Param("id") String id, @Param("password") String password);

}
