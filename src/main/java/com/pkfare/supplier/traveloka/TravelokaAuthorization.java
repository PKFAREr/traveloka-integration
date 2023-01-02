package com.pkfare.supplier.traveloka;

import com.alibaba.fastjson.JSONObject;
import com.pkfare.common.HttpReceive;
import com.pkfare.common.HttpSend;
import com.pkfare.supplier.Context;
import com.pkfare.supplier.bean.configure.SupplierInterfaceConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TravelokaAuthorization {

    private static final Map<String, String> HEADER = new HashMap<>();

    private static final String CLIENT_ID_NAME = "client_id";

    private static final String CLIENT_SECRET_NAME = "client_secret";

    private static final String ACCESS_TOKEN_NAME = "access_token";

    private static final Map<String, FutureTask<Token>> TOKEN_MAP = new ConcurrentHashMap<>();

    /**
     * token有效时间 单位 S 本地过期的token还有效 供应那边的有效期为3600S
     */
    private Integer effectiveTime = 1800;

    private TravelokaAuthorization() {
    }

    private Token initTravelokaAuthorization(SupplierInterfaceConfig authConfig) {
        Token token = new Token();
        token.setUrl(authConfig.getUrl());
        token.setClientId(authConfig.getUsername());
        token.setClientSecret(authConfig.getPassword());
        return token;
    }

    public String getToken(Context context) {
        SupplierInterfaceConfig authConfig = context.getConfigure("auth");

        FutureTask<Token> oldTokenFuture = TOKEN_MAP.get(authConfig.getUsername());
        try {
            if (Objects.isNull(oldTokenFuture)) {
                FutureTask<Token> task = getFutureTask(context);
                FutureTask<Token> newTokenFuture = TOKEN_MAP.putIfAbsent(authConfig.getUsername(), task);
                if (Objects.isNull(newTokenFuture)) {
                    // 初始化token任务成功 执行任务 并获取结果
                    task.run();
                    oldTokenFuture = task;
                } else {
                    // 初始化token任务竞争失败 直接获取结果
                    oldTokenFuture = newTokenFuture;
                }
            }
            Token token = oldTokenFuture.get(60L, TimeUnit.SECONDS);
            if (!checkTokenEffectiveTime(token)) {
                // 如果过期了 重新生成新的token任务
                FutureTask<Token> newTokenFuture = getFutureTask(context);
                if (TOKEN_MAP.replace(authConfig.getUsername(), oldTokenFuture, newTokenFuture)) {
                    // 竞争成功后执行
                    newTokenFuture.run();
                }
                // 重新获取新的token
                token = TOKEN_MAP.get(authConfig.getUsername()).get(60L, TimeUnit.SECONDS);
            }
            return token.getAccessToken();
        } catch (ExecutionException e) {
//            log.error("", e);
            TOKEN_MAP.remove(authConfig.getUsername());
        } catch (Exception e) {
//            log.error("", e);
        }
        return null;
    }

    private FutureTask<Token> getFutureTask(Context context) {
        SupplierInterfaceConfig authConfig = context.getConfigure("auth");
        return new FutureTask<>(() -> {
            Token token = initTravelokaAuthorization(authConfig);
            refreshTravelokaAuthorization(token, context.buildHttpSend());
            return token;
        });
    }

    private void refreshTravelokaAuthorization(Token token, HttpSend httpSend) throws Exception {
        token.setAccessToken(requestToken(token, httpSend));
        token.setExpireTime(refreshExpiresDate());
    }

    private Boolean checkTokenEffectiveTime(Token token) {
        //预留10秒作为请求的缓冲时间
        return new Date(System.currentTimeMillis() + 10000).before(token.getExpireTime());
    }

    private Date refreshExpiresDate() {
        return new Date(System.currentTimeMillis() + effectiveTime * 1000);
    }

    private String requestToken(Token token, HttpSend httpSend) throws IOException {
        String payload = "client_id=" + token.getClientId() + "&client_secret=" + token.getClientSecret();
        HttpReceive receive = httpSend.addHeader("Content-Type","application/x-www-form-urlencoded;charset=utf-8").url(token.getUrl()).send(payload);
        String result = receive.getReceivePayload();
        JSONObject responseObject = JSONObject.parseObject(result);
        String tokenStr = Optional.ofNullable(responseObject.get("access_token")).map(Object::toString).orElse(null);
        if(StringUtils.isEmpty(tokenStr)){
            throw new RuntimeException("获取token失败");
        }else {
            return tokenStr;
        }
    }

    @Data
    static class Token {

        private String accessToken;

        private Date expireTime;

        private String url;

        private String clientId;

        private String clientSecret;
    }

}
