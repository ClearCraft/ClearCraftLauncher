package org.jackhuang.hmcl.util;

import java.net.*;
import org.xbill.DNS.*;

public class MinecraftPingUtil {
    public static String ping(String address) {
        try {
            // 增强URI构造的健壮性
            String normalizedAddress = address.contains("://") ? address : "minecraft://" + address;
            URI uri = new URI(normalizedAddress);
            String host = uri.getHost();
            
            // 添加端口默认值处理
            int port = uri.getPort() != -1 ? uri.getPort() : 25565;

            // 优化DNS查询异常处理
            Lookup lookup = new Lookup("_minecraft._tcp." + host, Type.SRV);  // 添加SRV服务前缀
            org.xbill.DNS.Record[] records = null;
            records = lookup.run();
            
            if (records != null && records.length > 0) {
                SRVRecord srv = (SRVRecord) records[0];
                host = srv.getTarget().toString().replaceFirst("\\.$", "");
                port = srv.getPort();
            }
            // 合并连接逻辑（删除重复的socket连接块）
            try (Socket socket = new Socket()) {
                socket.setSoTimeout(1500); // 设置读取超时
                long start = System.currentTimeMillis();
                socket.connect(new InetSocketAddress(host, port), 1500); // 连接超时1.5秒
                int ms = (int) (System.currentTimeMillis() - start);
                return String.valueOf(ms) + " ms";
            }
        } catch (URISyntaxException e) {
            System.err.println("无效地址格式: " + address);
            return "Invalid Address";
        } catch (UnknownHostException e) {
            System.err.println("未知主机: " + e.getMessage());
            return "Unknown Host";
        } catch (ConnectException e) {
            System.err.println("连接被拒绝: " + e.getMessage());
            return "Connection Refused";
        } catch (SocketTimeoutException e) {
            return "Timeout";
        } catch (Exception e) {
            System.err.println("严重错误: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return "Error";
        }
    }
    public static Boolean check(String address) {
        if (address == null || address == "") {
            address = "mc.clearcraft.cn";
        }
        try {
            // 增强URI构造的健壮性
            String normalizedAddress = address.contains("://") ? address : "minecraft://" + address;
            URI uri = new URI(normalizedAddress);
            String host = uri.getHost();

            // 添加端口默认值处理
            int port = uri.getPort() != -1 ? uri.getPort() : 25565;

            // 优化DNS查询异常处理
            Lookup lookup = new Lookup("_minecraft._tcp." + host, Type.SRV);  // 添加SRV服务前缀
            org.xbill.DNS.Record[] records = null;
            records = lookup.run();

            if (records != null && records.length > 0) {
                SRVRecord srv = (SRVRecord) records[0];
                host = srv.getTarget().toString().replaceFirst("\\.$", "");
                port = srv.getPort();
            }
            // 合并连接逻辑（删除重复的socket连接块）
            try (Socket socket = new Socket()) {
                socket.setSoTimeout(1500); // 设置读取超时
                long start = System.currentTimeMillis();
                socket.connect(new InetSocketAddress(host, port), 1500); // 连接超时1.5秒
                int ms = (int) (System.currentTimeMillis() - start);
                return true;
            }
        } catch (URISyntaxException e) {
            System.err.println("无效地址格式: " + address);
            return false;
        } catch (UnknownHostException e) {
            System.err.println("未知主机: " + e.getMessage());
            return false;
        } catch (ConnectException e) {
            System.err.println("连接被拒绝: " + e.getMessage());
            return false;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (Exception e) {
            System.err.println("严重错误: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }
}