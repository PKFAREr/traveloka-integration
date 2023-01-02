package com.pkfare.supplier.traveloka.entity.req.booking;

import com.pkfare.supplier.traveloka.entity.constant.TravelokaConstant;
import lombok.Data;

import java.util.List;

@Data
public class TvlkPricingResultForBooking {

  private Integer needNIK;


  /** tvlk pricing 的 flightIds */
  private List<String> flightIds;

  /** tvlk 对应的航程类型 */
  private TravelokaConstant.JourneyType journeyType;

//  Map<Constant.PsgType, PriceResp> priceRespMap;
}
