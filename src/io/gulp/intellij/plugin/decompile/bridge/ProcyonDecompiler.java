package io.gulp.intellij.plugin.decompile.bridge;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.strobel.decompiler.PlainTextOutput;

/**
 * Created by bduisenov on 11/03/16.
 */
public class ProcyonDecompiler extends DecompilerImpl {

    private static final Logger logger = Logger.getInstance(ProcyonDecompiler.class);

    @Override
    public CharSequence decompile(VirtualFile file) {
        logger.debug("#decompile({})", file);
        return decompileCreatingTmpFile(file);
    }

    public CharSequence decompileInternal(String internalName) {
        PlainTextOutput output = new PlainTextOutput();
        com.strobel.decompiler.Decompiler.decompile(internalName, output);
        return output.toString();
    }

}
