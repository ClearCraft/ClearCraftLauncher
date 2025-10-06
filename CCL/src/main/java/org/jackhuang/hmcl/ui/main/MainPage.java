/*
 * Hello Minecraft! Launcher
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
package org.jackhuang.hmcl.ui.main;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXPopup;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import javafx.stage.Stage;
import org.jackhuang.hmcl.Launcher;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.VersionList;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.AnimationUtils;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.PopupMenu;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.versions.GameItem;
import org.jackhuang.hmcl.ui.versions.Versions;
import org.jackhuang.hmcl.upgrade.RemoteVersion;
import org.jackhuang.hmcl.upgrade.UpdateChecker;
import org.jackhuang.hmcl.upgrade.UpdateHandler;
import org.jackhuang.hmcl.util.Holder;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.javafx.BindingMapping;
import org.jackhuang.hmcl.util.javafx.MappedObservableList;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.MenuItem;
import java.awt.Image;
import java.net.URL;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.jackhuang.hmcl.download.RemoteVersion.Type.RELEASE;
import static org.jackhuang.hmcl.setting.ConfigHolder.config;
import static org.jackhuang.hmcl.ui.FXUtils.SINE;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public final class MainPage extends StackPane implements DecoratorPage {
    private static final String ANNOUNCEMENT = "announcement";

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>();

    private final PopupMenu menu = new PopupMenu();

    private final StackPane popupWrapper = new StackPane(menu);
    private final JFXPopup popup = new JFXPopup(popupWrapper);

    private final StringProperty currentGame = new SimpleStringProperty(this, "currentGame");
    private final BooleanProperty showUpdate = new SimpleBooleanProperty(this, "showUpdate");
    private final ObjectProperty<RemoteVersion> latestVersion = new SimpleObjectProperty<>(this, "latestVersion");
    private final ObservableList<Version> versions = FXCollections.observableArrayList();
    private final ObservableList<Node> versionNodes;
    private Profile profile;

    private static TrayIcon trayIcon;
    private static boolean traySupported = SystemTray.isSupported();
    private static final Path MIGRATION_FLAG_FILE = Metadata.HMCL_GLOBAL_DIRECTORY.resolve(".migration_done");

    private TransitionPane announcementPane;
    private final StackPane updatePane;
    private final JFXButton menuButton;

    {
        initializeSystemTray();
        HBox titleNode = new HBox(8);
        titleNode.setPadding(new Insets(0, 0, 0, 2));
        titleNode.setAlignment(Pos.CENTER_LEFT);

        ImageView titleIcon = new ImageView(FXUtils.newBuiltinImage("/assets/img/cc-icon-title.png"));
        Label titleLabel = new Label(Metadata.FULL_TITLE);
        titleLabel.getStyleClass().add("jfx-decorator-title");
        titleNode.getChildren().setAll(titleIcon, titleLabel);

        state.setValue(new State(null, titleNode, false, false, true));

        setPadding(new Insets(20));

        // 添加垂直布局容器（VBox）来管理卡片，避免层叠覆盖
        VBox contentBox = new VBox(16); // 16像素间距
        contentBox.setAlignment(Pos.TOP_LEFT); // 顶部左对齐
        contentBox.getChildren().addAll(
                getCard("最新公告", HTMLtoTEXT(getannouncement()))
        );

        getChildren().add(contentBox); // 将VBox添加到MainPage中

        updatePane = new StackPane();
        updatePane.setVisible(false);
        updatePane.getStyleClass().add("bubble");
        FXUtils.setLimitWidth(updatePane, 230);
        FXUtils.setLimitHeight(updatePane, 55);
        StackPane.setAlignment(updatePane, Pos.TOP_RIGHT);
        FXUtils.onClicked(updatePane, this::onUpgrade);
        FXUtils.onChange(showUpdateProperty(), this::showUpdate);

        {
            HBox hBox = new HBox();
            hBox.setSpacing(12);
            hBox.setAlignment(Pos.CENTER_LEFT);
            StackPane.setAlignment(hBox, Pos.CENTER_LEFT);
            StackPane.setMargin(hBox, new Insets(9, 12, 9, 16));
            {
                Label lblIcon = new Label();
                lblIcon.setGraphic(SVG.UPDATE.createIcon(Theme.whiteFill(), 20));

                TwoLineListItem prompt = new TwoLineListItem();
                prompt.setSubtitle(i18n("update.bubble.subtitle"));
                prompt.setPickOnBounds(false);
                prompt.titleProperty().bind(BindingMapping.of(latestVersionProperty()).map(latestVersion ->
                        latestVersion == null ? "" : i18n("update.bubble.title", latestVersion.getVersion())));

                hBox.getChildren().setAll(lblIcon, prompt);
            }

            JFXButton closeUpdateButton = new JFXButton();
            closeUpdateButton.setGraphic(SVG.CLOSE.createIcon(Theme.whiteFill(), 10));
            StackPane.setAlignment(closeUpdateButton, Pos.TOP_RIGHT);
            closeUpdateButton.getStyleClass().add("toggle-icon-tiny");
            StackPane.setMargin(closeUpdateButton, new Insets(5));
            closeUpdateButton.setOnAction(e -> closeUpdateBubble());

            updatePane.getChildren().setAll(hBox, closeUpdateButton);
        }

        StackPane launchPane = new StackPane();
        launchPane.getStyleClass().add("launch-pane");
        launchPane.setMaxWidth(230);
        launchPane.setMaxHeight(55);
        launchPane.setOnScroll(event -> {
            int index = IntStream.range(0, versions.size())
                    .filter(i -> versions.get(i).getId().equals(getCurrentGame()))
                    .findFirst().orElse(-1);
            if (index < 0) return;
            if (event.getDeltaY() > 0) {
                index--;
            } else {
                index++;
            }
            profile.setSelectedVersion(versions.get((index + versions.size()) % versions.size()).getId());
        });
        StackPane.setAlignment(launchPane, Pos.BOTTOM_RIGHT);
        {
            JFXButton launchButton = new JFXButton();
            launchButton.setPrefWidth(230);
            launchButton.setPrefHeight(55);
            //launchButton.setButtonType(JFXButton.ButtonType.RAISED);
            launchButton.setOnAction(e -> launch());
            launchButton.setDefaultButton(true);
            launchButton.setClip(new Rectangle(-100, -100, 310, 200));
            {
                VBox graphic = new VBox();
                graphic.setAlignment(Pos.CENTER);
                graphic.setTranslateX(-7);
                graphic.setMaxWidth(200);

                Label launchLabel = new Label(i18n("version.launch"));
                launchLabel.setStyle("-fx-font-size: 16px;");

                Label currentLabel = new Label();
                currentLabel.setStyle("-fx-font-size: 12px;");

                // 绑定当前游戏版本文本
                currentLabel.textProperty().bind(Bindings.createStringBinding(this::getCurrentGame, currentGameProperty()));

                // 初始设置和监听变化
                Runnable updateGraphic = () -> {
                    graphic.getChildren().clear();
                    if (getCurrentGame() == null) {
                        graphic.getChildren().add(launchLabel);
                        graphic.setSpacing(0);
                    } else {
                        graphic.getChildren().addAll(launchLabel, currentLabel);
                        graphic.setSpacing(4);
                    }
                };

                updateGraphic.run();
                currentGameProperty().addListener((observable, oldValue, newValue) -> updateGraphic.run());

                launchButton.setGraphic(graphic);
            }

            Rectangle separator = new Rectangle();
            separator.setWidth(1);
            separator.setHeight(57);
            separator.setTranslateX(95);
            separator.setMouseTransparent(true);

            menuButton = new JFXButton();
            menuButton.setPrefHeight(55);
            menuButton.setPrefWidth(230);
            //menuButton.setButtonType(JFXButton.ButtonType.RAISED);
            menuButton.setStyle("-fx-font-size: 15px;");
            menuButton.setOnAction(e -> onMenu());
            menuButton.setClip(new Rectangle(211, -100, 100, 200));
            StackPane graphic = new StackPane();
            Node svg = SVG.ARROW_DROP_UP.createIcon(Theme.foregroundFillBinding(), 30);
            StackPane.setAlignment(svg, Pos.CENTER_RIGHT);
            graphic.getChildren().setAll(svg);
            graphic.setTranslateX(6);
            FXUtils.installFastTooltip(menuButton, i18n("version.switch"));
            menuButton.setGraphic(graphic);

            EventHandler<MouseEvent> secondaryClickHandle = event -> {
                if (event.getButton() == MouseButton.SECONDARY && event.getClickCount() == 1) {
                    menuButton.fire();
                    event.consume();
                }
            };
            launchButton.addEventHandler(MouseEvent.MOUSE_CLICKED, secondaryClickHandle);
            menuButton.addEventHandler(MouseEvent.MOUSE_CLICKED, secondaryClickHandle);

            launchPane.getChildren().setAll(launchButton, separator, menuButton);
        }

        getChildren().addAll(updatePane, launchPane);

        menu.setMaxHeight(365);
        menu.setMaxWidth(545);
        menu.setAlwaysShowingVBar(true);
        FXUtils.onClicked(menu, popup::hide);
        versionNodes = MappedObservableList.create(versions, version -> {
            Node node = PopupMenu.wrapPopupMenuItem(new GameItem(profile, version.getId()));
            FXUtils.onClicked(node, () -> profile.setSelectedVersion(version.getId()));
            return node;
        });
        Bindings.bindContent(menu.getContent(), versionNodes);
        checkAndMigrateHMCLData();
    }
    private void checkAndMigrateHMCLData() {
        // 检查独立的标志文件
        if (Files.exists(MIGRATION_FLAG_FILE)) {
            return;
        }

        Platform.runLater(() -> {
            try {
                Path hmclGlobalDir = getHMCLGlobalDirectory();

                if (hmclGlobalDir != null && Files.exists(hmclGlobalDir) && isDirectoryNotEmpty(hmclGlobalDir)) {
                    Controllers.dialog(new MessageDialogPane.Builder(
                            i18n("migration.title"),
                            i18n("migration.message"),
                            MessageDialogPane.MessageType.QUESTION)
                            .addAction(i18n("button.yes"), () -> {
                                try {
                                    Files.createFile(MIGRATION_FLAG_FILE);
                                } catch (IOException e) {
                                    LOG.warning("Failed to create migration flag file: " + e.getMessage());
                                }
                                performMigration(hmclGlobalDir);
                                restartApplication();
                            })
                            .addCancel(i18n("button.no"), () -> {
                                // 创建标志文件
                                try {
                                    Files.createFile(MIGRATION_FLAG_FILE);
                                } catch (IOException e) {
                                    LOG.warning("Failed to create migration flag file: " + e.getMessage());
                                }
                            })
                            .build());
                } else {
                    // 没有HMCL数据，创建标志文件
                    try {
                        Files.createFile(MIGRATION_FLAG_FILE);
                    } catch (IOException e) {
                        LOG.warning("Failed to create migration flag file: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                LOG.warning("Failed to check HMCL migration: " + e.getMessage());
            }
        });
    }


    /**
     * 初始化系统任务栏图标
     */
    private void initializeSystemTray() {
        if (!traySupported) {
            LOG.info("System tray is not supported on this platform");
            return;
        }

        try {
            // 获取系统托盘
            SystemTray tray = SystemTray.getSystemTray();

            // 加载图标图像
            java.awt.Image image = loadTrayIcon();
            if (image == null) {
                LOG.warning("Failed to load tray icon image");
                return;
            }

            // 创建托盘图标
            trayIcon = new TrayIcon(image, Metadata.TITLE);
            trayIcon.setImageAutoSize(true);

            // 创建弹出菜单
            java.awt.PopupMenu popupMenu = createTrayPopupMenu();
            trayIcon.setPopupMenu(popupMenu);

            // 添加鼠标点击事件
            trayIcon.addActionListener(e -> showMainWindow());

            // 添加到系统托盘
            tray.add(trayIcon);

            LOG.info("System tray icon initialized successfully");

        } catch (Exception e) {
            LOG.warning("Failed to initialize system tray: " + e.getMessage());
        }
    }

    /**
     * 加载任务栏图标
     */
    private java.awt.Image loadTrayIcon() {
        try {
            // 尝试从内置资源加载
            URL imageUrl = getClass().getResource("/assets/img/cc-icon-title.png");
            if (imageUrl != null) {
                return javax.imageio.ImageIO.read(imageUrl);
            }

            // 如果内置资源不存在，尝试创建简单图标
            return createFallbackIcon();
        } catch (IOException e) {
            LOG.warning("Failed to load tray icon from resources: " + e.getMessage());
            return createFallbackIcon();
        }
    }

    /**
     * 创建备用图标
     */
    private java.awt.Image createFallbackIcon() {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();

        g.setColor(new java.awt.Color(66, 133, 244)); // 蓝色背景
        g.fillRect(0, 0, 16, 16);

        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 10));
        g.drawString("CC", 3, 11);

        g.dispose();
        return image;
    }

    /**
     * 创建托盘弹出菜单
     */
    private java.awt.PopupMenu createTrayPopupMenu() {
        java.awt.PopupMenu popup = new java.awt.PopupMenu();

        MenuItem showItem = new MenuItem(i18n("tray.show"));
        showItem.addActionListener(e -> showMainWindow());
        popup.add(showItem);

        popup.addSeparator();

        MenuItem exitItem = new MenuItem(i18n("tray.exit"));
        exitItem.addActionListener(e -> exitApplication());
        popup.add(exitItem);

        return popup;
    }


    private void exitApplication() {
        Platform.runLater(() -> {
            // 移除托盘图标
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
            // 退出应用程序
            Launcher.stopApplication();
        });
    }
    /**
     * 显示主窗口
     */
    private void showMainWindow() {
        Platform.runLater(() -> {
            Stage primaryStage = (Stage) Controllers.getStage();
            if (primaryStage != null) {
                // 显示窗口
                primaryStage.show();
                primaryStage.toFront();

                // 如果窗口是最小化的，恢复它
                if (primaryStage.isIconified()) {
                    primaryStage.setIconified(false);
                }
            }
        });
    }

    /**
     * 获取HMCL全局目录路径（根据提供的代码逻辑）
     */
    private Path getHMCLGlobalDirectory() {
        try {
            String hmclHome = System.getProperty("hmcl.home");
            if (hmclHome == null) {
                if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                    String xdgData = System.getenv("XDG_DATA_HOME");
                    if (StringUtils.isNotBlank(xdgData)) {
                        return Paths.get(xdgData, "hmcl").toAbsolutePath().normalize();
                    } else {
                        return Paths.get(System.getProperty("user.home"), ".local", "share", "hmcl").toAbsolutePath().normalize();
                    }
                } else {
                    // 使用HMCL的工作目录获取逻辑
                    return OperatingSystem.getWorkingDirectory("hmcl");
                }
            } else {
                return Paths.get(hmclHome).toAbsolutePath().normalize();
            }
        } catch (Exception e) {
            LOG.warning("Failed to get HMCL global directory: " + e.getMessage());
            return null;
        }
    }

    /**
     * 检查目录是否不为空
     */
    private boolean isDirectoryNotEmpty(Path directory) {
        try {
            return Files.exists(directory) &&
                    Files.isDirectory(directory) &&
                    Files.list(directory).findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 执行数据迁移
     */
    private void performMigration(Path hmclGlobalDir) {
        try {
            Path cclGlobalDir = Metadata.HMCL_GLOBAL_DIRECTORY; // CCL的全局目录

            // 确保目标目录存在
            Files.createDirectories(cclGlobalDir);

            // 复制所有文件和子目录
            Files.walk(hmclGlobalDir)
                    .forEach(source -> {
                        try {
                            Path destination = cclGlobalDir.resolve(hmclGlobalDir.relativize(source));
                            if (Files.isDirectory(source)) {
                                Files.createDirectories(destination);
                            } else {
                                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            LOG.warning("Failed to copy file: " + source + " - " + e.getMessage());
                        }
                    });

            LOG.info("Successfully migrated HMCL data from " + hmclGlobalDir + " to " + cclGlobalDir);
        } catch (IOException e) {
            LOG.warning("Failed to migrate HMCL data: " + e.getMessage());
            Controllers.showToast(i18n("migration.failed"));
        }
    }

    /**
     * 重启应用程序
     */
    private void restartApplication() {
        try {

            // 获取当前JAR文件路径
            String jarPath = Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(jarPath);

            // 构建重启命令
            List<String> command = new ArrayList<>();
            command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
            command.add("-jar");
            command.add(jarFile.getAbsolutePath());

            // 添加系统属性（如果存在）
            if (System.getProperty("hmcl.home") != null) {
                command.add("-Dhmcl.home=" + System.getProperty("hmcl.home"));
            }
            if (System.getProperty("hmcl.dir") != null) {
                command.add("-Dhmcl.dir=" + System.getProperty("hmcl.dir"));
            }

            LOG.info("Restarting application with command: " + String.join(" ", command));

            // 启动新进程
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(System.getProperty("user.dir"))); // 在当前目录启动
            builder.start();

            // 退出当前应用程序
            Launcher.stopApplication();

        } catch (Exception e) {
            LOG.warning("Failed to restart application: " + e.getMessage());
            Controllers.dialog(i18n("restart.failed"), i18n("restart"), MessageDialogPane.MessageType.ERROR);
        }
    }
    private void showUpdate(boolean show) {
        doAnimation(show);

        if (show && getLatestVersion() != null && !Objects.equals(config().getPromptedVersion(), getLatestVersion().getVersion())) {
            Controllers.dialog(new MessageDialogPane.Builder("", i18n("update.bubble.title", getLatestVersion().getVersion()), MessageDialogPane.MessageType.INFO)
                    .addAction(i18n("button.view"), () -> {
                        config().setPromptedVersion(getLatestVersion().getVersion());
                        onUpgrade();
                    })
                    .addCancel(null)
                    .build());
        }
    }


    private void doAnimation(boolean show) {
        if (AnimationUtils.isAnimationEnabled()) {
            Duration duration = Duration.millis(320);
            Timeline nowAnimation = new Timeline();
            nowAnimation.getKeyFrames().addAll(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(updatePane.translateXProperty(), show ? 260 : 0, SINE)),
                    new KeyFrame(duration,
                            new KeyValue(updatePane.translateXProperty(), show ? 0 : 260, SINE)));
            if (show) nowAnimation.getKeyFrames().add(
                    new KeyFrame(Duration.ZERO, e -> updatePane.setVisible(true)));
            else nowAnimation.getKeyFrames().add(
                    new KeyFrame(duration, e -> updatePane.setVisible(false)));
            nowAnimation.play();
        } else {
            updatePane.setVisible(show);
        }
    }
    public static void cleanupTrayIcon() {
        if (traySupported && trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (Exception e) {
                LOG.warning("Failed to remove tray icon: " + e.getMessage());
            }
        }
    }
    private void launch() {
        Profile profile = Profiles.getSelectedProfile();
        Versions.launch(profile, profile.getSelectedVersion(), null);
    }

    private void launchNoGame() {
        DownloadProvider downloadProvider = DownloadProviders.getDownloadProvider();
        VersionList<?> versionList = downloadProvider.getVersionListById("game");

        Holder<String> gameVersionHolder = new Holder<>();
        Task<?> task = versionList.refreshAsync("")
                .thenSupplyAsync(() -> versionList.getVersions("").stream()
                        .filter(it -> it.getVersionType() == RELEASE)
                        .sorted()
                        .findFirst()
                        .orElseThrow(() -> new IOException("No versions found")))
                .thenComposeAsync(version -> {
                    Profile profile = Profiles.getSelectedProfile();
                    DefaultDependencyManager dependency = profile.getDependency();
                    String gameVersion = gameVersionHolder.value = version.getGameVersion();

                    return dependency.gameBuilder()
                            .name(gameVersion)
                            .gameVersion(gameVersion)
                            .buildAsync();
                })
                .whenComplete(any -> profile.getRepository().refreshVersions())
                .whenComplete(Schedulers.javafx(), (result, exception) -> {
                    if (exception == null) {
                        profile.setSelectedVersion(gameVersionHolder.value);
                        launch();
                    } else if (exception instanceof CancellationException) {
                        Controllers.showToast(i18n("message.cancelled"));
                    } else {
                        LOG.warning("Failed to install game", exception);
                        Controllers.dialog(StringUtils.getStackTrace(exception),
                                i18n("install.failed"),
                                MessageDialogPane.MessageType.WARNING);
                    }
                });
        Controllers.taskDialog(task, i18n("version.launch.empty.installing"), TaskCancellationAction.NORMAL);
    }

    private void onMenu() {
        Node contentNode;
        if (menu.getContent().isEmpty()) {
            Label placeholder = new Label(i18n("version.empty"));
            placeholder.setStyle("-fx-padding: 10px; -fx-text-fill: gray; -fx-font-style: italic;");
            contentNode = placeholder;
        } else {
            contentNode = menu;
        }

        popupWrapper.getChildren().setAll(contentNode);

        if (popup.isShowing()) {
            popup.hide();
        }
        popup.show(
                menuButton,
                JFXPopup.PopupVPosition.BOTTOM,
                JFXPopup.PopupHPosition.RIGHT,
                0,
                -menuButton.getHeight()
        );
    }

    private void onUpgrade() {
        RemoteVersion target = UpdateChecker.getLatestVersion();
        if (target == null) {
            return;
        }
        UpdateHandler.updateFrom(target);
    }

    private void closeUpdateBubble() {
        showUpdate.unbind();
        showUpdate.set(false);
    }

    @Override
    public ReadOnlyObjectWrapper<State> stateProperty() {
        return state;
    }

    public String getCurrentGame() {
        return currentGame.get();
    }

    public StringProperty currentGameProperty() {
        return currentGame;
    }

    public void setCurrentGame(String currentGame) {
        this.currentGame.set(currentGame);
    }

    public boolean isShowUpdate() {
        return showUpdate.get();
    }

    public BooleanProperty showUpdateProperty() {
        return showUpdate;
    }

    public void setShowUpdate(boolean showUpdate) {
        this.showUpdate.set(showUpdate);
    }

    public RemoteVersion getLatestVersion() {
        return latestVersion.get();
    }

    public ObjectProperty<RemoteVersion> latestVersionProperty() {
        return latestVersion;
    }

    public void setLatestVersion(RemoteVersion latestVersion) {
        this.latestVersion.set(latestVersion);
    }

    public void initVersions(Profile profile, List<Version> versions) {
        FXUtils.checkFxUserThread();
        this.profile = profile;
        this.versions.setAll(versions);
    }

    public TransitionPane getCard(String title, TextFlow body) {
        TransitionPane announcementPane = new TransitionPane();
        VBox announcementCard = new VBox();

        BorderPane titleBar = new BorderPane();
        titleBar.getStyleClass().add("title");
        titleBar.setLeft(new Label(title));

        Node hideNode = SVG.CLOSE.createIcon(Theme.blackFill(), 20);
        hideNode.setCursor(Cursor.HAND);
        titleBar.setRight(hideNode);
        FXUtils.onClicked(hideNode, () -> {
        });

        body.setLineSpacing(4);

        announcementCard.getChildren().setAll(titleBar, body);
        announcementCard.setSpacing(16);
        announcementCard.getStyleClass().addAll("card", "announcement");

        VBox announcementBox = new VBox(16);
        announcementBox.getChildren().add(announcementCard);

        announcementPane.setContent(announcementBox, ContainerAnimations.NONE);
        return announcementPane;
    }


    public TextFlow HTMLtoTEXT(String rawHtml) {
        Document doc = Jsoup.parse(rawHtml);
        TextFlow docbody = new TextFlow();

        for (Element element : doc.body().children()) {
            if (element.tagName().equals("a")) {
                Hyperlink link = new Hyperlink(element.text());
                link.setOnAction(e -> Controllers.onHyperlinkAction(element.attr("href")));
                docbody.getChildren().add(link);
            } else {
                // 处理普通文本：将 <br> 替换为 \n
                String htmlContent = element.html(); // 获取当前元素的 HTML 内容（包含 <br>）
                String textWithBreaks = htmlContent.replaceAll("<br\\s*/?>", ""); // 替换 <br> 为 \n
                Text text = new Text(textWithBreaks);
                docbody.getChildren().add(text);
            }
        }
        return docbody;
    }

    public String getannouncement() {
        String url = "https://api.clearcraft.cn/bulletin";
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                try {
                    return new JSONObject(response.toString()).getJSONObject("value").getString("HTML"); // 保存返回值到变量
                } catch (JSONException e) {
                    LOG.error("获取最新公告失败", e);
                    return "<p>获取最新公告失败，详细请查看日志文件</p>";
                }
            } catch (IOException e) {
                LOG.error("获取最新公告失败", e);
                return "<p>获取最新公告失败，详细请查看日志文件</p>";
            }
        }catch (IOException e) {
            LOG.error("获取最新公告失败", e);
            return "<p>获取最新公告失败，详细请查看日志文件</p>";
        }
    }
}
