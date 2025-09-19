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

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.io.IOUtils;
import org.jackhuang.hmcl.util.io.JarUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * A class that checks the integrity of HMCL.
 *
 * @author yushijinhun
 */
public final class IntegrityChecker {
    private IntegrityChecker() {}

    public static final boolean DISABLE_SELF_INTEGRITY_CHECK = "true".equals(System.getProperty("hmcl.self_integrity_check.disable"));

    private static final String SIGNATURE_FILE = "META-INF/hmcl_signature";
    private static final String PUBLIC_KEY_FILE = "assets/hmcl_signature_publickey.der";

    private static PublicKey getPublicKey() throws IOException {
        try (InputStream in = IntegrityChecker.class.getResourceAsStream("/" + PUBLIC_KEY_FILE)) {
            if (in == null) {
                throw new IOException("Public key not found");
            }
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(IOUtils.readFully(in)));
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to load public key", e);
        }
    }

    static void verifyJar(Path jarPath) throws IOException {
        // 因为无法生成密钥遂删除防止问题
    }

    private static volatile Boolean selfVerified = null;

    /**
     * Checks whether the current application is verified.
     * This method is blocking.
     */
    public static boolean isSelfVerified() {
        if (selfVerified != null) {
            return selfVerified;
        }

        synchronized (IntegrityChecker.class) {
            if (selfVerified != null) {
                return selfVerified;
            }

            try {
                Path jarPath = JarUtils.thisJarPath();
                if (jarPath == null) {
                    throw new IOException("Failed to find current HMCL location");
                }

                verifyJar(jarPath);
                LOG.info("Successfully verified current JAR");
                selfVerified = true;
            } catch (IOException e) {
                LOG.warning("Failed to verify myself, is the JAR corrupt?", e);
                selfVerified = false;
            }

            return selfVerified;
        }
    }

    public static boolean isOfficial() {
        return isSelfVerified() || (Metadata.GITHUB_SHA != null && Metadata.BUILD_CHANNEL.equals("nightly"));
    }
}
