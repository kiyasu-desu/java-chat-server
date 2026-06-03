package u3123004589.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import u3123004589.common.Constant;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class User {
    //id
    private String id;
    //密码
    private String password;
    //登录状态
    private Constant.UserStatus status;
}
