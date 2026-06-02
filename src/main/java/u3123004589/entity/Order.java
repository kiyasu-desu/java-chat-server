package u3123004589.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import u3123004589.common.Constant;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Order {
    //命令
    private Constant.OrderType order;
    //数据
    private String content;
}
