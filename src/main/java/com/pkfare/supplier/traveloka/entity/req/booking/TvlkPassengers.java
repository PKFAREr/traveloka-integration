package com.pkfare.supplier.traveloka.entity.req.booking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class TvlkPassengers {
  /** 成人 */
  private List<TvlkPassengerDetail> adults;

  /** 儿童 */
  private List<TvlkPassengerDetail> children;

  /** 婴儿 */
  private List<TvlkPassengerDetail> infants;
}
