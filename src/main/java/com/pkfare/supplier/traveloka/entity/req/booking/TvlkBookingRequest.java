package com.pkfare.supplier.traveloka.entity.req.booking;

import com.pkfare.supplier.traveloka.entity.constant.TravelokaConstant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class TvlkBookingRequest {
  /** 联系人信息 */
  private TvlkContactDetail contactDetail;

  /** 乘客信息 */
  private TvlkPassengers passengers;

  /** 预订行程id：search返回的相应行程的flightId 组成的集合 */
  private List<String> flightIds;

  /** 行程类型：ONE_WAY，BASIC_RT，PACKAGE_RT */
  private TravelokaConstant.JourneyType journeyType;

  /** 语言和地区，格式：<language>_<country>；例如：en_ID表示英语、印尼 */
  private String locale;

  /** 用户名 */
  private String username;

  /** 登录账号 */
  private String loginID;

  /** 登录类型：EMAIL，PN */
  private String loginType;

  /** 客户登录账号 */
  private String customerLoginID;

  /** 客户登录类型：EMAIL，PN */
  private String customerLoginType;
}
