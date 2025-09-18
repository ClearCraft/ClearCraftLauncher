/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.upgrade;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import java.net.URI;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.ILoggerFactory;

import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;

import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Lang.thread;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;
import static org.jackhuang.hmcl.util.Pair.pair;

public final class UpdateChecker {
    private UpdateChecker() {}

    private static final ObjectProperty<RemoteVersion> latestVersion = new SimpleObjectProperty<>();
    private static final BooleanBinding outdated = Bindings.createBooleanBinding(
            () -> {
                RemoteVersion latest = latestVersion.get();
                if (latest == null || isDevelopmentVersion(Metadata.VERSION)) {
                    return false;
                } else if (latest.isForce()
                        || Metadata.isNightly()
                        || latest.getChannel() == UpdateChannel.NIGHTLY
                        || latest.getChannel() != UpdateChannel.getChannel()) {
                    return !latest.getVersion().equals(Metadata.VERSION);
                } else {
                    return VersionNumber.compare(Metadata.VERSION, latest.getVersion()) < 0;
                }
            },
            latestVersion);
    private static final ReadOnlyBooleanWrapper checkingUpdate = new ReadOnlyBooleanWrapper(false);

    public static void init() {
        requestCheckUpdate(UpdateChannel.getChannel());
    }

    public static RemoteVersion getLatestVersion() {
        return latestVersion.get();
    }

    public static ReadOnlyObjectProperty<RemoteVersion> latestVersionProperty() {
        return latestVersion;
    }

    public static boolean isOutdated() {
        return outdated.get();
    }

    public static ObservableBooleanValue outdatedProperty() {
        return outdated;
    }

    public static boolean isCheckingUpdate() {
        return checkingUpdate.get();
    }

    public static ReadOnlyBooleanProperty checkingUpdateProperty() {
        return checkingUpdate.getReadOnlyProperty();
    }

    private static RemoteVersion checkUpdate(UpdateChannel channel) throws IOException, InterruptedException, URISyntaxException {
        // 第一次请求获取版本信息
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(Metadata.HMCL_LATEST_VERSION_URL))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject versionInfo = new JSONObject(response.body());

            // 构建更新检查URL
            String updateUrl = NetworkUtils.withQuery(Metadata.HMCL_UPDATE_URL, mapOf(
                    pair("version", versionInfo.getString("latest_version")),
                    pair("build", String.valueOf(versionInfo.getInt("latest_build")))
            ));

            // 第二次请求获取文件列表
            client = HttpClient.newHttpClient();
            request = HttpRequest.newBuilder()
                    .uri(new URI(updateUrl))
                    .GET()
                    .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject updateInfo = new JSONObject(response.body());

            // 安全获取contents数组
            JSONArray contents = updateInfo.optJSONArray("contents");
            if (contents == null) {
                throw new JSONException("contents字段不存在或不是数组");
            }

            // 查找.jar.sha1文件
            String sha1Content = null;
            for (int i = 0; i < contents.length(); i++) {
                JSONObject file = contents.getJSONObject(i);
                if (file.getString("name").endsWith(".jar.sha1")) {
                    String downloadUrl = "https://api.clearcraft.cn/CCL/download?file=" + file.getString("path");

                    // 获取SHA1文件内容
                    HttpResponse<String> sha1Response = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder().uri(new URI(downloadUrl)).GET().build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    sha1Content = sha1Response.body().trim();  // 使用trim()删除两端空白字符
                    break;
                }
            }
            String downloadUrl = null;
            for (int i = 0; i < contents.length(); i++) {
                JSONObject file = contents.getJSONObject(i);
                if (file.getString("name").endsWith(".jar")) {
                    downloadUrl = "https://api.clearcraft.cn/CCL/download?file=" + file.getString("path");
                    break;
                }
            }

            // 使用第一个文件作为主下载文件，并传递版本号和SHA1校验值
            return RemoteVersion.fetch(
                    channel,
                    downloadUrl,
                    sha1Content,
                    versionInfo.getString("latest_version") + "." + versionInfo.getInt("latest_build") // 使用外层API获取的版本号
            );
        } catch (Exception e) {
            LOG.error("Check update failed", e);
            return null;
        }
    }

    private static boolean isDevelopmentVersion(String version) {
        return version.contains("@") || // eg. @develop@
                version.contains("SNAPSHOT"); // eg. 3.5.SNAPSHOT
    }

    public static void requestCheckUpdate(UpdateChannel channel) {
        Platform.runLater(() -> {
            if (isCheckingUpdate())
                return;
            checkingUpdate.set(true);

            thread(() -> {
                RemoteVersion result = null;
                try {
                    result = checkUpdate(channel);
                    LOG.info("Latest version (" + channel + ") is " + result);
                } catch (IOException | InterruptedException | URISyntaxException e) {
                    LOG.warning("Failed to check for update", e);
                }

                RemoteVersion finalResult = result;
                Platform.runLater(() -> {
                    checkingUpdate.set(false);
                    if (finalResult != null) {
                        latestVersion.set(finalResult);
                    }
                });
            }, "Update Checker", true);
        });
    }
}
