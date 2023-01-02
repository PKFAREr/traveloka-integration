package com.pkfare.supplier.traveloka.entity.constant;

/**
 * @author cz.jay
 * @date 2022-05-13
 */
public class TravelokaConstant {


    public static final String YES = "YES";
    public static final String NO = "NO";
    public static final String RETURN_ONLY = "RETURN_ONLY";
    public static final String FULL = "FULL";
    public static final String REGULAR = "REGULAR";
    public static final String VOID = "VOID";
    public static final String NO_SHOW = "NO_SHOW";
    public static final String PERCENT_BASE_FARE = "PERCENT_BASE_FARE";
    public static final String PERCENT_NTA = "PERCENT_NTA";
    public static final String PERCENT_TOTAL_FARE = "PERCENT_TOTAL_FARE";
    public static final String CANCELLATION_FEE = "CANCELLATION_FEE";
    public static final String ADMIN_FEE = "ADMIN_FEE";
    public static final Integer SINGLE_NAME_LENGTH_MIN = 0;
    public static final Integer SINGLE_NAME_LENGTH_MAX = 80;
    public static final Integer NEED_NIK = 1;

    public static final String NATIONALITY = "ID";

    public enum SeatClass {
        ECONOMY, PREMIUM_ECONOMY, BUSINESS, FIRST, PROMO, OTHERS,
        ;

        public static SeatClass getInstance(String name) {
            // CabinType.PERMIUM_ECONOMY 单词拼错……
            if (name.equalsIgnoreCase("PERMIUM_ECONOMY")) {
                return PREMIUM_ECONOMY;
            }
            for (SeatClass seat : values()) {
                if (seat.name().equalsIgnoreCase(name)) {
                    return seat;
                }
            }
            return ECONOMY;
        }
    }

    public static String cabinClassMapping(String key) {
        switch (key) {
            case "BUSINESS":
                return "C";
            case "FIRST":
                return "F";
            case "ECONOMY":
            case "PROMO":
            default:
                return "Y";
        }
    }

    /**
     * 退款状态
     */
    public enum RefundableStatus {
        REFUNDABLE,
        NON_REFUNDABLE,
        UNKNOWN,
    }

    public enum BaggageType {
        KG, PIECE,
    }

    public enum DocumentType {
        NATIONAL_ID,
        PASSPORT,
        OTHERS,
    }

    public enum JourneyType {
        ONE_WAY,
        BASIC_RT,
        PACKAGE_RT
    }


    public enum Title {
        MR,
        MRS,
        MISS
    }
}
