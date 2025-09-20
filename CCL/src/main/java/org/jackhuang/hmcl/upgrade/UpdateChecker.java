/*
 * ClearCraft Launcher
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/ >.
 */
package org.jackhuang.hmcl.upgrade;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.jackhuang.hmcl.util.Lang.thread;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public final class UpdateChecker {

    private UpdateChecker() {}

    /* ===================== 状态字段 ===================== */
    private static final ObjectProperty<RemoteVersion> latestVersion = new SimpleObjectProperty<>();
    private static final BooleanBinding outdated =
            Bindings.createBooleanBinding(() -> computeOutdated(), latestVersion);
    private static final ReadOnlyBooleanWrapper checkingUpdate = new ReadOnlyBooleanWrapper(false);

    /* ===================== 公共 API ===================== */
    public static void init() {
        requestCheckUpdate(UpdateChannel.getChannel());
    }

    public static RemoteVersion getLatestVersion()           { return latestVersion.get(); }
    public static ReadOnlyObjectProperty<RemoteVersion> latestVersionProperty() { return latestVersion; }
    public static boolean isOutdated()                       { return outdated.get(); }
    public static ObservableBooleanValue outdatedProperty()  { return outdated; }
    public static boolean isCheckingUpdate()                 { return checkingUpdate.get(); }
    public static ReadOnlyBooleanProperty checkingUpdateProperty() {
        return checkingUpdate.getReadOnlyProperty();
    }

    /* ===================== 内部实现 ===================== */
    private static final String GITHUB_LATEST_RELEASE =
            "https://api.github.com/repos/ClearCraft/ClearCraftLauncher/releases/latest";
    private static final String GITEE_LATEST_RELEASE =
            "https://gitee.com/api/v5/repos/xsp090424/ClearCraftLauncher/releases/latest";

    /** 下载并读取 sha256 文件，完成后删除；失败返回 null */
    private static String fetchSha256Gitee(String shaUrl) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        try {
            Path tmp = Files.createTempFile("ccl_", ".sha256");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(shaUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (resp.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                return null;
            }
            String content = Files.readString(tmp).trim();
            Files.deleteIfExists(tmp);
            if (content.matches("^[0-9a-fA-F]{64}$")) {
                return content.toLowerCase();
            }
        } catch (IOException | InterruptedException e) {
            LOG.warning("Failed to fetch sha256 from " + shaUrl + ": " + e.getMessage());
        }
        return null;
    }

    private static RemoteVersion checkUpdate(UpdateChannel channel) {
        String api = (channel == UpdateChannel.GITEE) ? GITEE_LATEST_RELEASE : GITHUB_LATEST_RELEASE;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(api))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warning("API returned " + resp.statusCode());
                return null;
            }

            JSONObject root = new JSONObject(resp.body());
            String tag   = root.getString("tag_name");            // v1.0.0.148
            JSONArray assets = root.getJSONArray("assets");

            String jarUrl  = null;
            String sha256  = null;

            /* 1. 先找 jar */
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");
                if (name.endsWith(".jar")) {
                    jarUrl = asset.getString("browser_download_url");
                    break;
                }
            }
            if (jarUrl == null) {
                LOG.warning("No .jar asset found in latest release");
                return null;
            }

            /* 2. 拿哈希 */
            if (channel == UpdateChannel.GITHUB) {
                /* GitHub: 用 API 返回的 digest */
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.getString("name");
                    if (name.endsWith(".jar")) {
                        String digest = asset.optString("digest", "");
                        if (digest.startsWith("sha256:")) {
                            sha256 = digest.substring(7);
                        }
                        break;
                    }
                }
            } else {
                /* Gitee: 下载同名.sha256文件 */
                String shaUrl = jarUrl + ".sha256";
                sha256 = fetchSha256Gitee(shaUrl);
            }

            /* 3. 镜像前缀替换：仅 GitHub 需要 */
            if (channel == UpdateChannel.GITHUB && Metadata.HMCL_UPDATE_URL != null) {
                jarUrl = jarUrl.replace("https://github.com", Metadata.HMCL_UPDATE_URL);
            }

            /* 4. 版本号 = tag 去掉 v 前缀 */
            String ver = tag.startsWith("v") ? tag.substring(1) : tag;

            return RemoteVersion.fetch(channel, jarUrl, sha256, ver);

        } catch (Exception e) {
            LOG.error("Failed to check update from " + channel, e);
            return null;
        }
    }

    private static boolean computeOutdated() {
        RemoteVersion latest = latestVersion.get();
        if (latest == null || isDevelopmentVersion(Metadata.VERSION))
            return false;

        if (latest.isForce()
                || latest.getChannel() != UpdateChannel.getChannel()) {
            return !latest.getVersion().equals(Metadata.VERSION);
        }
        return VersionNumber.compare(Metadata.VERSION, latest.getVersion()) < 0;
    }

    private static boolean isDevelopmentVersion(String version) {
        return version.contains("@") || version.contains("SNAPSHOT");
    }

    public static void requestCheckUpdate(UpdateChannel channel) {
        Platform.runLater(() -> {
            if (isCheckingUpdate()) return;
            checkingUpdate.set(true);

            thread(() -> {
                RemoteVersion result = checkUpdate(channel);
                LOG.info("Latest version (" + channel + ") is " + result);

                Platform.runLater(() -> {
                    checkingUpdate.set(false);
                    if (result != null) latestVersion.set(result);
                });
            }, "CCL-UpdateChecker", true);
        });
    }
}