package com.pkfare.supplier.traveloka.entity.req.shopping;

import lombok.Data;

/**
 * 单程/印尼境内往返shopping入参
 *
 * @author cz.jay
 * @date 2022-05-13
 */
@Data
public class FlightSearchRQ {
  private JourneyReq journey;
  public PassengerReq passengers;
}
