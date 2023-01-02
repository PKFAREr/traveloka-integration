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
public class TvlkPassengerDetail {
  /** 头衔：MR，MRS，MISS */
  private TravelokaConstant.Title title;

  /** 乘客名 */
  private String firstName;

  /** 乘客姓 */
  private String lastName;

  /** 性别，F-女, M-男 */
  private String gender;

  /** 出生日期 */
  private String dateOfBirth;

  /** 证件详情 */
  private TvlkDocumentDetail documentDetail;

  /** 国籍，根据预订前查询的相应航司booking rules要求客户填写 */
  private String nationality;

  /** 出生地 */
  private String birthLocation;

  /** 乘客添加辅营产品信息，默认传空 */
//  private List<SegmentResp.AddOnsResp> addOns;
}
