package com.kingdom.pojo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * <h3>kingdom-parent</h3>
 * <p>基金备选库表</p>
 *
 * @author : HuangJingChao
 * @date : 2020-06-27 23:17
 **/
@Data
public class FundAlternate implements Serializable {
    private Integer stockAlternateId;
    private String stockType;
    private String name;
    private String code;
    private String riskType;
    private BigDecimal prevClose;
    private BigDecimal upAndDown;
    private BigDecimal open;
    private Integer createdTime;
    private Integer updatedTime;
    private Integer ruleId;
    private BigDecimal valueNow;
    private BigDecimal peRatio;
    private String corporation;
}
