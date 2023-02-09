package com.pkfare.supplier.traveloka;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.pkfare.common.HttpReceive;
import com.pkfare.common.HttpSend;
import com.pkfare.supplier.Context;
import com.pkfare.supplier.ShoppingWorkflow;
import com.pkfare.supplier.bean.configure.SupplierInterfaceConfig;
import com.pkfare.supplier.logics.*;
import com.pkfare.supplier.standard.bean.*;
import com.pkfare.supplier.standard.bean.base.APICodes;
import com.pkfare.supplier.standard.bean.req.CtSearchParam;
import com.pkfare.supplier.standard.bean.res.CtSearchResult;
import com.pkfare.supplier.standard.bean.res.CtShoppingResult;
import com.pkfare.supplier.traveloka.entity.constant.TravelokaConstant;
import com.pkfare.supplier.traveloka.entity.req.shopping.FlightSearchRQ;
import com.pkfare.supplier.traveloka.entity.req.shopping.JourneyReq;
import com.pkfare.supplier.traveloka.entity.req.shopping.PackageRoundTripFlightSearchRQ;
import com.pkfare.supplier.traveloka.entity.req.shopping.PassengerReq;
import com.pkfare.supplier.validation.InvalidInputException;
import io.reactivex.Single;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static com.pkfare.supplier.traveloka.entity.constant.TravelokaConstant.cabinClassMapping;

@Slf4j
@Service
public class TravelokaShoppingWorkflow implements ShoppingWorkflow {

    /**
     * 印尼往返单程限制数
     */
    @Value("${traveloka.rtLimit:30}")
    private Integer travelokaRtLimit;
    @Autowired
    StandardLocations standardLocations;
    @Autowired
    TravelokaAuthorization travelokaAuthorization;

    @Autowired
    @Qualifier("traveloka-location-mapping")
    Locations locations;

    @Override
    public void validateInput(CtSearchParam ctSearchParam, Context context) throws InvalidInputException {
        ShoppingWorkflow.super.validateInput(ctSearchParam, context);
        int total = ctSearchParam.getAdultNumber();
        if (Objects.nonNull(ctSearchParam.getChildNumber())){
            total += ctSearchParam.getChildNumber();
        }
        if (total > 7){
            throw new InvalidInputException("traveloka only allow no more than 7 passengers.");
        }
    }

    @Override
    public Single<CtSearchResult> execute(CtSearchParam ctSearchParam, Context context) {
        SupplierInterfaceConfig onewayConfigure = context.getConfigure("oneWay");
        SupplierInterfaceConfig roundTripConfigure = context.getConfigure("roundTrip");
        SupplierInterfaceConfig packageRoundTripConfigure = context.getConfigure("packageRoundTrip");
        Single<CtSearchResult> single = Single.never();
        String token = travelokaAuthorization.getToken(context);
        if (StringUtils.isEmpty(token)){
            throw new RuntimeException("api auth fail.");
        }
        HttpSend httpSend = context.buildHttpSend().addHeader("Authorization", token);

        httpSend.addHeader("Content-Type", "application/json;");
        FlightSearchRQ depReq = buildRequest(ctSearchParam, TripType.DEPARTURE);
        int passengerCount = ctSearchParam.getAdultNumber() + ctSearchParam.getChildNumber();

        switch (ctSearchParam.getTripType()) {
            case TripType.ONE_WAY:
                single = httpSend.url(onewayConfigure.getUrl()).asyncSend(JSON.toJSONString(depReq))
                        .map(receive -> completable(receive, TripType.DEPARTURE, ctSearchParam))
                        .retryWhen(new Retries(10, 2000));
                break;
            case TripType.ROUND_TRIP:
                if (standardLocations.city2country(ctSearchParam.getFromCity()).equals(standardLocations.city2country(ctSearchParam.getToCity()))) {
                    FlightSearchRQ retReq = buildRequest(ctSearchParam, TripType.RETURN);
                    HttpSend httpSendReturn = context.buildHttpSend().addHeaders(httpSend.getHeaders());

                    single = Single.zip(
                            httpSend.url(roundTripConfigure.getUrl()).asyncSend(JSON.toJSONString(depReq)).map(receive -> completable(receive, TripType.DEPARTURE, ctSearchParam))
                                    .retryWhen(new Retries(10, 2000)),
                            httpSendReturn.url(roundTripConfigure.getUrl()).asyncSend(JSON.toJSONString(retReq)).map(receive -> completable(receive, TripType.RETURN, ctSearchParam))
                                    .retryWhen(new Retries(10, 2000)),
                            this::combine);
                } else {
                    PackageRoundTripFlightSearchRQ req = buildRequest(ctSearchParam);
                    single = httpSend.url(packageRoundTripConfigure.getUrl()).asyncSend(JSON.toJSONString(req)).map(receive -> completable(receive, ctSearchParam))
                            .retryWhen(new Retries(10, 2000));
                }
                break;
            default:
        }
        return single;
    }

    /***
     * parse intl RT result
     */
    private CtSearchResult completable(HttpReceive receive, CtSearchParam ctSearchParam) {
        if (!receive.isOk()){
            return new CtSearchResult(APICodes.Basic.NET_ERR);
        }
        JSONObject response = JSON.parseObject(receive.getReceivePayload());
        if (Objects.isNull(response)){
            return new CtSearchResult(APICodes.Basic.RES_ERR);
        }
        Boolean success = response.getBoolean("success");
        if (BooleanUtils.isNotTrue(success)) {
            String err = response.getString("errorMessage");
            return new CtSearchResult(APICodes.Basic.RES_ERR);
        }
        JSONObject data = response.getJSONObject("data");
        Boolean completed = data.getBoolean("completed");
        if (BooleanUtils.isNotTrue(completed)) {
            throw new RetryException("");
        }
        CtSearchResult ctSearchResult = CtSearchResult.success();
        List<CtShoppingResult> shoppingResultList = Lists.newArrayList();
        List<CtSearchSegment> flightList = Lists.newArrayList();
        ctSearchResult.setFlightList(flightList);
        ctSearchResult.setShoppingResultList(shoppingResultList);

        int passengerCount = ctSearchParam.getAdultNumber() + ctSearchParam.getChildNumber();
        JSONArray departureFlightDetail = data.getJSONArray("departureFlightDetail");
        JSONArray returnFlightDetail = data.getJSONArray("returnFlightDetail");
        Map<String, String> flightIdTable = data.getObject("flightIdTable", Map.class);
        JSONObject fareTables = data.getJSONObject("fareTable");

        int index = 0;
        for (Map.Entry<String, String> entry : flightIdTable.entrySet()) {

            CtShoppingResult ctShoppingResult = new CtShoppingResult();
            shoppingResultList.add(ctShoppingResult);
            //flight id
//            ctShoppingResult.setData(entry.getValue());
            List<CtFlightRef> ctFlightRefs = Lists.newArrayList();
            ctShoppingResult.setFlightRefList(ctFlightRefs);
            List<CtTu> ctTus = Lists.newArrayList();
            ctShoppingResult.setTuList(ctTus);
            JSONObject fareTable = fareTables.getJSONObject(entry.getKey());
            JSONObject partnerFare = fareTable.getJSONObject("partnerFare");

            CtTu ctTu = parseFare(partnerFare);
            ctTus.add(ctTu);
            List<CtFormatBaggageDetail> baggageDetails = Lists.newArrayList();
            ctTu.setFormatBaggageDetailList(baggageDetails);
            String[] indexes = entry.getKey().split("\\.");
            JSONObject dep = departureFlightDetail.getJSONObject(Integer.parseInt(indexes[0]));
            JSONObject ret = returnFlightDetail.getJSONObject(Integer.parseInt(indexes[1]));
            JSONArray depSegments = dep.getJSONArray("segments");
            JSONArray retSegments = ret.getJSONArray("segments");

//            if (depSegments.size() > 2 || retSegments.size() > 2) {
//                continue;
//            }

            List<String> farebasisList = Lists.newArrayList();

            //航程级别索引，往返各从头开始计数
            int segmentIndex = 0;
            for (Object o1 : depSegments) {
                JSONObject segment = (JSONObject) o1;
                CtSearchSegment ctSearchSegment = parseSegment(segment);
                ctSearchSegment.setFlightRefNum(index++);
                flightList.add(ctSearchSegment);

                CtFlightRef ctFlightRef = new CtFlightRef();
                ctFlightRef.setFlightRefNum(ctSearchSegment.getFlightRefNum());
                ctFlightRef.setSegmentNo(TripType.DEPARTURE);
                ctFlightRef.setFlightSeq(segmentIndex++);
                ctFlightRef.setSeatGrade(cabinClassMapping(segment.getString("seatClass")));
                ctFlightRef.setSeatClass(segment.getString("subClass"));
                ctFlightRef.setSeatCount(passengerCount);
                ctFlightRefs.add(ctFlightRef);
                farebasisList.add((String) segment.getOrDefault("fareBasisCode","YRT"));

                JSONArray baggageOptions = segment.getJSONObject("addOns").getJSONArray("baggageOptions");
                if (Objects.nonNull(baggageOptions)){
                    for (Object bo : baggageOptions) {
                        JSONObject baggageOption = (JSONObject)bo;
                        Optional<CtFormatBaggageDetail> ctFormatBaggageDetailOptional = parseBaggage(baggageOption);
                        if (!ctFormatBaggageDetailOptional.isPresent()){
                            continue;
                        }
                        CtFormatBaggageDetail ctFormatBaggageDetail = ctFormatBaggageDetailOptional.get();
                        ctFormatBaggageDetail.setFlightSeq(ctFlightRef.getFlightSeq());
                        ctFormatBaggageDetail.setSegmentNo(1);
                        baggageDetails.add(ctFormatBaggageDetail);
                    }
                }
            }

            segmentIndex = 0;
            for (Object o1 : retSegments) {
                JSONObject segment = (JSONObject) o1;
                CtSearchSegment ctSearchSegment = parseSegment(segment);
                ctSearchSegment.setFlightRefNum(index++);
                flightList.add(ctSearchSegment);

                CtFlightRef ctFlightRef = new CtFlightRef();
                ctFlightRef.setFlightRefNum(ctSearchSegment.getFlightRefNum());
                ctFlightRef.setSegmentNo(TripType.RETURN);
                ctFlightRef.setFlightSeq(segmentIndex++);
                ctFlightRef.setSeatGrade(cabinClassMapping(segment.getString("seatClass")));
                ctFlightRef.setSeatClass(segment.getString("subClass"));
                ctFlightRef.setSeatCount(passengerCount);
                ctFlightRefs.add(ctFlightRef);
                farebasisList.add((String) segment.getOrDefault("fareBasisCode","YRT"));

                JSONArray baggageOptions = segment.getJSONObject("addOns").getJSONArray("baggageOptions");
                if (Objects.nonNull(baggageOptions)){
                    for (Object bo : baggageOptions) {
                        JSONObject baggageOption = (JSONObject)bo;
                        Optional<CtFormatBaggageDetail> ctFormatBaggageDetailOptional = parseBaggage(baggageOption);
                        if (!ctFormatBaggageDetailOptional.isPresent()){
                            continue;
                        }
                        CtFormatBaggageDetail ctFormatBaggageDetail = ctFormatBaggageDetailOptional.get();
                        ctFormatBaggageDetail.setFlightSeq(ctFlightRef.getFlightSeq());
                        ctFormatBaggageDetail.setSegmentNo(2);
                        baggageDetails.add(ctFormatBaggageDetail);
                    }
                }
            }
            ctTu.setValidatingCarrier(depSegments.getJSONObject(0).getString("marketingAirline"));
            ctTu.setFareBasis(StringUtils.join(farebasisList, ";"));
        }
        return ctSearchResult;
    }

    /**
     * parse OW or one side flights of domestic RT
     * */
    private CtSearchResult completable(HttpReceive receive, int type, CtSearchParam ctSearchParam) {
        if (!receive.isOk()){
            return new CtSearchResult(APICodes.Basic.NET_ERR);
        }
        JSONObject response = JSON.parseObject(receive.getReceivePayload());
        if (Objects.isNull(response)){
            return new CtSearchResult(APICodes.Basic.RES_ERR);
        }
        Boolean success = response.getBoolean("success");
        if (BooleanUtils.isNotTrue(success)) {
            String err = response.getString("errorMessage");
            return new CtSearchResult(APICodes.Basic.RES_ERR);
        }
        JSONObject data = response.getJSONObject("data");
        Boolean completed = data.getBoolean("completed");
        if (BooleanUtils.isNotTrue(completed)) {
            throw new RetryException("");
        }
        CtSearchResult ctSearchResult = CtSearchResult.success();
        List<CtShoppingResult> shoppingResultList = Lists.newArrayList();
        List<CtSearchSegment> flightList = Lists.newArrayList();
        ctSearchResult.setFlightList(flightList);
        ctSearchResult.setShoppingResultList(shoppingResultList);

        int passengerCount = ctSearchParam.getAdultNumber() + ctSearchParam.getChildNumber();
        JSONArray array = data.getJSONArray("oneWayFlightSearchResults");
        if (Objects.isNull(array)) {
            array = data.getJSONArray("basicRoundTripFlightSearchResults");
        }
        //结果集级别索引，单次shopping所有结果进行计数
        int index = 0;

        for (Object o : array) {
            JSONObject solution = (JSONObject) o;
            JSONObject journey = (JSONObject) solution.getJSONArray("journeys").get(0);
            JSONArray segments = journey.getJSONArray("segments");

//            if (segments.size() > 2) {
//                continue;
//            }

            CtShoppingResult ctShoppingResult = new CtShoppingResult();
            shoppingResultList.add(ctShoppingResult);
//            ctShoppingResult.setData(solution.getString("flightId"));

            List<CtFlightRef> ctFlightRefs = Lists.newArrayList();
            ctShoppingResult.setFlightRefList(ctFlightRefs);

            List<CtTu> ctTus = Lists.newArrayList();
            ctShoppingResult.setTuList(ctTus);

            JSONObject partnerFare = journey.getJSONObject("fareInfo").getJSONObject("partnerFare");

            CtTu ctTu = parseFare(partnerFare);
            ctTus.add(ctTu);
            List<CtFormatBaggageDetail> baggageDetails = Lists.newArrayList();
            ctTu.setFormatBaggageDetailList(baggageDetails);

            List<String> farebasisList = Lists.newArrayList();

            //航程级别索引，往返各从头开始计数
            int segmentIndex = 0;
            for (Object o1 : segments) {
                JSONObject segment = (JSONObject) o1;

                CtSearchSegment ctSearchSegment = parseSegment(segment);
                ctSearchSegment.setFlightRefNum(index++);
                flightList.add(ctSearchSegment);


                CtFlightRef ctFlightRef = new CtFlightRef();
                ctFlightRef.setFlightRefNum(ctSearchSegment.getFlightRefNum());
                ctFlightRef.setSegmentNo(type);
                ctFlightRef.setFlightSeq(segmentIndex++);
                ctFlightRef.setSeatGrade(cabinClassMapping(segment.getString("seatClass")));
                ctFlightRef.setSeatClass(segment.getString("subClass"));
                ctFlightRef.setSeatCount(passengerCount);
                ctFlightRefs.add(ctFlightRef);

                JSONArray baggageOptions = segment.getJSONObject("addOns").getJSONArray("baggageOptions");
                if (Objects.nonNull(baggageOptions)){
                    for (Object bo : baggageOptions) {
                        JSONObject baggageOption = (JSONObject)bo;
                        Optional<CtFormatBaggageDetail> ctFormatBaggageDetailOptional = parseBaggage(baggageOption);
                        if (!ctFormatBaggageDetailOptional.isPresent()){
                            continue;
                        }
                        CtFormatBaggageDetail ctFormatBaggageDetail = ctFormatBaggageDetailOptional.get();
                        ctFormatBaggageDetail.setFlightSeq(ctFlightRef.getFlightSeq());
                        ctFormatBaggageDetail.setSegmentNo(type);
                        baggageDetails.add(ctFormatBaggageDetail);
                    }
                }

                farebasisList.add((String) segment.getOrDefault("fareBasisCode","2".equals(ctSearchParam.getTripType())?"YRT":"YOW"));
            }
            ctTu.setValidatingCarrier(segments.getJSONObject(0).getString("marketingAirline"));
            ctTu.setFareBasis(StringUtils.join(farebasisList, ";"));
        }
        return ctSearchResult;
    }

    Optional<CtFormatBaggageDetail> parseBaggage(JSONObject baggageOption){
        JSONObject priceWithCurrency = baggageOption.getJSONObject("priceWithCurrency");
        BigDecimal amount = priceWithCurrency.getBigDecimal("amount");
        if (BigDecimal.ZERO.compareTo(amount) != 0){
            return Optional.empty();
        }
        CtFormatBaggageDetail ctFormatBaggageDetail = new CtFormatBaggageDetail();
        String baggageType = baggageOption.getString("baggageType");
        switch (baggageType){
            case "KG":
                ctFormatBaggageDetail.setBaggageWeight(Integer.valueOf(baggageOption.getString("baggageWeight")));
                ctFormatBaggageDetail.setBaggagePiece(-1);
                if (ctFormatBaggageDetail.getBaggageWeight() == 0){
                    return Optional.empty();
                }
                break;
            default :
                ctFormatBaggageDetail.setBaggagePiece(Integer.valueOf(baggageOption.getString("baggageQuantity")));
                if (ctFormatBaggageDetail.getBaggagePiece() == 0){
                    return Optional.empty();
                }
                break;
        }
        ctFormatBaggageDetail.setPassengerType(0);
        return Optional.of(ctFormatBaggageDetail);
    }

    CtSearchSegment parseSegment(JSONObject segment) {
        CtSearchSegment ctSearchSegment = new CtSearchSegment();
        JSONObject departureDetail = segment.getJSONObject("departureDetail");
        ctSearchSegment.setDepAirport(departureDetail.getString("airportCode"));
        if (StringUtils.length(departureDetail.getString("departureTerminal")) < 2){
            ctSearchSegment.setDepTerminal(departureDetail.getString("departureTerminal"));
        }
        String depTime = departureDetail.getString("departureDate") + departureDetail.getString("departureTime");
        ctSearchSegment.setDepTime(Times.of(depTime, "MM-dd-yyyyHH:mm").to("yyyyMMddHHmm"));

        JSONObject arrivalDetail = segment.getJSONObject("arrivalDetail");
        String arrTime = arrivalDetail.getString("arrivalDate") + arrivalDetail.getString("arrivalTime");
        ctSearchSegment.setArrTime(Times.of(arrTime, "MM-dd-yyyyHH:mm").to("yyyyMMddHHmm"));
        ctSearchSegment.setArrAirport(arrivalDetail.getString("airportCode"));
        if (StringUtils.length(arrivalDetail.getString("arrivalTerminal")) < 2){
            ctSearchSegment.setArrTerminal(arrivalDetail.getString("arrivalTerminal"));
        }
        ctSearchSegment.setFlightNumber(segment.getString("flightCode").replace("-",""));
        ctSearchSegment.setMarketingCarrier(segment.getString("operatingAirline"));
        ctSearchSegment.setOperatingCarrier(segment.getString("operatingAirline"));
        if (StringUtils.length(ctSearchSegment.getOperatingCarrier()) != 2){
            ctSearchSegment.setOperatingCarrier(segment.getString("brandAirline"));
            if (StringUtils.length(ctSearchSegment.getOperatingCarrier()) != 2){
                ctSearchSegment.setOperatingCarrier(ctSearchSegment.getMarketingCarrier());
            }
        }
        if (segment.getJSONObject("stopInfo") != null) {
            CtStop ctStop = new CtStop();
            ctStop.setStopAirport(segment.getJSONObject("stopInfo").getString("airportCode"));
            ctSearchSegment.setStops(Lists.newArrayList(ctStop));
        }
        ctSearchSegment.setCodeShare(!ctSearchSegment.getOperatingCarrier().equals(ctSearchSegment.getMarketingCarrier()));
//        ctSearchSegment.setOperatingFlightNo(ctSearchSegment.getFlightNumber());

        return ctSearchSegment;
    }

    CtTu parseFare(JSONObject partnerFare) {
        CtTu ctTu = new CtTu();
        JSONObject adultFare = partnerFare.getJSONObject("adultFare");
        List<CtPrice> ctPrices = Lists.newArrayList();
        ctTu.setPriceList(ctPrices);
        CtPrice adtPrice = new CtPrice();
        adtPrice.setPassengerType(CtPassengeType.ADT.code);
        adtPrice.setPrice(adultFare.getJSONObject("baseFareWithCurrency").getBigDecimal("amount"));
        adtPrice.setTaxFeeAmount(BigDecimal.ZERO);
        JSONObject adtVatWithCurrency = adultFare.getJSONObject("vatWithCurrency");
        if (Objects.nonNull(adtVatWithCurrency)) {
            adtPrice.setTaxFeeAmount((BigDecimal) adtVatWithCurrency.getOrDefault("amount", BigDecimal.ZERO));
        }
        adtPrice.setPublishPrice(adtPrice.getPrice());
        ctPrices.add(adtPrice);
        ctTu.setCurrency(adultFare.getJSONObject("baseFareWithCurrency").getString("currency"));

        JSONObject childFare = partnerFare.getJSONObject("childFare");
        if (Objects.nonNull(childFare)) {
            CtPrice chdPrice = new CtPrice();
            chdPrice.setPassengerType(CtPassengeType.CHD.code);
            chdPrice.setPrice(childFare.getJSONObject("baseFareWithCurrency").getBigDecimal("amount"));
            chdPrice.setTaxFeeAmount(BigDecimal.ZERO);
            JSONObject vatWithCurrency = childFare.getJSONObject("vatWithCurrency");
            if (Objects.nonNull(vatWithCurrency)) {
                chdPrice.setTaxFeeAmount((BigDecimal) vatWithCurrency.getOrDefault("amount", BigDecimal.ZERO));
            }
            chdPrice.setPublishPrice(chdPrice.getPrice());
            ctPrices.add(chdPrice);
        }
        ctTu.setRefundInfoList(Lists.newArrayList());
        ctTu.setChangesInfoList(Lists.newArrayList());
        return ctTu;
    }

    PackageRoundTripFlightSearchRQ buildRequest(CtSearchParam ctSearchParam) {
        PackageRoundTripFlightSearchRQ req = new PackageRoundTripFlightSearchRQ();
        req.setJourneys(Lists.newArrayList());

        JourneyReq dep = new JourneyReq();
        dep.setDepAirportOrAreaCode(locations.convert(ctSearchParam.getFromCity()));
        dep.setArrAirportOrAreaCode(locations.convert(ctSearchParam.getToCity()));
        dep.setDepDate(Dates.of(ctSearchParam.getFromDate(), "yyyyMMdd").to("MM-dd-yyyy"));
        dep.setSeatClass(TravelokaConstant.SeatClass.ECONOMY.name());
        req.getJourneys().add(dep);

        JourneyReq ret = new JourneyReq();
        ret.setDepAirportOrAreaCode(locations.convert(ctSearchParam.getToCity()));
        ret.setArrAirportOrAreaCode(locations.convert(ctSearchParam.getFromCity()));
        ret.setDepDate(Dates.of(ctSearchParam.getRetDate(), "yyyyMMdd").to("MM-dd-yyyy"));
        ret.setSeatClass(TravelokaConstant.SeatClass.ECONOMY.name());
        req.getJourneys().add(ret);

        PassengerReq passengerReq = new PassengerReq();
        passengerReq.setAdult(ctSearchParam.getAdultNumber());
        passengerReq.setChild(ctSearchParam.getChildNumber());
        passengerReq.setInfant(ctSearchParam.getInfantNumber());
        req.setPassengers(passengerReq);
        return req;
    }


    FlightSearchRQ buildRequest(CtSearchParam ctSearchParam, Integer indicator) {
        FlightSearchRQ req = new FlightSearchRQ();
        JourneyReq journeyReq = new JourneyReq();

        if (TripType.DEPARTURE == indicator) {
            journeyReq.setDepAirportOrAreaCode(locations.convert(ctSearchParam.getFromCity()));
            journeyReq.setArrAirportOrAreaCode(locations.convert(ctSearchParam.getToCity()));
            journeyReq.setDepDate(Dates.of(ctSearchParam.getFromDate(), "yyyyMMdd").to("MM-dd-yyyy"));
        } else {
            journeyReq.setDepAirportOrAreaCode(locations.convert(ctSearchParam.getToCity()));
            journeyReq.setArrAirportOrAreaCode(locations.convert(ctSearchParam.getFromCity()));
            journeyReq.setDepDate(Dates.of(ctSearchParam.getRetDate(), "yyyyMMdd").to("MM-dd-yyyy"));
        }
        journeyReq.setSeatClass(TravelokaConstant.SeatClass.ECONOMY.name());
        req.setJourney(journeyReq);
        PassengerReq passengerReq = new PassengerReq();
        passengerReq.setAdult(ctSearchParam.getAdultNumber());
        passengerReq.setChild(ctSearchParam.getChildNumber());
        passengerReq.setInfant(ctSearchParam.getInfantNumber());
        req.setPassengers(passengerReq);
        return req;
    }

    /***
     * domestic RT, request dep/ret individually then combine them
     */
    private CtSearchResult combine(CtSearchResult dep, CtSearchResult ret) {
        if (CollectionUtils.isEmpty(dep.getShoppingResultList()) || CollectionUtils.isEmpty(dep.getShoppingResultList())){
            return new CtSearchResult(APICodes.Basic.NO_RESULT);
        }
        Map<String, CtSearchSegment> segmentMap = Maps.newHashMap();
        CtSearchResult combineResult = CtSearchResult.success();
        Map<Integer, CtSearchSegment> tempMap = Maps.newHashMap();
        combineResult.setShoppingResultList(Lists.newArrayList());
        //航段引用序重排逻辑
        Integer i = 0;
        for (CtSearchSegment seg : dep.getFlightList()) {
            segmentMap.put("d" + seg.getFlightRefNum(), seg);
            seg.setFlightRefNum(i++);
            tempMap.put(seg.getFlightRefNum(), seg);
        }
        for (CtSearchSegment seg : ret.getFlightList()) {
            segmentMap.put("r" + seg.getFlightRefNum(), seg);
            seg.setFlightRefNum(i++);
            tempMap.put(seg.getFlightRefNum(), seg);
        }

        for (CtShoppingResult result : dep.getShoppingResultList()) {
            for (CtFlightRef ref : result.getFlightRefList()) {
                CtSearchSegment segment = segmentMap.get("d" + ref.getFlightRefNum());
                ref.setFlightRefNum(segment.getFlightRefNum());
            }
        }

        for (CtShoppingResult result : ret.getShoppingResultList()) {
            for (CtFlightRef ref : result.getFlightRefList()) {
                CtSearchSegment segment = segmentMap.get("r" + ref.getFlightRefNum());
                ref.setFlightRefNum(segment.getFlightRefNum());
            }
        }

        Set<CtSearchSegment> segmentSet = Sets.newHashSet();

        for (CtShoppingResult depResult : dep.getShoppingResultList()) {
            for (CtShoppingResult retResult : ret.getShoppingResultList()) {

                CtSearchSegment depLast = tempMap.get(depResult.getFlightRefList().get(depResult.getFlightRefList().size() - 1).getFlightRefNum());
                CtSearchSegment retFirst = tempMap.get(retResult.getFlightRefList().get(0).getFlightRefNum());

                if (Duration.between(Times.of(depLast.getArrTime(), "yyyyMMddHHmm").dateTime(),
                        Times.of(retFirst.getDepTime(), "yyyyMMddHHmm").dateTime()).toHours() < 6) {
                    continue;
                }

                CtTu depCtTu = depResult.getTuList().get(0);
                CtTu retCtTu = retResult.getTuList().get(0);

                CtTu combineCtTu = new CtTu();
                combineCtTu.setPriceList(Lists.newArrayList());
                combineCtTu.setFareBasis(depCtTu.getFareBasis() + ";" + retCtTu.getFareBasis());
                Map<Integer, CtPrice> priceMap = depCtTu.getPriceList().stream().collect(Collectors.toMap(CtPrice::getPassengerType, price -> price));
                for (CtPrice retPrice : retCtTu.getPriceList()) {
                    CtPrice combinePrice = new CtPrice();
                    CtPrice depPrice = priceMap.get(retPrice.getPassengerType());
                    combinePrice.setTaxFeeAmount(depPrice.getTaxFeeAmount().add(retPrice.getTaxFeeAmount()));
                    combinePrice.setPrice(depPrice.getPrice().add(retPrice.getPrice()));
                    combinePrice.setPublishPrice(depPrice.getPublishPrice().add(retPrice.getPublishPrice()));
                    combinePrice.setPassengerType(depPrice.getPassengerType());
                    combineCtTu.getPriceList().add(combinePrice);
                }
                combineCtTu.setValidatingCarrier(depCtTu.getValidatingCarrier());
                combineCtTu.setRefundInfoList(Lists.newArrayList());
                combineCtTu.setChangesInfoList(Lists.newArrayList());
                combineCtTu.setFormatBaggageDetailList(Lists.newArrayList());
                combineCtTu.getFormatBaggageDetailList().addAll(depCtTu.getFormatBaggageDetailList());
                combineCtTu.getFormatBaggageDetailList().addAll(retCtTu.getFormatBaggageDetailList());
                combineCtTu.setCurrency(depCtTu.getCurrency());
                CtShoppingResult ctShoppingResult = new CtShoppingResult();
                ctShoppingResult.setFlightRefList(Lists.newArrayList());
                ctShoppingResult.getFlightRefList().addAll(depResult.getFlightRefList());
                ctShoppingResult.getFlightRefList().addAll(retResult.getFlightRefList());
                ctShoppingResult.setData(depResult.getData() + "|" + retResult.getData());
                ctShoppingResult.setTuList(Lists.newArrayList(combineCtTu));
                combineResult.getShoppingResultList().add(ctShoppingResult);

                ctShoppingResult.getFlightRefList().stream().map(CtFlightRef::getFlightRefNum).map(tempMap::get).forEach(segmentSet::add);

                if (combineResult.getShoppingResultList().size() >= travelokaRtLimit){
                    combineResult.setFlightList(Lists.newArrayList(segmentSet));
                    return combineResult;
                }
            }
        }
        combineResult.setFlightList(Lists.newArrayList(segmentSet));
        return combineResult;
    }

}
