package com.pkfare.supplier.traveloka;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.pkfare.common.HttpSend;
import com.pkfare.supplier.Context;
import com.pkfare.supplier.ShoppingWorkflow;
import com.pkfare.supplier.bean.configure.SupplierInterfaceConfig;
import com.pkfare.supplier.logics.*;
import com.pkfare.supplier.standard.bean.*;
import com.pkfare.supplier.standard.bean.req.CtSearchParam;
import com.pkfare.supplier.standard.bean.res.CtSearchResult;
import com.pkfare.supplier.standard.bean.res.CtShoppingResult;
import com.pkfare.supplier.traveloka.entity.constant.TravelokaConstant;
import com.pkfare.supplier.traveloka.entity.req.shopping.FlightSearchRQ;
import com.pkfare.supplier.traveloka.entity.req.shopping.JourneyReq;
import com.pkfare.supplier.traveloka.entity.req.shopping.PackageRoundTripFlightSearchRQ;
import com.pkfare.supplier.traveloka.entity.req.shopping.PassengerReq;
import com.pkfare.supplier.validation.InvalidInputException;
import com.pkfare.supplier.validation.InvalidOutputException;
import io.reactivex.Single;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.pkfare.supplier.traveloka.entity.constant.TravelokaConstant.cabinClassMapping;

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

    @Override
    public void validateInput(CtSearchParam ctSearchParam, Context context) throws InvalidInputException {

    }

    @Override
    public void validateOutput(CtSearchResult ctSearchResult, Context context) throws InvalidOutputException {

    }

    @Override
    public Single<CtSearchResult> execute(CtSearchParam ctSearchParam, Context context) {
        SupplierInterfaceConfig onewayConfigure = context.getConfigure("oneWay");
        SupplierInterfaceConfig roundTripConfigure = context.getConfigure("roundTrip");
        SupplierInterfaceConfig packageRoundTripConfigure = context.getConfigure("packageRoundTrip");
        Single<CtSearchResult> single = Single.never();
        String token = travelokaAuthorization.getToken(context);
        HttpSend httpSend = context.buildHttpSend().addHeader("Authorization", token);

        httpSend.addHeader("Content-Type", "application/json;");
        FlightSearchRQ depReq = buildRequest(ctSearchParam, TripType.DEPARTURE);

        switch (ctSearchParam.getTripType()) {
            case TripType.ONE_WAY:
                single = httpSend.url(onewayConfigure.getUrl()).asyncSend(JSON.toJSONString(depReq))
                        .map(receive -> completable(receive.getReceivePayload(),TripType.DEPARTURE))
                        .retryWhen(new Retries(10, 2000));
                break;
            case TripType.ROUND_TRIP:
                if (standardLocations.city2country(ctSearchParam.getFromCity()).equals(standardLocations.city2country(ctSearchParam.getToCity()))) {
                    FlightSearchRQ retReq = buildRequest(ctSearchParam, TripType.RETURN);
                    single = Single.zip(
                            httpSend.url(roundTripConfigure.getUrl()).asyncSend(JSON.toJSONString(depReq)).map(receive -> completable(receive.getReceivePayload(),TripType.DEPARTURE))
                                    .retryWhen(new Retries(10, 2000)),
                            httpSend.url(roundTripConfigure.getUrl()).asyncSend(JSON.toJSONString(retReq)).map(receive -> completable(receive.getReceivePayload(),TripType.RETURN))
                                    .retryWhen(new Retries(10, 2000)),
                            this::combine);
                } else {
                    PackageRoundTripFlightSearchRQ req = buildRequest(ctSearchParam);
                    single = httpSend.url(packageRoundTripConfigure.getUrl()).asyncSend(JSON.toJSONString(req)).map(receive -> completable(receive.getReceivePayload()))
                            .retryWhen(new Retries(10, 2000));
                }
                break;
            default:
        }
        return single;
    }

    private CtSearchResult completable(String payload){
        JSONObject response = JSON.parseObject(payload);
        Boolean success = response.getBoolean("success");
        if (!success) {
            throw new RuntimeException();
        }
        JSONObject data = response.getJSONObject("data");
        Boolean completed = data.getBoolean("completed");
        if (!completed) {
            throw new RetryException("");
        }
        CtSearchResult ctSearchResult = new CtSearchResult();
        List<CtShoppingResult> shoppingResultList = Lists.newArrayList();
        List<CtSearchSegment> flightList = Lists.newArrayList();
        ctSearchResult.setFlightList(flightList);
        ctSearchResult.setShoppingResultList(shoppingResultList);

        JSONArray departureFlightDetail = data.getJSONArray("departureFlightDetail");
        JSONArray returnFlightDetail = data.getJSONArray("returnFlightDetail");
        Map<String, String> flightIdTable = data.getObject("flightIdTable",Map.class);
        JSONObject fareTables = data.getJSONObject("fareTable");

        int index = 0;
        for (Map.Entry<String, String> entry : flightIdTable.entrySet()) {

            CtShoppingResult ctShoppingResult = new CtShoppingResult();
            shoppingResultList.add(ctShoppingResult);
            //flight id
            ctShoppingResult.setData(entry.getValue());
            List<CtFlightRef> ctFlightRefs = Lists.newArrayList();
            ctShoppingResult.setFlightRefList(ctFlightRefs);
            List<CtTu> ctTus = Lists.newArrayList();
            ctShoppingResult.setTuList(ctTus);
            JSONObject fareTable = fareTables.getJSONObject(entry.getKey());
            JSONObject partnerFare = fareTable.getJSONObject("partnerFare");

            CtTu ctTu = parseFare(partnerFare);
            ctTus.add(ctTu);

            String[] indexes = entry.getKey().split("\\.");
            JSONObject dep = departureFlightDetail.getJSONObject(Integer.parseInt(indexes[0]));
            JSONObject ret = returnFlightDetail.getJSONObject(Integer.parseInt(indexes[1]));
            JSONArray depSegments = dep.getJSONArray("segments");
            JSONArray retSegments = ret.getJSONArray("segments");

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
                ctFlightRefs.add(ctFlightRef);
                farebasisList.add(segment.getString("fareBasisCode"));

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
                ctFlightRefs.add(ctFlightRef);
                farebasisList.add(segment.getString("fareBasisCode"));
            }
            ctTu.setFareBasis(StringUtils.join(farebasisList, ";"));
        }
        return ctSearchResult;
    }

    private CtSearchResult completable(String payload, int type) {
        JSONObject response = JSON.parseObject(payload);
        Boolean success = response.getBoolean("success");
        if (!success) {
            throw new RuntimeException();
        }
        JSONObject data = response.getJSONObject("data");
        Boolean completed = data.getBoolean("completed");
        if (!completed) {
            throw new RetryException("");
        }
        CtSearchResult ctSearchResult = new CtSearchResult();
        List<CtShoppingResult> shoppingResultList = Lists.newArrayList();
        List<CtSearchSegment> flightList = Lists.newArrayList();
        ctSearchResult.setFlightList(flightList);
        ctSearchResult.setShoppingResultList(shoppingResultList);

        JSONArray array = data.getJSONArray("oneWayFlightSearchResults");

        //结果集级别索引，单次shopping所有结果进行计数
        int index = 0;

        for (Object o : array) {
            JSONObject solution = (JSONObject) o;
            JSONObject journey = (JSONObject) solution.getJSONArray("journeys").get(0);
            JSONArray segments = journey.getJSONArray("segments");
            CtShoppingResult ctShoppingResult = new CtShoppingResult();

            ctShoppingResult.setData(solution.getString("flightId"));

            List<CtFlightRef> ctFlightRefs = Lists.newArrayList();
            ctShoppingResult.setFlightRefList(ctFlightRefs);

            List<CtTu> ctTus = Lists.newArrayList();
            ctShoppingResult.setTuList(ctTus);

            JSONObject partnerFare = journey.getJSONObject("fareInfo").getJSONObject("partnerFare");

            CtTu ctTu = parseFare(partnerFare);
            ctTus.add(ctTu);

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
                ctFlightRefs.add(ctFlightRef);

                farebasisList.add(segment.getString("fareBasisCode"));
            }
            ctTu.setFareBasis(StringUtils.join(farebasisList, ";"));
        }
        return ctSearchResult;
    }

    CtSearchSegment parseSegment(JSONObject segment){
        CtSearchSegment ctSearchSegment = new CtSearchSegment();
        JSONObject departureDetail = segment.getJSONObject("departureDetail");
        ctSearchSegment.setDepAirport(departureDetail.getString("airportCode"));
        ctSearchSegment.setDepTerminal(departureDetail.getString("departureTerminal"));
        String depTime = departureDetail.getString("departureDate") + departureDetail.getString("departureTime");
        ctSearchSegment.setDepTime(Times.of(depTime, "DD-MM-YYYYHH:MM").to("YYYYMMDDHHMM"));
        JSONObject arrivalDetail = segment.getJSONObject("arrivalDetail");
        String arrTime = arrivalDetail.getString("arrivalDate") + arrivalDetail.getString("arrivalTime");
        ctSearchSegment.setArrTime(Times.of(arrTime, "DD-MM-YYYYHH:MM").to("YYYYMMDDHHMM"));
        ctSearchSegment.setArrAirport(arrivalDetail.getString("airportCode"));
        ctSearchSegment.setArrTerminal(arrivalDetail.getString("arrivalTerminal"));
        ctSearchSegment.setFlightNumber(segment.getString("flightCode").replace("-", ""));
        ctSearchSegment.setMarketingCarrier(segment.getString("marketingAirline"));
        ctSearchSegment.setOperatingCarrier(segment.getString("operatingAirline"));
        if (segment.getJSONObject("stopInfo") != null) {
            CtStop ctStop = new CtStop();
            ctStop.setStopAirport(segment.getJSONObject("stopInfo").getString("airportCode"));
            ctSearchSegment.setStops(Lists.newArrayList(ctStop));
        }
        ctSearchSegment.setCodeShare(!ctSearchSegment.getOperatingCarrier().equals(ctSearchSegment.getMarketingCarrier()));
        ctSearchSegment.setOperatingFlightNo(ctSearchSegment.getFlightNumber());

        return ctSearchSegment;
    }

    CtTu parseFare(JSONObject partnerFare){
        CtTu ctTu = new CtTu();
        JSONObject adultFare = partnerFare.getJSONObject("adultFare");
        List<CtPrice> ctPrices = Lists.newArrayList();
        ctTu.setPriceList(ctPrices);
        CtPrice adtPrice = new CtPrice();
        adtPrice.setPassengerType(CtPassengeType.ADT.code);
        adtPrice.setPrice(adultFare.getJSONObject("baseFareWithCurrency").getBigDecimal("amount"));
        adtPrice.setTaxFeeAmount((BigDecimal) adultFare.getJSONObject("vatWithCurrency").getOrDefault("amount", BigDecimal.ZERO));
        adtPrice.setPublishPrice(adtPrice.getPrice());
        ctPrices.add(adtPrice);

        JSONObject childFare = partnerFare.getJSONObject("childFare");
        if (!childFare.isEmpty()) {
            CtPrice chdPrice = new CtPrice();
            chdPrice.setPassengerType(CtPassengeType.CHD.code);
            chdPrice.setPrice(childFare.getJSONObject("baseFareWithCurrency").getBigDecimal("amount"));

            JSONObject vatWithCurrency = childFare.getJSONObject("vatWithCurrency");
            if (Objects.nonNull(vatWithCurrency)) {
                chdPrice.setTaxFeeAmount((BigDecimal) vatWithCurrency.getOrDefault("amount", BigDecimal.ZERO));
            }
            chdPrice.setPublishPrice(chdPrice.getPrice());
            ctPrices.add(chdPrice);
        }
        return ctTu;
    }

    PackageRoundTripFlightSearchRQ buildRequest(CtSearchParam ctSearchParam) {
        PackageRoundTripFlightSearchRQ req = new PackageRoundTripFlightSearchRQ();
        req.setJourneys(Lists.newArrayList());

        JourneyReq dep = new JourneyReq();
        dep.setDepAirportOrAreaCode(ctSearchParam.getFromCity());
        dep.setArrAirportOrAreaCode(ctSearchParam.getToCity());
        dep.setDepDate(Dates.of(ctSearchParam.getFromDate(), "yyyyMMdd").to("MM-dd-yyyy"));
        dep.setSeatClass(TravelokaConstant.SeatClass.ECONOMY.name());
        req.getJourneys().add(dep);

        JourneyReq ret = new JourneyReq();
        ret.setDepAirportOrAreaCode(ctSearchParam.getToCity());
        ret.setArrAirportOrAreaCode(ctSearchParam.getFromCity());
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
            journeyReq.setDepAirportOrAreaCode(ctSearchParam.getFromCity());
            journeyReq.setArrAirportOrAreaCode(ctSearchParam.getToCity());
            journeyReq.setDepDate(Dates.of(ctSearchParam.getFromDate(), "yyyyMMdd").to("MM-dd-yyyy"));
        } else {
            journeyReq.setDepAirportOrAreaCode(ctSearchParam.getToCity());
            journeyReq.setArrAirportOrAreaCode(ctSearchParam.getFromCity());
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

    private CtSearchResult combine(CtSearchResult dep, CtSearchResult ret){
        Map<String, CtSearchSegment> segmentMap = Maps.newHashMap();
        //航段引用序重排逻辑
        AtomicReference<Integer> i = new AtomicReference<>(0);
        dep.getFlightList().forEach(seg-> {
            segmentMap.put("d"+ seg.getFlightRefNum(), seg);
            seg.setFlightRefNum(i.getAndSet(i.get() + 1));
        });
        ret.getFlightList().forEach(seg-> {
            segmentMap.put("r"+seg.getFlightRefNum(),seg);
            seg.setFlightRefNum(i.getAndSet(i.get() + 1));
        });

        for (CtShoppingResult depResult : dep.getShoppingResultList()) {
            for (CtShoppingResult retResult : ret.getShoppingResultList()) {
                depResult.getFlightRefList().forEach(ref-> {
                    CtSearchSegment segment = segmentMap.get("d"+ref.getFlightRefNum());
                    ref.setFlightRefNum(segment.getFlightRefNum());
                });
                retResult.getFlightRefList().forEach(ref-> {
                    CtSearchSegment segment = segmentMap.get("r"+ref.getFlightRefNum());
                    ref.setFlightRefNum(segment.getFlightRefNum());
                });
                depResult.getFlightRefList().addAll(retResult.getFlightRefList());

                CtTu depCtTu = depResult.getTuList().get(0);
                CtTu retCtTu = retResult.getTuList().get(0);
                depCtTu.setFareBasis(depCtTu.getFareBasis()+";"+ retCtTu.getFareBasis());

                Map<Integer,CtPrice> priceMap = depCtTu.getPriceList().stream().collect(Collectors.toMap(CtPrice::getPassengerType, price -> price));
                for (CtPrice retPrice : retCtTu.getPriceList()) {
                    CtPrice depPrice = priceMap.get(retPrice.getPassengerType());
                    depPrice.setTaxFeeAmount(depPrice.getTaxFeeAmount().add(retPrice.getTaxFeeAmount()));
                    depPrice.setPrice(depPrice.getPrice().add(retPrice.getPrice()));
                    depPrice.setPublishPrice(depPrice.getPublishPrice().add(retPrice.getPublishPrice()));
                }
            }
        }
        return dep;
    }

}
