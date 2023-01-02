package com.pkfare.supplier.traveloka.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TvlkAreaVO {

    /**
     * traveloka 地区城市码
     */
    private String tvlkAreaCode;

    /**
     * pk 地区城市码
     */
    private String pkfAreaCode;

}
