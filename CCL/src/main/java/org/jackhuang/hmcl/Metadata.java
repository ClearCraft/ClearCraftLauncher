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
package org.jackhuang.hmcl;

import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Stores metadata about this application.
 */
public final class Metadata {
    private Metadata() {
    }

    public static final String NAME = "CCL";
    public static final String FULL_NAME = "ClearCraft Launcher";
    public static final String VERSION = JarUtils.getManifestAttribute("Version", "1.0.0");
    public static final String BUILD = JarUtils.getManifestAttribute("Build", "2");

    public static final String TITLE = NAME + " " + VERSION;
    public static final String FULL_TITLE = FULL_NAME + " v" + VERSION;

    public static final String PUBLISH_URL = "https://hmcl.huangyuhui.net";
    public static final String ABOUT_URL = PUBLISH_URL + "/about";
    public static final String DOWNLOAD_URL = PUBLISH_URL + "/download";
    public static final String HMCL_LATEST_VERSION_URL = "https://api.clearcraft.cn/CCL/last";
    public static final String HMCL_UPDATE_URL = System.getProperty("hmcl.github.proxy", null);

    public static final String DOCS_URL = "https://docs.hmcl.net";
    public static final String CONTACT_URL = DOCS_URL + "/help.html";
    public static final String CHANGELOG_URL = DOCS_URL + "/changelog/";
    public static final String EULA_URL = DOCS_URL + "/eula/hmcl.html";
    public static final String GROUPS_URL = DOCS_URL + "/groups.html";

    public static final String BUILD_CHANNEL = JarUtils.getManifestAttribute("Build-Channel", "nightly");
    public static final String GITHUB_SHA = JarUtils.getManifestAttribute("GitHub-SHA", null);

    public static final Path CURRENT_DIRECTORY = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    public static final Path MINECRAFT_DIRECTORY = OperatingSystem.getWorkingDirectory("minecraft");
    public static final Path HMCL_GLOBAL_DIRECTORY;
    public static final Path HMCL_CURRENT_DIRECTORY;
    public static final Path DEPENDENCIES_DIRECTORY;

    static {
        String hmclHome = System.getProperty("hmcl.home");
        if (hmclHome == null) {
            if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                String xdgData = System.getenv("XDG_DATA_HOME");
                if (StringUtils.isNotBlank(xdgData)) {
                    HMCL_GLOBAL_DIRECTORY = Paths.get(xdgData, "ccl").toAbsolutePath().normalize();
                } else {
                    HMCL_GLOBAL_DIRECTORY = Paths.get(System.getProperty("user.home"), ".local", "share", "ccl").toAbsolutePath().normalize();
                }
            } else {
                HMCL_GLOBAL_DIRECTORY = OperatingSystem.getWorkingDirectory("ccl");
            }
        } else {
            HMCL_GLOBAL_DIRECTORY = Paths.get(hmclHome).toAbsolutePath().normalize();
        }

        String hmclCurrentDir = System.getProperty("hmcl.dir");
        HMCL_CURRENT_DIRECTORY = hmclCurrentDir != null
                ? Paths.get(hmclCurrentDir).toAbsolutePath().normalize()
                : CURRENT_DIRECTORY.resolve(".ccl");
        DEPENDENCIES_DIRECTORY = HMCL_CURRENT_DIRECTORY.resolve("dependencies");
    }

    public static boolean isStable() {
        return "stable".equals(BUILD_CHANNEL);
    }

    public static boolean isDev() {
        return "dev".equals(BUILD_CHANNEL);
    }

    public static boolean isNightly() {
        return !isStable() && !isDev();
    }
}
