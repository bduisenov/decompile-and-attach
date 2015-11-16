package io.gulp.intellij;

import java.io.*;
import java.util.Arrays;
import java.util.function.Function;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.codec.Charsets;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.java.decompiler.IdeaDecompiler;

import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.roots.ui.configuration.PathUIUtils;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiBinaryFile;
import com.intellij.psi.PsiFile;

/**
 * Created by bduisenov on 12/11/15.
 */
public class DecompileAndAttachAction extends AnAction {

    private static final Logger logger = Logger.getInstance(DecompileAndAttachAction.class);

    private String baseDirName = "decompiled";

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();

        if (project == null) {
            return;
        }

        findOrCreateBaseDir(project) //
                .done((baseDir) -> {
                    PsiFile psiFile = event.getData(CommonDataKeys.PSI_FILE);
                    if (psiFile instanceof PsiBinaryFile && "jar".equals(psiFile.getVirtualFile().getExtension())) {
                        VirtualFile jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(psiFile.getVirtualFile());
                        try {
                            File jarFile = FileUtil.createTempFile("decompiled", "tmp");
                            JarOutputStream jarOutputStream = createJarOutputStream(jarFile);
                            String filename = null;
                            try {
                                filename = processor(project, jarOutputStream).apply(jarRoot);
                            } finally {
                                jarOutputStream.close();
                            }
                            copyAndAttach(project, baseDir, jarFile, filename);
                        } catch (IOException e) {
                            Throwables.propagate(e);
                        }
                    } else {
                        System.out.println("error");
                    }
                });
    }

    private void copyAndAttach(Project project, VirtualFile baseDir, File jarFile, String filename) throws IOException {
        final String libraryName = filename.replace(".jar", "-sources.jar");
        File sourceJar = new File(baseDir.getPath() + File.separator + libraryName);
        sourceJar.createNewFile();
        FileUtil.copy(jarFile, sourceJar);
        ApplicationManager.getApplication().runWriteAction(() -> {
            VirtualFile sourceJarVF = LocalFileSystem.getInstance()
                    .refreshAndFindFileByIoFile(sourceJar);
            VirtualFile sourceJarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(sourceJarVF);
            VirtualFile[] roots = PathUIUtils.scanAndSelectDetectedJavaSourceRoots(null, new VirtualFile[]{sourceJarRoot});
            final Library library = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                    .createLibrary(libraryName);
            final Library.ModifiableModel model = library.getModifiableModel();
            for (VirtualFile root : roots) {
                model.addRoot(root, OrderRootType.SOURCES);
            }
            model.commit();
        });
    }

    private Function<VirtualFile, String> processor(Project project, JarOutputStream jarOutputStream) {
        return new Function<VirtualFile, String>() {

            private IdeaDecompiler decompiler = new IdeaDecompiler();

            @Override
            public String apply(VirtualFile head) {
                try {
                    final VirtualFile[] children = head.getChildren();
                    process("", head.getChildren()[0], Iterables.skip(Arrays.asList(children), 1));
                    return head.getName(); // file name
                } catch (IOException e) {
                    Throwables.propagate(e);
                }
                return null;
            }

            private void process(String relativePath, VirtualFile head, Iterable<VirtualFile> tail) throws IOException {
                if (head == null) {
                    return;
                }
                VirtualFile[] children = head.getChildren();
                if (head.isDirectory() && children.length > 0) {
                    relativePath += head.getName() + "/";
                    addDirectoryEntry(jarOutputStream, relativePath);
                    Iterable<VirtualFile> xs = Iterables.skip(Arrays.asList(children), 1);
                    process(relativePath, children[0], xs);
                } else {
                    if (!head.getName().contains("$") && "class".equals(head.getExtension())) {
                        decompileAndSave(relativePath + head.getNameWithoutExtension() + ".java", head);
                    }
                }
                if (tail != null && !Iterables.isEmpty(tail)) {
                    process(relativePath, Iterables.getFirst(tail, null), Iterables.skip(tail, 1));
                }
            }

            private void decompileAndSave(String relativeFilePath, VirtualFile file) throws IOException {
                final CharSequence decompiled = decompiler.getText(file);
                addFileEntry(jarOutputStream, relativeFilePath, decompiled);
            }
        };
    }

    private Promise<VirtualFile> findOrCreateBaseDir(Project project) {
        AsyncPromise<VirtualFile> promise = new AsyncPromise<>();
        ApplicationManager.getApplication().runWriteAction(() -> { //
            VirtualFile baseDir = Arrays.stream(project.getBaseDir().getChildren()) //
                    .filter(vf -> vf.getName().equals(baseDirName)) //
                    .findFirst().orElseGet(() -> {
                try {
                    return project.getBaseDir().createChildDirectory(null, baseDirName);
                } catch (IOException e) {
                    promise.setError(e);
                }
                return null;
            });
            if (promise.getState() != Promise.State.REJECTED) {
                promise.setResult(baseDir);
            }
        });
        return promise;
    }

    private static JarOutputStream createJarOutputStream(File jarFile) throws IOException {
        logger.debug("#createJarOutputStream({})", jarFile);
        final BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(jarFile));
        return new JarOutputStream(outputStream);
    }

    private static void addDirectoryEntry(ZipOutputStream output, String relativePath) throws IOException {
        logger.debug("#addDirectoryEntry(@@output@@, {})", relativePath);
        ZipEntry e = new ZipEntry(relativePath);
        e.setMethod(ZipEntry.STORED);
        e.setSize(0);
        e.setCrc(0);
        output.putNextEntry(e);
        output.closeEntry();
    }

    private static void addFileEntry(ZipOutputStream output, String relativePath, CharSequence decompiled) throws IOException {
        logger.debug("#addFileEntry(@@output@@, {}, @@decompiled@@)", relativePath);
        final ByteArrayInputStream file = new ByteArrayInputStream(
                decompiled.toString().getBytes(Charsets.toCharset("UTF-8")));
        long size = decompiled.length();
        ZipEntry e = new ZipEntry(relativePath);
        if (size == 0) {
            e.setMethod(ZipEntry.STORED);
            e.setSize(0);
            e.setCrc(0);
        }
        output.putNextEntry(e);
        try {
            FileUtil.copy(file, output);
        }
        finally {
            file.close();
        }
        output.closeEntry();
    }

}
