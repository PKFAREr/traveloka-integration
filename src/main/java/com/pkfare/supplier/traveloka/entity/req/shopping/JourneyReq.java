package com.pkfare.supplier.traveloka.entity.req.shopping;

import lombok.Data;

/**
 * @author cz.jay
 * @date 2022-05-19
 */
@Data
public class JourneyReq {

  private String depAirportOrAreaCode;
  private String arrAirportOrAreaCode;
  private String depDate;
  private String seatClass;
}
