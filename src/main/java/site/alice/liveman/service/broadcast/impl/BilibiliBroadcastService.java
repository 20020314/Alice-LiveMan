/*
 * <Alice LiveMan>
 * Copyright (C) <2018>  <NekoSunflower>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package site.alice.liveman.service.broadcast.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import site.alice.liveman.jenum.VideoBannedTypeEnum;
import site.alice.liveman.model.AccountInfo;
import site.alice.liveman.model.VideoInfo;
import site.alice.liveman.service.broadcast.BroadcastService;
import site.alice.liveman.utils.HttpRequestUtil;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpSession;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BilibiliBroadcastService implements BroadcastService {
    private static final String SESSION_ATTRIBUTE    = "bilibili-qrcode";
    private static final String URL_GENERATE_CODE    = "https://passport.bilibili.com/qrcode/getLoginUrl";
    private static final String URL_CHECK_CODE       = "https://passport.bilibili.com/qrcode/getLoginInfo?gourl=https://www.bilibili.com/&oauthKey=%s";
    private static final String BILI_LIVE_UPDATE_URL = "https://api.live.bilibili.com/room/v1/Room/update";
    private static final String BILI_START_LIVE_URL  = "https://api.live.bilibili.com/room/v1/Room/startLive";
    private static final String BILI_STOP_LIVE_URL   = "https://api.live.bilibili.com/room/v1/Room/stopLive";
    private static final String BILI_LIVE_INFO_URL   = "https://api.live.bilibili.com/live_user/v1/UserInfo/live_info";

    @Autowired
    private HttpSession session;

    @Override
    public boolean isMatch(String accountSite) {
        return "bilibili".equals(accountSite);
    }

    @Override
    public String getBroadcastAddress(AccountInfo accountInfo) throws Exception {
        VideoInfo videoInfo = accountInfo.getCurrentVideo();
        int area = 371;
        if (videoInfo.getCropConf().getVideoBannedType() == VideoBannedTypeEnum.FULL_SCREEN) {
            area = 33;
        } else if (videoInfo.getArea() != null) {
            area = videoInfo.getArea()[1];
        }
        Matcher matcher = Pattern.compile("bili_jct=(.{32})").matcher(accountInfo.getCookies());
        String csrfToken = "";
        if (matcher.find()) {
            csrfToken = matcher.group(1);
        }
        String startLiveJson = HttpRequestUtil.downloadUrl(new URI(BILI_START_LIVE_URL), accountInfo.getCookies(), "room_id=" + accountInfo.getRoomId() + "&platform=pc&area_v2=" + area + (videoInfo.isVertical() ? "&type=1" : "") + "&csrf_token=" + csrfToken + "&csrf=" + csrfToken, StandardCharsets.UTF_8);
        JSONObject startLiveObject = JSON.parseObject(startLiveJson);
        JSONObject rtmpObject;
        if (startLiveObject.getInteger("code") == 0) {
            rtmpObject = startLiveObject.getJSONObject("data").getJSONObject("rtmp");
        } else {
            if (startLiveJson.contains("系统升级维护中")) {

            }
            accountInfo.setDisable(true);
            throw new RuntimeException("开启B站直播间失败" + startLiveObject);
        }
        String addr = rtmpObject.getString("addr");
        String code = rtmpObject.getString("code");
        if (!addr.endsWith("/") && !code.startsWith("/")) {
            return addr + "/" + code;
        } else {
            return addr + code;
        }
    }

    @Override
    public void setBroadcastSetting(AccountInfo accountInfo, String title, Integer areaId) {
        String postData = null;
        try {
            if (title == null && areaId == null) {
                return;
            }
            Matcher matcher = Pattern.compile("bili_jct=(.{32})").matcher(accountInfo.getCookies());
            String csrfToken = "";
            if (matcher.find()) {
                csrfToken = matcher.group(1);
            }
            title = title != null && title.length() > 20 ? title.substring(0, 20) : title;
            postData = "room_id=" + getBroadcastRoomId(accountInfo) + (StringUtils.isNotBlank(title) ? "&title=" + title : "") + (areaId != null ? "&area_id=" + areaId : "") + "&csrf_token=" + csrfToken + "&csrf=" + csrfToken;
            String resJson = HttpRequestUtil.downloadUrl(new URI(BILI_LIVE_UPDATE_URL), accountInfo.getCookies(), postData, StandardCharsets.UTF_8);
            JSONObject resObject = JSON.parseObject(resJson);
            if (resObject.getInteger("code") != 0) {
                log.error("修改直播间信息失败[" + postData + "]" + resJson);
            }
        } catch (Throwable e) {
            log.error("修改直播间信息失败[" + postData + "]", e);
        }
    }

    @Override
    public String getBroadcastRoomId(AccountInfo accountInfo) throws Exception {
        if (StringUtils.isEmpty(accountInfo.getRoomId())) {
            String liveInfoJson = HttpRequestUtil.downloadUrl(new URI(BILI_LIVE_INFO_URL), accountInfo.getCookies(), Collections.emptyMap(), StandardCharsets.UTF_8);
            JSONObject liveInfoObject = JSON.parseObject(liveInfoJson);
            if (liveInfoObject.get("data") instanceof JSONObject) {
                JSONObject data = liveInfoObject.getJSONObject("data");
                String roomid = data.getString("roomid");
                if ("false".equals(roomid)) {
                    throw new RuntimeException("该账号尚未开通直播间");
                }
                accountInfo.setRoomId(roomid);
                accountInfo.setNickname(data.getJSONObject("userInfo").getString("uname"));
                accountInfo.setUid(data.getJSONObject("userInfo").getString("uid"));
                accountInfo.setAccountId(accountInfo.getNickname());
            } else {
                throw new RuntimeException("获取B站直播间信息失败" + liveInfoObject);
            }
        }
        accountInfo.setRoomUrl("https://live.bilibili.com/" + accountInfo.getRoomId());
        return accountInfo.getRoomId();
    }

    @Override
    public void stopBroadcast(AccountInfo accountInfo, boolean stopOnPadding) {
        try {
            if (stopOnPadding) {
                // 仅当直播间没有视频数据时才关闭
                String roomId = getBroadcastRoomId(accountInfo);
                log.info("检查直播间[roomId=" + roomId + "]视频流状态...");
                for (int i = 1; i <= 3; i++) {
                    try {
                        URI playUrlApi = new URI("https://api.live.bilibili.com/room/v1/Room/playUrl?cid=" + roomId);
                        String playUrl = HttpRequestUtil.downloadUrl(playUrlApi, StandardCharsets.UTF_8);
                        JSONObject playUrlObj = JSONObject.parseObject(playUrl);
                        if (playUrlObj.getInteger("code") != 0) {
                            log.error("获取直播视频流地址失败" + playUrl);
                            return;
                        }
                        JSONArray urls = playUrlObj.getJSONObject("data").getJSONArray("durl");
                        if (!CollectionUtils.isEmpty(urls)) {
                            JSONObject urlObj = (JSONObject) urls.iterator().next();
                            String url = urlObj.getString("url");
                            HttpResponse httpResponse = HttpRequestUtil.getHttpResponse(new URI(url));
                            EntityUtils.consume(httpResponse.getEntity());
                            StatusLine statusLine = httpResponse.getStatusLine();
                            if (statusLine.getStatusCode() < 400) {
                                // 状态码 < 400，请求成功不需要关闭直播间
                                log.info("[roomId=" + roomId + "]直播视频流HTTP响应[" + statusLine + "]将不会关闭直播间");
                                return;
                            } else {
                                log.info("[roomId=" + roomId + "]直播视频流HTTP响应[" + statusLine + "]尝试关闭直播间...");
                                break;
                            }
                        }
                    } catch (Exception e) {
                        log.error("检查直播间推流状态发生错误，重试(" + i + "/3)", e);
                        if (i == 3) {
                            log.info("无法读取[roomId=" + roomId + "]的直播视频流，尝试关闭直播间...");
                        }
                    }
                }
            }
            Matcher matcher = Pattern.compile("bili_jct=(.{32})").matcher(accountInfo.getCookies());
            String csrfToken = "";
            if (matcher.find()) {
                csrfToken = matcher.group(1);
            }
            String postData = "room_id=" + getBroadcastRoomId(accountInfo) + "&platform=pc&csrf_token=" + csrfToken;
            String resJson = HttpRequestUtil.downloadUrl(new URI(BILI_STOP_LIVE_URL), accountInfo.getCookies(), postData, StandardCharsets.UTF_8);
            JSONObject resObject = JSON.parseObject(resJson);
            if (resObject.getInteger("code") != 0) {
                log.error("关闭直播间失败" + resJson);
            } else {
                log.info("直播间[roomId=" + accountInfo.getRoomId() + "]已关闭！");
            }
        } catch (Throwable e) {
            log.error("关闭直播间失败", e);
        }
    }

    @Override
    public String getBroadcastCookies(String username, String password, String captcha) throws Exception {
        Object qrCode = session.getAttribute(SESSION_ATTRIBUTE);
        Map<String, String> requestProperties = new HashMap<>();
        requestProperties.put("referer", "https://passport.bilibili.com/login");
        requestProperties.put("x-requested-with", "XMLHttpRequest");
        HttpResponse httpResponse = HttpRequestUtil.getHttpResponse(URI.create(String.format(URL_CHECK_CODE, qrCode)), null, "oauthKey=" + qrCode + "&gourl=https%3A%2F%2Fwww.bilibili.com%2F", requestProperties, StandardCharsets.UTF_8);
        List<Header> headerList = new ArrayList<>(Arrays.asList(httpResponse.getHeaders("set-cookie")));
        String checkResultJSON = EntityUtils.toString(httpResponse.getEntity(), "utf-8");
        JSONObject checkResult = JSON.parseObject(checkResultJSON);
        if (checkResult.getBoolean("status")) {
            headerList.addAll(Arrays.asList(httpResponse.getHeaders("set-cookie")));
            return headerList.stream().map(header -> header.getValue().split(";")[0]).collect(Collectors.joining(";"));
        }
        throw new Exception(checkResult.getString("message"));
    }

    @Override
    public InputStream getBroadcastCaptcha() throws IOException {
        Map<String, String> requestProperties = new HashMap<>();
        requestProperties.put("referer", "https://passport.bilibili.com/login");
        requestProperties.put("x-requested-with", "XMLHttpRequest");
        String generateCodeJSON = HttpRequestUtil.downloadUrl(URI.create(URL_GENERATE_CODE), null, requestProperties, StandardCharsets.UTF_8);
        JSONObject generateCode = JSON.parseObject(generateCodeJSON);
        if (generateCode.getInteger("code") == 0) {
            String url = generateCode.getJSONObject("data").getString("url");
            String code = generateCode.getJSONObject("data").getString("oauthKey");
            session.setAttribute(SESSION_ATTRIBUTE, code);
            try {
                QRCode qrCode = Encoder.encode(url, ErrorCorrectionLevel.M);
                BufferedImage qrCodeImage = new BufferedImage(qrCode.getMatrix().getWidth(), qrCode.getMatrix().getHeight(), BufferedImage.TYPE_BYTE_BINARY);
                for (int x = 0; x < qrCodeImage.getWidth(); x++) {
                    for (int y = 0; y < qrCodeImage.getHeight(); y++) {
                        if (qrCode.getMatrix().get(x, y) == 0) {
                            qrCodeImage.setRGB(x, y, Color.WHITE.getRGB());
                        }
                    }
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(qrCodeImage, "bmp", bos);
                return new ByteArrayInputStream(bos.toByteArray());
            } catch (WriterException e) {
                throw new IOException(e);
            }
        } else {
            log.error("获取qrcode失败:" + generateCodeJSON);
        }
        return null;
    }


    public static class CaptchaMismatchException extends Exception {
        private String geetestUrl;

        public CaptchaMismatchException(String message, String geetestUrl) {
            super(message);
            this.geetestUrl = geetestUrl;
        }

        public String getGeetestUrl() {
            return geetestUrl;
        }
    }
}
