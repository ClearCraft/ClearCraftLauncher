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
package org.jackhuang.hmcl.ui.versions;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.download.game.GameAssetDownloadTask;
import org.jackhuang.hmcl.game.GameDirectoryType;
import org.jackhuang.hmcl.game.GameRepository;
import org.jackhuang.hmcl.game.LauncherHelper;
import org.jackhuang.hmcl.mod.RemoteMod;
import org.jackhuang.hmcl.setting.*;
import org.jackhuang.hmcl.task.*;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.PromptDialogPane;
import org.jackhuang.hmcl.ui.construct.Validator;
import org.jackhuang.hmcl.ui.download.ModpackInstallWizardProvider;
import org.jackhuang.hmcl.ui.export.ExportWizardProvider;
import org.jackhuang.hmcl.util.MinecraftPingUtil;
import org.jackhuang.hmcl.util.ServerAddress;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public final class Versions {
    private static final Label lblPing = new Label();
    private static final ScheduledExecutorService pingExecutor = Executors.newSingleThreadScheduledExecutor();
    private static VersionSetting lastVersionSetting = null;
    private Versions() {
    }

    public static void addNewGame() {
        Controllers.getDownloadPage().showGameDownloads();
        Controllers.navigate(Controllers.getDownloadPage());
    }

    public static void importModpack() {
        Profile profile = Profiles.getSelectedProfile();
        if (profile.getRepository().isLoaded()) {
            Controllers.getDecorator().startWizard(new ModpackInstallWizardProvider(profile), i18n("install.modpack"));
        }
    }

    public static void downloadModpackImpl(Profile profile, String version, RemoteMod.Version file) {
        Path modpack;
        URI downloadURL;
        try {
            downloadURL = NetworkUtils.toURI(file.getFile().getUrl());
            modpack = Files.createTempFile("modpack", ".zip");
        } catch (IOException | IllegalArgumentException e) {
            Controllers.dialog(
                    i18n("install.failed.downloading.detail", file.getFile().getUrl()) + "\n" + StringUtils.getStackTrace(e),
                    i18n("download.failed.no_code"), MessageDialogPane.MessageType.ERROR);
            return;
        }
        Controllers.taskDialog(
                new FileDownloadTask(downloadURL, modpack)
                        .whenComplete(Schedulers.javafx(), e -> {
                            if (e == null) {
                                if (version != null) {
                                    Controllers.getDecorator().startWizard(new ModpackInstallWizardProvider(profile, modpack.toFile(), version));
                                } else {
                                    Controllers.getDecorator().startWizard(new ModpackInstallWizardProvider(profile, modpack.toFile()));
                                }
                            } else if (e instanceof CancellationException) {
                                Controllers.showToast(i18n("message.cancelled"));
                            } else {
                                Controllers.dialog(
                                        i18n("install.failed.downloading.detail", file.getFile().getUrl()) + "\n" + StringUtils.getStackTrace(e),
                                        i18n("download.failed.no_code"), MessageDialogPane.MessageType.ERROR);
                            }
                        }).executor(true),
                i18n("message.downloading"),
                TaskCancellationAction.NORMAL
        );
    }

    public static void deleteVersion(Profile profile, String version) {
        boolean isIndependent = profile.getVersionSetting(version).getGameDirType() == GameDirectoryType.VERSION_FOLDER;
        String message = isIndependent ? i18n("version.manage.remove.confirm.independent", version) :
                i18n("version.manage.remove.confirm.trash", version, version + "_removed");

        JFXButton deleteButton = new JFXButton(i18n("button.delete"));
        deleteButton.getStyleClass().add("dialog-error");
        deleteButton.setOnAction(e -> profile.getRepository().removeVersionFromDisk(version));

        Controllers.confirmAction(message, i18n("message.warning"), MessageDialogPane.MessageType.WARNING, deleteButton);
    }

    public static CompletableFuture<String> renameVersion(Profile profile, String version) {
        return Controllers.prompt(i18n("version.manage.rename.message"), (newName, resolve, reject) -> {
            if (!OperatingSystem.isNameValid(newName)) {
                reject.accept(i18n("install.new_game.malformed"));
                return;
            }
            if (profile.getRepository().renameVersion(version, newName)) {
                resolve.run();
                profile.getRepository().refreshVersionsAsync()
                        .thenRunAsync(Schedulers.javafx(), () -> {
                            if (profile.getRepository().hasVersion(newName)) {
                                profile.setSelectedVersion(newName);
                            }
                        }).start();
            } else {
                reject.accept(i18n("version.manage.rename.fail"));
            }
        }, version);
    }

    public static void exportVersion(Profile profile, String version) {
        Controllers.getDecorator().startWizard(new ExportWizardProvider(profile, version), i18n("modpack.wizard"));
    }

    public static void openFolder(Profile profile, String version) {
        FXUtils.openFolder(profile.getRepository().getRunDirectory(version));
    }

    public static void duplicateVersion(Profile profile, String version) {
        Controllers.prompt(
                new PromptDialogPane.Builder(i18n("version.manage.duplicate.prompt"), (res, resolve, reject) -> {
                    String newVersionName = ((PromptDialogPane.Builder.StringQuestion) res.get(1)).getValue();
                    boolean copySaves = ((PromptDialogPane.Builder.BooleanQuestion) res.get(2)).getValue();
                    Task.runAsync(() -> profile.getRepository().duplicateVersion(version, newVersionName, copySaves))
                            .thenComposeAsync(profile.getRepository().refreshVersionsAsync())
                            .whenComplete(Schedulers.javafx(), (result, exception) -> {
                                if (exception == null) {
                                    resolve.run();
                                } else {
                                    reject.accept(StringUtils.getStackTrace(exception));
                                    profile.getRepository().removeVersionFromDisk(newVersionName);
                                }
                            }).start();
                })
                        .addQuestion(new PromptDialogPane.Builder.HintQuestion(i18n("version.manage.duplicate.confirm")))
                        .addQuestion(new PromptDialogPane.Builder.StringQuestion(null, version,
                                new Validator(i18n("install.new_game.already_exists"), newVersionName -> !profile.getRepository().hasVersion(newVersionName))))
                        .addQuestion(new PromptDialogPane.Builder.BooleanQuestion(i18n("version.manage.duplicate.duplicate_save"), false)));
    }

    public static void updateVersion(Profile profile, String version) {
        Controllers.getDecorator().startWizard(new ModpackInstallWizardProvider(profile, version));
    }

    public static void updateGameAssets(Profile profile, String version) {
        TaskExecutor executor = new GameAssetDownloadTask(profile.getDependency(), profile.getRepository().getVersion(version), GameAssetDownloadTask.DOWNLOAD_INDEX_FORCIBLY, true)
                .executor();
        Controllers.taskDialog(executor, i18n("version.manage.redownload_assets_index"), TaskCancellationAction.NO_CANCEL);
        executor.start();
    }

    public static void cleanVersion(Profile profile, String id) {
        try {
            profile.getRepository().clean(id);
        } catch (IOException e) {
            LOG.warning("Unable to clean game directory", e);
        }
    }

    public static void generateLaunchScript(Profile profile, String id) {
        if (!checkVersionForLaunching(profile, id))
            return;
        ensureSelectedAccount(account -> {
            GameRepository repository = profile.getRepository();
            FileChooser chooser = new FileChooser();
            if (repository.getRunDirectory(id).isDirectory())
                chooser.setInitialDirectory(repository.getRunDirectory(id));
            chooser.setTitle(i18n("version.launch_script.save"));
            chooser.getExtensionFilters().add(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                    ? new FileChooser.ExtensionFilter(i18n("extension.bat"), "*.bat")
                    : new FileChooser.ExtensionFilter(i18n("extension.sh"), "*.sh"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(i18n("extension.ps1"), "*.ps1"));
            File file = chooser.showSaveDialog(Controllers.getStage());
            if (file != null)
                new LauncherHelper(profile, account, id).makeLaunchScript(file);
        });
    }

    public static void launch(Profile profile, String id, Consumer<LauncherHelper> injecter) {
        VersionSetting vs = profile.getVersionSetting(profile.getSelectedVersion());
        if (!checkVersionForLaunching(profile, id))
            return;
        ensureSelectedAccount(account -> {
            LauncherHelper launcherHelper = new LauncherHelper(profile, account, id);
            if (injecter != null)
                injecter.accept(launcherHelper);
            if (!MinecraftPingUtil.check(vs.getServerIp())) {
                showConfirmationDialog(vs.getServerIp(), result -> {
                    if (result) {
                        launcherHelper.launch();
                    }
                }, id, profile);
            }else{
                launcherHelper.launch();
            }
        });
    }

    public static void showConfirmationDialog(String serverip, Consumer<Boolean> callback, String id, Profile profile) {
        com.jfoenix.controls.JFXDialogLayout dialogLayout = new com.jfoenix.controls.JFXDialogLayout();
        VersionSetting versionSetting = profile.getVersionSetting(id);
        lastVersionSetting = versionSetting;

        dialogLayout.setHeading(new Label("设置的IP无法访问!"));
        dialogLayout.setBody(new ProgressIndicator());

        JFXComboBox<String> cboServerIP = new JFXComboBox<>();
        cboServerIP.getItems().addAll(
                "mc.clearcraft.cn",
                "hz.mc.clearcraft.cn",
                "xm.mc.clearcraft.cn",
                "sp.mc.clearcraft.cn",
                "jp.mc.clearcraft.cn",
                "hk.mc.clearcraft.cn",
                "home.clearcraft.cn:15680",
                "自定义"
        );

        // 首先设置当前IP地址到下拉框
        String currentIp = versionSetting.getServerIp();
        if (StringUtils.isNotBlank(currentIp)) {
            // 如果当前IP不在列表中，添加到列表并选择
            if (!cboServerIP.getItems().contains(currentIp)) {
                cboServerIP.getItems().add(currentIp);
            }
            cboServerIP.setValue(currentIp);
        } else {
            cboServerIP.setValue("mc.clearcraft.cn");
        }



        cboServerIP.getEditor().setOnAction(e -> {
            String customValue = cboServerIP.getEditor().getText();
            cboServerIP.setValue(customValue);
        });

        cboServerIP.getEditor().focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) { // 失去焦点时
                String customValue = cboServerIP.getEditor().getText();
                cboServerIP.setValue(customValue);
            }
        });

        HBox serverBox = new HBox(8);
        serverBox.setAlignment(Pos.CENTER_LEFT);


        // 延迟显示标签
        lblPing.setStyle("-fx-text-fill: #666;");
        lblPing.setMinWidth(80);

        // 将组件放入容器
        serverBox.getChildren().addAll(cboServerIP, lblPing);

        cboServerIP.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                if ("自定义".equals(newValue)) {
                    cboServerIP.setEditable(true);
                    cboServerIP.getEditor().clear();
                    cboServerIP.getEditor().requestFocus();
                } else {
                    // 选择其他选项时，禁用编辑模式
                    cboServerIP.setEditable(false);

                    // 正常的ping逻辑 - 只执行一次，不重复执行
                    pingExecutor.schedule(() -> {
                        String latency = MinecraftPingUtil.ping(newValue);
                        Platform.runLater(() -> lblPing.setText(latency));
                    }, 0, TimeUnit.SECONDS);

                    // 验证IP格式
                    if (StringUtils.isNotBlank(newValue)) {
                        try {
                            ServerAddress.parse(newValue);
                        } catch (Exception ignored) {
                            cboServerIP.getSelectionModel().select(oldValue);
                        }
                    }
                }
            }
        });

        cboServerIP.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (StringUtils.isBlank(newVal))
                try {
                    ServerAddress.parse(newVal);
                } catch (Exception ignored) {
                    cboServerIP.getSelectionModel().select(oldVal);
                }
        });

        JFXButton AllowButton = new JFXButton("继续");
        JFXButton DenyButton = new JFXButton("取消");

        AllowButton.getStyleClass().add("dialog-accept");
        AllowButton.setOnAction(e -> {
            callback.accept(true);
            ((javafx.scene.Node) dialogLayout).fireEvent(new DialogCloseEvent());
        });

        DenyButton.getStyleClass().add("dialog-cancel");
        DenyButton.setOnAction(e -> {
            callback.accept(false);
            ((javafx.scene.Node) dialogLayout).fireEvent(new DialogCloseEvent());
        });

        VBox content = new VBox(10,
                new Label("您设置的IP地址 \"" + serverip + "\" 似乎没有响应，此IP可能无法连接，是否继续启动? 您可以从这里更改默认的启动IP,或者选择取消启动"),
                new HBox(serverBox),
                new HBox(10, AllowButton, DenyButton)
        );

        // 移除原有的绑定逻辑，改为手动同步
        // 当用户点击"继续"时，我们需要手动更新versionSetting
        AllowButton.setOnAction(e -> {
            // 更新IP地址
            versionSetting.setServerIp(cboServerIP.getValue());
            callback.accept(true);
            ((javafx.scene.Node) dialogLayout).fireEvent(new DialogCloseEvent());
        });

        DenyButton.setOnAction(e -> {
            callback.accept(false);
            ((javafx.scene.Node) dialogLayout).fireEvent(new DialogCloseEvent());
        });

        dialogLayout.setActions(AllowButton, DenyButton);
        onEscPressed(dialogLayout, DenyButton::fire);
        StackPane updatePane = new StackPane();
        updatePane.getChildren().add(content);
        dialogLayout.setBody(updatePane);
        Controllers.dialog(dialogLayout);
    }
    public static void testGame(Profile profile, String id) {
        launch(profile, id, LauncherHelper::setTestMode);
    }

    private static boolean checkVersionForLaunching(Profile profile, String id) {
        if (id == null || !profile.getRepository().isLoaded() || !profile.getRepository().hasVersion(id)) {
            Controllers.dialog(i18n("version.empty.launch"), i18n("launch.failed"), MessageDialogPane.MessageType.ERROR, () -> {
                Controllers.navigate(Controllers.getDownloadPage());
            });
            return false;
        } else {
            return true;
        }
    }

    private static void ensureSelectedAccount(Consumer<Account> action) {
        Account account = Accounts.getSelectedAccount();
        if (ConfigHolder.isNewlyCreated() && !AuthlibInjectorServers.getServers().isEmpty() &&
                !(account instanceof AuthlibInjectorAccount && AuthlibInjectorServers.getServers().contains(((AuthlibInjectorAccount) account).getServer()))) {
            CreateAccountPane dialog = new CreateAccountPane(AuthlibInjectorServers.getServers().iterator().next());
            dialog.addEventHandler(DialogCloseEvent.CLOSE, e -> {
                Account newAccount = Accounts.getSelectedAccount();
                if (newAccount == null) {
                    // user cancelled operation
                } else {
                    Platform.runLater(() -> action.accept(newAccount));
                }
            });
            Controllers.dialog(dialog);
        } else if (account == null) {
            CreateAccountPane dialog = new CreateAccountPane();
            dialog.addEventHandler(DialogCloseEvent.CLOSE, e -> {
                Account newAccount = Accounts.getSelectedAccount();
                if (newAccount == null) {
                    // user cancelled operation
                } else {
                    Platform.runLater(() -> action.accept(newAccount));
                }
            });
            Controllers.dialog(dialog);
        } else {
            action.accept(account);
        }
    }

    public static void modifyGlobalSettings(Profile profile) {
        Controllers.getSettingsPage().showGameSettings(profile);
        Controllers.navigate(Controllers.getSettingsPage());
    }

    public static void modifyGameSettings(Profile profile, String version) {
        Controllers.getVersionPage().setVersion(version, profile);
        // VersionPage.loadVersion will be invoked after navigation
        Controllers.navigate(Controllers.getVersionPage());
    }
}
