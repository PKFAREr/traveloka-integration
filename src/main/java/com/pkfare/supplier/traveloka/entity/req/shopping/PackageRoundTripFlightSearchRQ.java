package com.pkfare.supplier.traveloka.entity.req.shopping;

import lombok.Data;

import java.util.List;



/**
 * 非印尼境内往返入参
 * @author cz.jay
 * @date 2022-05-13
 */
@Data
public class PackageRoundTripFlightSearchRQ {
  private List<JourneyReq> journeys;
  public PassengerReq passengers;
}
