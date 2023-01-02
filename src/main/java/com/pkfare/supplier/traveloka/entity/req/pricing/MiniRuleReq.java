package com.pkfare.supplier.traveloka.entity.req.pricing;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@Data
public class MiniRuleReq {

    private String journeyType;

    private List<String> flightIds;
}
