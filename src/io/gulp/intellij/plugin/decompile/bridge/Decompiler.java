package io.gulp.intellij.plugin.decompile.bridge;

import com.intellij.openapi.vfs.VirtualFile;

/**
 * Created by bduisenov on 11/03/16.
 */
public class Decompiler {

    private DecompilerImpl impl;

    public Decompiler(DecompilerImpl impl) {
        this.impl = impl;
    }

    public DecompilerImpl getImpl() {
        return impl;
    }

    public CharSequence decompile(VirtualFile file) {
        return getImpl().decompile(file);
    }
}
