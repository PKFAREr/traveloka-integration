package com.pkfare.supplier.traveloka.entity.req.booking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class TvlkContactDetail {

  /** 代理商邮箱，将作为TVLK侧的预订账号 */
  private String email;

  /** 联系人名 */
  private String firstName;

  /** 联系人姓 */
  private String lastName;

  /** 代理商电话，将作为TVLK侧的预订账号 */
  private String phoneNumber;

  /** 代理商电话国家区号 */
  private String phoneNumberCountryCode;

  /** 客户邮箱 */
  private String customerEmail;

  /** 客户电话 */
  private String customerPhoneNumber;

  /** 客户电话国家区号 */
  private String customerPhoneNumberCountryCode;
}
