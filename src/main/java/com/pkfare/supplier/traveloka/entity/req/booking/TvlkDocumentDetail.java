package com.pkfare.supplier.traveloka.entity.req.booking;

import com.pkfare.supplier.traveloka.entity.constant.TravelokaConstant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class TvlkDocumentDetail {
  /** 发证地 */
  private String issuingCountry;

  /** 证件号码 */
  private String documentNo;

  /** 证件过期时间，格式：MM-DD-YYYY */
  private String expirationDate;

  /** 发证日期，格式：MM-DD-YYYY */
  private String issuanceDate;

  /**
   * 证件类型：NATIONAL_ID，PASSPORT，VISA，BIRTH_CERTIFICATE，DRIVING_LICENSE，OTHERS
   * PKFARE的P对应PASSPORT，PKFARE的N对应NATIONAL_ID，PKFARE的O对应OTHERS
   */
  private TravelokaConstant.DocumentType documentType;
}
