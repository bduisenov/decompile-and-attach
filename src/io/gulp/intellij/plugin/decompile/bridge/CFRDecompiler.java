package io.gulp.intellij.plugin.decompile.bridge;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import org.benf.cfr.reader.PluginRunner;
import org.benf.cfr.reader.util.getopt.OptionsImpl;

import java.util.HashMap;

/**
 * Created by bduisenov on 12/03/16.
 */
public class CFRDecompiler extends DecompilerImpl {

    private static final Logger logger = Logger.getInstance(CFRDecompiler.class);

    private PluginRunner decompiler = new PluginRunner(new HashMap<String, String>(){{
        put(OptionsImpl.SHOW_CFR_VERSION.getName(), "false");
    }});

    @Override
    public CharSequence decompile(VirtualFile file) {
        logger.debug("decompile({})", file);
        return decompileCreatingTmpFile(file);
    }

    @Override
    public CharSequence decompileInternal(String absolutePath) {
        return decompiler.getDecompilationFor(absolutePath);
    }
}
