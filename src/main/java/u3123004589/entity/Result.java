package u3123004589.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import u3123004589.common.Constant;

/**
 * 结果数据类
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Result {
    //结果类型
    private Constant.ResultType result;
    //附加消息
    private String info;

    @Override
    public String toString() {

        if (result == Constant.ResultType.SUCCESS && info != null) {
            return result.toString();
        } else {
            return result + " " + info;
        }

    }
}
