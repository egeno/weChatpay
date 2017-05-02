package com.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Member;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.sun.corba.se.impl.protocol.giopmsgheaders.Message;
import com.utils.Sha1Util;
import com.utils.http.HttpRequest;

public class Wxpay {


/** 
 * 微信获取prepay_id 同时下单 
 *  
 * @return 
 * @throws Exception 
 */  
@RequestMapping(value = "/getPrepayId", method = RequestMethod.GET)  
public @ResponseBody Map<String, Object> getPrepayId(HttpServletRequest request, String sn) throws Exception {  
    Map<String, Object> resultMap = new HashMap<String, Object>();  

    Order order = orderService.findBySn(sn);  
    System.out.println("sn==========="+sn);  
    Member member = memberService.getCurrent();  
    if (order == null || !member.equals(order.getMember()) || order.getPaymentMethod() == null  
            || order.getAmountPayable().compareTo(BigDecimal.ZERO) <= 0) {  
        System.out.println(ERROR_VIEW);  
        resultMap.put("error_message", ERROR_VIEW);  
        resultMap.put("success", 1);  
        return resultMap;  
    }  
    if (PaymentMethod.Method.online.equals(order.getPaymentMethod().getMethod())) {  
        if (orderService.isLocked(order, member, true)) {  
            System.out.println(Message.warn("shop.order.locked"));  
            resultMap.put("error_message", Message.warn("shop.order.locked"));  
            resultMap.put("success", 1);  
            return resultMap;  
        }  

        PaymentPlugin paymentPlugin = pluginService.getPaymentPlugin("wxpayPubPaymentPlugin");  
        if (paymentPlugin != null) {  
            if (paymentPlugin == null || !paymentPlugin.getIsEnabled()) {  
                System.out.println(ERROR_VIEW);  
                resultMap.put("error_message", ERROR_VIEW);  
                resultMap.put("success", 1);  
                return resultMap;  
            }  

            // 添加支付记录  
            PaymentLog paymentLogtmp = paymentLogService.findBySn(order.getSn());  
            System.out.println("sn==========="+order.getSn());  
            System.out.println("paymentLogtmp==========="+paymentLogtmp);  
            if(paymentLogtmp==null){  
                PaymentLog paymentLog = new PaymentLog();  
                paymentLog.setSn(order.getSn());  
                paymentLog.setType(PaymentLog.Type.payment);  
                paymentLog.setStatus(PaymentLog.Status.wait);  
                paymentLog.setFee(paymentPlugin.calculateFee(order.getAmountPayable()));  
                paymentLog.setAmount(paymentPlugin.calculateAmount(order.getAmountPayable()));  
                paymentLog.setPaymentPluginId(paymentPlugin.getId());  
                paymentLog.setPaymentPluginName(paymentPlugin.getName());  
                paymentLog.setMember(member);  
                paymentLog.setOrder(order);  
                paymentLogService.save(paymentLog);  
            }  

            Map<String, String> paramMap = new HashMap<String, String>();  
            paramMap.put("out_trade_no", order.getSn());  
            paramMap.put("total_fee",  
                    order.getAmount() != null ? paymentPlugin.calculateAmount(order.getAmountPayable())  
                            .multiply(new BigDecimal("100")).toBigInteger().toString() : "0");  
            paramMap.put("openid", member.getOpenId());  
            String paramXms =CommonPayment.getPrepayIdParam(request, paramMap).toString();  
            String str = HttpRequest.sendPost(CommonWeChat.PAYMENT_GET_PREPAYID_URL, paramXms);  
            str = str.replaceAll("<![CDATA[|]]>", "");  
            SortedMap<String, String> dataMap = CommonPayment.xmlToMap(str);  

            SortedMap<String, String> data = new TreeMap<String, String>();  
            if (dataMap.get("return_code").equalsIgnoreCase("SUCCESS")  
                    && dataMap.get("result_code").equalsIgnoreCase("SUCCESS")) {  

                data.put("appId", CommonWeChat.APPID.trim());  
                data.put("timeStamp", Sha1Util.getTimeStamp().trim());  
                data.put("nonceStr", Sha1Util.getNonceStr().trim());  
                data.put("package", "prepay_id=" + dataMap.get("prepay_id").trim());  
                data.put("signType", CommonWeChat.SIGNTYPE.trim());  
                data.put("paySign", CommonPayment.getMD5Sign(data).trim());  
                resultMap.put("success", 0);  
                resultMap.put("resultData", data);  
            } else if (dataMap.get("return_code").equalsIgnoreCase("FAIL")) {  
                System.out.println(dataMap.get("return_msg"));  
                resultMap.put("error_message", dataMap.get("return_msg"));  
                resultMap.put("success", 1);  
            } else if (dataMap.get("result_code").equalsIgnoreCase("FAIL")) {  
                System.out.println(dataMap.get("err_code_des"));  
                resultMap.put("error_message", dataMap.get("err_code_des"));  
                resultMap.put("success", 1);  
            } else {  
                System.out.println(dataMap.get("数据有误"));  
                resultMap.put("error_message", "数据有误");  
                resultMap.put("success", 1);  
            }  
        } else {  
            System.out.println(ERROR_VIEW);  
            resultMap.put("error_message", ERROR_VIEW);  
            resultMap.put("success", 1);  
            return resultMap;  
        }  
    }  

    return resultMap;  
}  

@RequestMapping(value = "/m_weixinNotify")  
public String m_weixinNotify(String sn, HttpServletRequest request, ModelMap model) {  
    System.out.println("sn====="+sn);  
    PaymentLog paymentLog = paymentLogService.findBySn(sn);  
    System.out.println("paymentLog====="+paymentLog);  
    model.addAttribute("paymentLog", paymentLog);  
    return "payment/plugin_notify";  
}  

/** 
 * 微信支付成功通知 
 *  
 * @return 
 * @throws Exception 
 */  
@RequestMapping(value = "/paySuccess")  
public void paySuccess(HttpServletRequest request, HttpServletResponse response) throws Exception {  
    System.out.println("paySuccess: begin\n");  
    try {  
        ServletInputStream in = request.getInputStream();  
        StringBuffer buff = new StringBuffer();  
        try {  
            byte[] b = new byte[4096];  
            for (int length; (length = in.read(b)) != -1;) {  
                buff.append(new String(b, 0, length));  
            }  
        } catch (IOException e) {  
            System.out.println("streamToString : === " + e.getMessage());  
            buff = buff.delete(0, buff.length());  
            e.printStackTrace();  
        }  
        String result = buff.toString();  
          
        System.out.println("result：== " + result);  
        System.out.println("xStreamUtil begin...");  
          
          

        if (result != null && !result.equals("")) {  
            Map<String, String> map = CommonPayment.xmlToMap(result);  
            System.out.println("map:" + map);  
            for (Object keyValue : map.keySet()) {  
//              System.out.println(keyValue + "=" + map.get(keyValue));  
            }  
            System.out.println("result_code:" + map.get("result_code").equalsIgnoreCase("SUCCESS"));  
            if (map.get("result_code").equalsIgnoreCase("SUCCESS")) {  
//              String sign = CommonPayment.SuccessSign(map, CommonWeChat.MCH_KEY);  
//              System.out.println("215 sign=" + map.get("sign") + " APP_PAYKRY=" + sign);  
//              if (sign != null && map.get("sign").equals(sign)) {  
                    String out_trade_no = map.get("out_trade_no");  
                    String total_fee = map.get("total_fee");  
                    //处理订单  

                    sendToCFT("<xml><return_code><![CDATA[SUCCESS]]></return_code></xml>", response);  
//              }  
            }  
        }  
        sendToCFT("<xml><return_code><![CDATA[FAIL]]></return_code></xml>", response);  
    } catch (Exception ex) {  
        System.out.println("paySuccess Exception = " + ex.getMessage());  
        ex.printStackTrace();  
    }  
    System.out.println("paySuccess  === end\n");  

}  

public void sendToCFT(String msg, HttpServletResponse response) throws IOException {  
    String strHtml = msg;  
    PrintWriter out = response.getWriter();  
    out.println(strHtml);  
    out.flush();  
    out.close();  
}  
}