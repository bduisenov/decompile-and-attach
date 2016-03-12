package io.gulp.intellij.plugin.decompile.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.help.UnsupportedOperationException;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * Created by bduisenov on 11/03/16.
 */
abstract class DecompilerImpl {

    private static final Logger logger = Logger.getInstance(DecompilerImpl.class);

    public abstract CharSequence decompile(VirtualFile file);

    public CharSequence decompileInternal(String absolutePath) {
        throw new UnsupportedOperationException();
    }

    protected CharSequence decompileCreatingTmpFile(VirtualFile file) {
        String tmpDirPath = FileUtil.getTempDirectory();
        Path tmpClassFile = Paths.get(tmpDirPath + file.getPath().substring(file.getPath().indexOf("!") + 1));

        CharSequence result;
        try {
            Files.createDirectories(tmpClassFile.getParent());
            Files.copy(file.getInputStream(), tmpClassFile);
            result = decompileInternal(tmpClassFile.toAbsolutePath().toString());
        } catch (IOException e) {
            logger.error(e);
            result = e.getMessage();
        } finally {
            try {
                Files.delete(tmpClassFile);
            } catch (IOException e) {
                logger.error(e);
            }
        }
        return result;
    }

}
