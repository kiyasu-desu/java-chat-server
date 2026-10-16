package com.kiyasu.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.kiyasu.chat.common.Constant;

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
        return result + "," + info;
    }
}
