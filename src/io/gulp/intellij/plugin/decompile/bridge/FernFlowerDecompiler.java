package io.gulp.intellij.plugin.decompile.bridge;

import org.jetbrains.java.decompiler.IdeaDecompiler;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * Created by bduisenov on 11/03/16.
 */
public class FernFlowerDecompiler extends DecompilerImpl {

    private static final Logger logger = Logger.getInstance(FernFlowerDecompiler.class);

    private IdeaDecompiler decompiler = new IdeaDecompiler();

    @Override
    public CharSequence decompile(VirtualFile file) {
        logger.debug("#decompile({})", file);
        return decompiler.getText(file);
    }
}
