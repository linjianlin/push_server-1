package cn.wildfirechat.push.android.hms;

import cn.wildfirechat.push.PushMessage;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;

@Component
public class HMSPush {
    private static final Logger LOG = LoggerFactory.getLogger(HMSPush.class);
    private static final String tokenUrl = "https://oauth-login.cloud.huawei.com/oauth2/v3/token"; // 获取认证Token的URL

    private String accessToken;// 下发通知消息的认证Token
    private long tokenExpiredTime; // accessToken的过期时间

    private HMSConfig mConfig;

    private String apiUrl; // 应用级消息下发API

    @Autowired
    public HMSPush(HMSConfig config) {
        mConfig = config;
        apiUrl = MessageFormat.format("https://push-api.cloud.huawei.com/v1/{0}/messages:send", mConfig.getAppId());
    }

    // 获取下发通知消息的认证Token
    private void refreshToken() throws IOException {
        LOG.info("hms refresh token");
        String msgBody = MessageFormat.format("grant_type=client_credentials&client_secret={0}&client_id={1}",
                URLEncoder.encode(mConfig.getAppSecret(), "UTF-8"), mConfig.getAppId());
        String response = httpPost(tokenUrl, msgBody, 5000, 5000);
        JSONObject obj = JSONObject.parseObject(response);
        accessToken = obj.getString("access_token");
        tokenExpiredTime = System.currentTimeMillis() + obj.getLong("expires_in") - 5 * 60 * 1000;
        LOG.info("hms refresh token with result {}", response);
    }

    // 发送Push消息
    public void push(PushMessage pushMessage) {
        if (tokenExpiredTime <= System.currentTimeMillis()) {
            try {
                refreshToken();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        /* PushManager.requestToken为客户端申请token的方法，可以调用多次以防止申请token失败 */
        /* PushToken不支持手动编写，需使用客户端的onToken方法获取 */
        // JSONArray deviceTokens = new JSONArray();// 目标设备Token
        // deviceTokens.add(pushMessage.getDeviceToken());

        // JSONObject msg = new JSONObject();
        // msg.put("type", 1);// 3: 通知栏消息，异步透传消息请根据接口文档设置
        // String token = pushMessage.getDeviceToken();
        // pushMessage.deviceToken = null;
        // msg.put("body", new Gson().toJson(pushMessage));// 通知栏消息body内容

        // JSONObject hps = new JSONObject();// 华为PUSH消息总结构体
        // hps.put("msg", msg);

        // JSONObject payload = new JSONObject();
        // payload.put("hps", hps);

        // LOG.info("send push to HMS {}", payload);

        String token = pushMessage.getDeviceToken();
        try {
            JSONObject object = new JSONObject();
            object.put("validate_only", false);
            JSONObject message = new JSONObject();
            JSONObject android = new JSONObject();
            JSONObject android_notification = new JSONObject();
            android_notification.put("title", MessageFormat.format("{0} 给你发来了一条信息", pushMessage.senderName));
            android_notification.put("body", pushMessage.pushContent);
            android_notification.put("importance", "NORMAL");
            JSONObject click_action = new JSONObject();
            click_action.put("type", 3);
            JSONObject badge = new JSONObject();
            badge.put("class", "cn.sqc.chat.app.main.SplashActivity");
            badge.put("add_num", 1);
            android_notification.put("click_action", click_action);
            android_notification.put("badge", badge);
            android.put("notification", android_notification);
            message.put("android", android);

            List<String> tokens = Arrays.asList(token);
            message.put("token", tokens);
            object.put("message", message);

            // String postBody = MessageFormat.format(
            // "access_token={0}&nsp_svc={1}&nsp_ts={2}&device_token_list={3}&payload={4}",
            // URLEncoder.encode(accessToken, "UTF-8"),
            // URLEncoder.encode("openpush.message.api.send", "UTF-8"),
            // URLEncoder.encode(String.valueOf(System.currentTimeMillis() / 1000),
            // "UTF-8"),
            // URLEncoder.encode(deviceTokens.toString(), "UTF-8"),
            // URLEncoder.encode(payload.toString(), "UTF-8"));

            // String postUrl = apiUrl + "?nsp_ctx="
            // + URLEncoder.encode("{\"ver\":\"1\", \"appId\":\"" + mConfig.getAppId() +
            // "\"}", "UTF-8");
            String postBody = object.toString();
            String response = httpPost(apiUrl, postBody, 5000, 5000, accessToken);
            LOG.info("Push to {} response {}", token, response);
        } catch (IOException e) {
            e.printStackTrace();
            LOG.info("Push to {} with exception", token, e);
        }
    }

    public String httpPost(String httpUrl, String data, int connectTimeout, int readTimeout) throws IOException {
        return httpPost(httpUrl, data, connectTimeout, readTimeout, null);
    }

    public String httpPost(String httpUrl, String data, int connectTimeout, int readTimeout, String accessToken)
            throws IOException {
        OutputStream outPut = null;
        HttpURLConnection urlConnection = null;
        InputStream in = null;

        try {
            URL url = new URL(httpUrl);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("POST");
            urlConnection.setDoOutput(true);
            urlConnection.setDoInput(true);

            urlConnection.setConnectTimeout(connectTimeout);
            urlConnection.setReadTimeout(readTimeout);
            if (accessToken != null) {
                urlConnection.setRequestProperty("Authorization", MessageFormat.format("Bearer {0}", accessToken));
                urlConnection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            } else {
                urlConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            }
            urlConnection.connect();

            // POST data
            outPut = urlConnection.getOutputStream();
            outPut.write(data.getBytes("UTF-8"));
            outPut.flush();

            // read response
            if (urlConnection.getResponseCode() < 400) {
                in = urlConnection.getInputStream();
            } else {
                in = urlConnection.getErrorStream();
            }

            List<String> lines = IOUtils.readLines(in, urlConnection.getContentEncoding());
            StringBuffer strBuf = new StringBuffer();
            for (String line : lines) {
                strBuf.append(line);
            }
            LOG.info(strBuf.toString());
            return strBuf.toString();
        } finally {
            IOUtils.closeQuietly(outPut);
            IOUtils.closeQuietly(in);
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }
}
