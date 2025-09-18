package org.jackhuang.hmcl.util;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.jfoenix.controls.JFXButton;
import com.sun.net.httpserver.HttpExchange;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftAccount;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftSession;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import java.util.UUID;
import org.jackhuang.hmcl.auth.AuthInfo;
import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import java.util.concurrent.ConcurrentHashMap;

public class LocalHttpServer {
    // 单例实例
    private static final LocalHttpServer INSTANCE = new LocalHttpServer();

    private HttpServer server;
    private static String jsonResponse;

    // 私有构造方法防止外部创建实例
    private LocalHttpServer() {}

    // 获取单例实例的方法
    public static LocalHttpServer getInstance() {
        return INSTANCE;
    }

    public synchronized void start(int port) throws IOException {
        // 如果已有服务器在运行，先停止它
        if (server != null) {
            stop();
        }

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/user/info", new InfoHandler());
        server.createContext("/user/login", new LoginHandler());
        server.createContext("/status", new StatusHandler());
        server.setExecutor(null);
        server.start();
        LOG.info("Server started on port " + port);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null; // 清除引用，防止重复停止
            LOG.info("Server stopped");
        } else {
            LOG.info("Server is not running");
        }
    }

    // 检查服务器是否正在运行
    public boolean isRunning() {
        return server != null;
    }

    public static void setJsonResponse(String json) {
        jsonResponse = json;
    }

    private static final ConcurrentHashMap<String, SessionState> pendingRequests = new ConcurrentHashMap<>();

    static class SessionState {
        boolean allowed = false;
        String sourceInfo;
        long expireTime;
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 支持OPTIONS预检请求
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 200, "");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                try {
                    // 获取来源信息
                    String source = exchange.getRequestHeaders().getFirst("Origin");
                    if (source == null) {
                        source = exchange.getRemoteAddress().getAddress().getHostAddress();
                    }

                    // 生成唯一请求ID
                    String requestId = UUID.randomUUID().toString();
                    SessionState state = new SessionState();
                    state.sourceInfo = source;
                    state.expireTime = System.currentTimeMillis() + 60000;

                    // 显示弹窗
                    String finalSource = source;
                    Platform.runLater(() -> showConfirmationDialog(requestId, finalSource));

                    // 等待用户响应
                    while (System.currentTimeMillis() < state.expireTime) {
                        if (pendingRequests.containsKey(requestId)) {
                            SessionState result = pendingRequests.get(requestId);
                            if (result.allowed) {
                                // 返回用户信息
                                sendResponse(exchange, 200, buildUserResponse(exchange));
                            } else {
                                sendResponse(exchange, 403, "{\"status\":\"denied\"}");
                            }
                            pendingRequests.remove(requestId);
                            return;
                        }
                        Thread.sleep(500);
                    }

                    // 超时处理
                    sendResponse(exchange, 403, "{\"status\":\"timeout\"}");

                } catch (Exception e) {
                    sendResponse(exchange, 500, "{\"error\":\"Internal Server Error\"}");
                }
            }
        }

        private String buildUserResponse(HttpExchange exchange) {
            // 复用原有的用户信息构建逻辑
            Account account = Accounts.getSelectedAccount();
            String loginTypeName = Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account));
            String username;
            if (account instanceof MicrosoftAccount) {
                MicrosoftSession session = ((MicrosoftAccount) account).getSession();
                username = session.getProfile().getName();
            } else {
                username = account.getUsername();
            }
            UUID uuid = account.getUUID();

            if (account instanceof AuthlibInjectorAccount) {
                AuthlibInjectorServer server = ((AuthlibInjectorAccount) account).getServer();
                if (server.getUrl().equals("https://skins.clearcraft.cn/api/yggdrasil/")) {
                    loginTypeName = "ClearCraft";
                } else if (server.getUrl().equals("https://skin.ineko.cc/api/yggdrasil/")) {
                    loginTypeName = "RainCraft";
                } else {
                    loginTypeName = i18n("account.injector.server");
                }
            }

            // 修复authInfo空指针问题
            String authToken = getAccessToken();

            jsonResponse = String.format("{\"status\":\"succeed\",\"userinfo\":{"
                            + "\"username\":\"%s\","
                            + "\"loginType\":\"%s\","
                            + "\"uuid\":\"%s\","
                            + "\"AuthToken\":\"%s\"}}",
                    username, loginTypeName, uuid, authToken);
            try {
                sendResponse(exchange, 200, jsonResponse);
            } catch (Exception e) {
                System.err.println("Failed to send response: " + e.getMessage());
            }
            return jsonResponse;
        }
    }

    private static void sendResponse(HttpExchange Exchange, int code, String response) throws IOException {
        // 添加CORS许可头
        Exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "https://dashboard.clearcraft.cn");  // 允许指定源
        Exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");  // 允许的请求方法
        Exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Origin, Content-Type, Accept");  // 允许的请求头
        Exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        Exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = Exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
    private static String getAccessToken() {
        Account account = Accounts.getSelectedAccount();
        if (account instanceof MicrosoftAccount) {
            MicrosoftSession session = ((MicrosoftAccount)account).getSession();
            return session.getAccessToken();
        }
        if (account instanceof AuthlibInjectorAccount)
            try {
                AuthInfo YggAuthInfo = ((AuthlibInjectorAccount)account).logIn();
                return YggAuthInfo.getAccessToken();
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        return "";
    }

    public static void showConfirmationDialog(String requestId, String source) {
        com.jfoenix.controls.JFXDialogLayout dialogLayout = new com.jfoenix.controls.JFXDialogLayout();
        javafx.scene.Node node = (javafx.scene.Node) dialogLayout;

        dialogLayout.setHeading(new Label("登录安全提示"));
        dialogLayout.setBody(new ProgressIndicator());


        JFXButton AllowButton = new JFXButton("同意");
        JFXButton DenyButton = new JFXButton("拒绝 (60)");

        final Timeline timeline = new Timeline(
                new KeyFrame(Duration.seconds(1), e -> {
                    int remaining = Integer.parseInt(DenyButton.getText().replaceAll("\\D", ""));
                    if (--remaining <= 0) {
                        node.fireEvent(new DialogCloseEvent());
                    } else {
                        DenyButton.setText("拒绝 (" + remaining + ")");
                    }
                })
        );
        timeline.setCycleCount(60);
        AllowButton.getStyleClass().add("dialog-accept");
        AllowButton.setOnAction(e -> {
            pendingRequests.put(requestId, new SessionState() {{ allowed = true; }});
            timeline.stop();
            node.fireEvent(new DialogCloseEvent());
        });

        DenyButton.getStyleClass().add("dialog-cancel");
        DenyButton.setOnAction(e -> {
            pendingRequests.put(requestId, new SessionState());
            timeline.stop();
            node.fireEvent(new DialogCloseEvent());
        });
        VBox content = new VBox(10,
                new Label(String.format("收到来自 \"%s\" 的登录请求，是否同意? \n注意: 如果同意此请求，网页将会获取到您的账户信息，可能导致账户被盗，请确保请求来源于您信任的地址", source)),
                new HBox(10, AllowButton, DenyButton)
        );

        dialogLayout.setActions(AllowButton, DenyButton);
        onEscPressed(dialogLayout, DenyButton::fire);
        StackPane updatePane = new StackPane();
        updatePane.getChildren().add(content);
        dialogLayout.setBody(updatePane);
        timeline.play();
        Controllers.dialog(dialogLayout);
    }
    static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 200, "");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                try {
                    sendResponse(exchange, 200, "{\"status\":\"running\"}");
                } catch (Exception e) {
                    sendResponse(exchange, 500, "{\"error\":\"Internal Server Error\"}");
                }
            }
        }
    }
    static class InfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 200, "");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                // 复用原有的用户信息构建逻辑
                Account account = Accounts.getSelectedAccount();
                String loginTypeName = Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account));
                String username;
                if (account instanceof MicrosoftAccount) {
                    MicrosoftSession session = ((MicrosoftAccount) account).getSession();
                    username = session.getProfile().getName();
                } else {
                    username = account.getUsername();
                }
                UUID uuid = account.getUUID();

                if (account instanceof AuthlibInjectorAccount) {
                    AuthlibInjectorServer server = ((AuthlibInjectorAccount) account).getServer();
                    if (server.getUrl().equals("https://skins.clearcraft.cn/api/yggdrasil/")) {
                        loginTypeName = "ClearCraft";
                    } else if (server.getUrl().equals("https://skin.ineko.cc/api/yggdrasil/")) {
                        loginTypeName = "RainCraft";
                    } else {
                        loginTypeName = i18n("account.injector.server");
                    }
                }

                jsonResponse = String.format("{\"status\":\"succeed\",\"userinfo\":{"
                                + "\"username\":\"%s\","
                                + "\"loginType\":\"%s\","
                                + "\"uuid\":\"%s\"}}",
                        username, loginTypeName, uuid);
                try {
                    sendResponse(exchange, 200, jsonResponse);
                } catch (Exception e) {
                    System.err.println("Failed to send response: " + e.getMessage());
                }
            }
        }
    }
}