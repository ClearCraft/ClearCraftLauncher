/*
 * ClearCraft Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.text.TextFlow;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.JFXHyperlink;
import org.jackhuang.hmcl.upgrade.RemoteVersion;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.jackhuang.hmcl.Metadata.CHANGELOG_URL;
import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public final class UpgradeDialog extends JFXDialogLayout {

    private static final String GITHUB_RELEASE_URL =
            "https://api.github.com/repos/ClearCraft/ClearCraftLauncher/releases/latest";

    public UpgradeDialog(RemoteVersion remoteVersion, Runnable updateRunnable) {
        maxWidthProperty().bind(Controllers.getScene().widthProperty().multiply(0.7));
        maxHeightProperty().bind(Controllers.getScene().heightProperty().multiply(0.7));

        setHeading(new Label(i18n("update.changelog")));
        setBody(new ProgressIndicator());

        /* ========= 异步拿日志 ========= */
        Task.supplyAsync(Schedulers.io(), () -> {
            /* 1. 先去 GitHub 试试 */
            String gitHubBody = fetchBodyFromGitHub();
            if (gitHubBody != null && !gitHubBody.isBlank()) {
                return markdownToNode(gitHubBody);
            }

            /* 2. 失败则回落到老 HTML 逻辑 */
            return fetchOldHtmlChangelog(remoteVersion);
        }).whenComplete(Schedulers.javafx(), (node, ex) -> {
            if (ex != null) {
                LOG.warning("Failed to load changelog", ex);
                FXUtils.openLink(GITHUB_RELEASE_URL);
                setBody();
            } else {
                ScrollPane sp = new ScrollPane(node);
                sp.setFitToWidth(true);
                FXUtils.smoothScrolling(sp);
                setBody(sp);
            }
        }).start();

        /* ========= 底部按钮 ========= */
        JFXHyperlink openInBrowser = new JFXHyperlink(i18n("web.view_in_browser"));
        openInBrowser.setExternalLink(GITHUB_RELEASE_URL);

        JFXButton updateButton = new JFXButton(i18n("update.accept"));
        updateButton.getStyleClass().add("dialog-accept");
        updateButton.setOnAction(e -> updateRunnable.run());

        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));

        setActions(openInBrowser, updateButton, cancelButton);
        onEscPressed(this, cancelButton::fire);
    }

    /* ===================== GitHub 取 body ===================== */
    private String fetchBodyFromGitHub() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_RELEASE_URL))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return new JSONObject(resp.body()).optString("body", null);
            }
        } catch (Exception e) {
            LOG.warning("Unable to fetch GitHub release body", e);
        }
        return null;
    }

    /* ===================== 老 HTML 逻辑 ===================== */
    private Node fetchOldHtmlChangelog(RemoteVersion remoteVersion) throws IOException {
        String url = CHANGELOG_URL + remoteVersion.getChannel().channelName + ".html";
        Document doc = Jsoup.parse(new java.net.URL(url), 30_000);
        org.jsoup.nodes.Node node = doc.selectFirst("#nowchange");
        if (node == null || !"h1".equals(node.nodeName()))
            throw new IOException("Cannot find #nowchange in document");

        HTMLRenderer renderer = new HTMLRenderer(uri -> {
            LOG.info("Open link: " + uri);
            FXUtils.openLink(uri.toString());
        });

        do {
            if ("h1".equals(node.nodeName()) && !"nowchange".equals(node.attr("id")))
                break;
            renderer.appendNode(node);
            node = node.nextSibling();
        } while (node != null);

        renderer.mergeLineBreaks();
        return renderer.render();
    }

    /* ===================== 极简 Markdown → TextFlow ===================== */
    private Node markdownToNode(String md) {
        TextFlow flow = new TextFlow();
        /* 仅做最简单的分段、转义 */
        for (String paragraph : md.split("\n\n")) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) continue;
            Label label = new Label(paragraph + "\n\n");
            label.setWrapText(true);
            flow.getChildren().add(label);
        }
        return flow;
    }
}