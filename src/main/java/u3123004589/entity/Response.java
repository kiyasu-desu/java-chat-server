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
public class Response {
    private Constant.OrderType orderType;
    private Result info;

    @Override
    public String toString() {
        return orderType.getOrderNo() + " " + info.getInfo();
    }
}
