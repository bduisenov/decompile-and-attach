package io.gulp.intellij;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import java.io.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.codec.Charsets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.java.decompiler.IdeaDecompiler;

import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.ui.configuration.PathUIUtils;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.CommonProcessors;

/**
 * Created by bduisenov on 12/11/15.
 */
public class DecompileAndAttachAction extends AnAction {

    private static final Logger logger = Logger.getInstance(DecompileAndAttachAction.class);

    private String baseDirName = "decompiled";

    @Override
    public void update(AnActionEvent e) {
        Presentation presentation = e.getPresentation();
        presentation.setEnabled(false);
        presentation.setVisible(false);
        VirtualFile virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (virtualFile != null && //
                "jar".equals(virtualFile.getExtension()) && //
                e.getProject() != null) {
            presentation.setEnabled(true);
            presentation.setVisible(true);
        }
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        Project project = event.getProject();

        if (project == null) {
            return;
        }

        findOrCreateBaseDir(project) //
                .done((baseDir) -> {
                    VirtualFile sourceVF = event.getData(CommonDataKeys.VIRTUAL_FILE);
                    checkNotNull(sourceVF, "event#getData(VIRTUAL_FILE) returned null");
                    if ("jar".equals(sourceVF.getExtension())) {
                        new Task.Backgroundable(project, "Decompiling...", false) {

                            @Override
                            public void run(@NotNull ProgressIndicator indicator) {
                                indicator.setFraction(0.0);
                                VirtualFile jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(sourceVF);
                                try {
                                    File tmpJarFile = FileUtil.createTempFile("decompiled", "tmp");
                                    String filename;
                                    try (JarOutputStream jarOutputStream = createJarOutputStream(tmpJarFile)) {
                                        filename = processor(jarOutputStream).apply(jarRoot);
                                    }
                                    indicator.setFraction(.90);
                                    indicator.setText("Attaching decompiled sources to project");
                                    copyAndAttach(project, baseDir, sourceVF, tmpJarFile, filename);
                                } catch (Exception e) {
                                    new Notification("DecompileAndAttach", "Jar lib couldn't be decompiled", e.getMessage(),
                                            NotificationType.ERROR).notify(project);
                                    Throwables.propagate(e);
                                }
                                indicator.setFraction(1.0);
                            }
                        }.queue();
                    } else {
                        Messages.showErrorDialog("You must choose jar file.", "Invalid File");
                    }
                });
    }

    private void copyAndAttach(Project project, VirtualFile baseDir, VirtualFile sourceVF, File tmpJarFile, String filename)
            throws IOException {
        String libraryName = filename.replace(".jar", "-sources.jar");
        File resultJar = new File(baseDir.getPath() + File.separator + libraryName);
        boolean newFile = resultJar.createNewFile();
        logger.debug("file exists?: ", newFile);
        FileUtil.copy(tmpJarFile, resultJar);
        FileUtil.delete(tmpJarFile);

        if (newFile) {
            // library is added to project for a first time
            ApplicationManager.getApplication().invokeAndWait(() -> {
                VirtualFile resultJarVF = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resultJar);
                checkNotNull(resultJarVF, "could not find Virtual File of %s", resultJar.getAbsolutePath());
                VirtualFile resultJarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(resultJarVF);
                VirtualFile[] roots = PathUIUtils.scanAndSelectDetectedJavaSourceRoots(null, new VirtualFile[] {resultJarRoot});
                new WriteCommandAction<Void>(project) {

                    @Override
                    protected void run(@NotNull Result<Void> result) throws Throwable {
                        final Module currentModule = ProjectRootManager.getInstance(project).getFileIndex()
                                .getModuleForFile(sourceVF);
                        checkNotNull(currentModule, "could not find current module");
                        Optional<Library> moduleLib = findExistingModuleLib(currentModule, sourceVF);
                        checkState(moduleLib.isPresent(), "could not find library in module dependencies");
                        Library.ModifiableModel model = moduleLib.get().getModifiableModel();
                        for (VirtualFile root : roots) {
                            model.addRoot(root, OrderRootType.SOURCES);
                        }
                        model.commit();

                        new Notification("DecompileAndAttach",
                                "Jar Sources Added", "decompiled sources " + libraryName
                                        + " where added successfully to dependency of a module " + currentModule.getName(),
                                NotificationType.INFORMATION).notify(project);
                    }
                }.execute();
            }, ModalityState.NON_MODAL);
        }

    }

    private Optional<Library> findExistingModuleLib(Module module, VirtualFile sourceVF) {
        final CommonProcessors.FindProcessor<OrderEntry> processor = new CommonProcessors.FindProcessor<OrderEntry>() {

            @Override
            protected boolean accept(OrderEntry orderEntry) {
                final String[] urls = orderEntry.getUrls(OrderRootType.CLASSES);
                final boolean contains = Arrays.asList(urls).contains("jar://" + sourceVF.getPath() + "!/");
                return contains && orderEntry instanceof LibraryOrderEntry;
            }
        };
        ModuleRootManager.getInstance(module).orderEntries().forEach(processor);
        Library result = null;
        if (processor.getFoundValue() != null) {
            result = ((LibraryOrderEntry) processor.getFoundValue()).getLibrary();
        }
        return Optional.ofNullable(result);
    }

    private Function<VirtualFile, String> processor(JarOutputStream jarOutputStream) {
        return new Function<VirtualFile, String>() {

            private IdeaDecompiler decompiler = new IdeaDecompiler();

            @Override
            public String apply(VirtualFile head) {
                try {
                    VirtualFile[] children = head.getChildren();
                    checkState(children.length > 0, "jar file is empty");
                    process("", head.getChildren()[0], Iterables.skip(Arrays.asList(children), 1), new HashSet<>());
                    final String result = head.getName();
                    logger.debug("#apply({}): returned {}", head, result);
                    return result; // file name
                } catch (IOException e) {
                    Throwables.propagate(e);
                }
                return null;
            }

            private void process(String relativePath, VirtualFile head, Iterable<VirtualFile> tail, Set<String> writtenPaths) throws IOException {
                if (head == null) {
                    return;
                }
                VirtualFile[] children = head.getChildren();
                if (head.isDirectory() && children.length > 0) {
                    String path = relativePath + head.getName() + "/";
                    addDirectoryEntry(jarOutputStream, path, writtenPaths);
                    Iterable<VirtualFile> xs = Iterables.skip(Arrays.asList(children), 1);
                    process(path, children[0], xs, writtenPaths);
                } else {
                    if (!head.getName().contains("$") && "class".equals(head.getExtension())) {
                        decompileAndSave(relativePath + head.getNameWithoutExtension() + ".java", head, writtenPaths);
                    }
                }
                if (tail != null && !Iterables.isEmpty(tail)) {
                    process(relativePath, Iterables.getFirst(tail, null), Iterables.skip(tail, 1), writtenPaths);
                }
            }

            private void decompileAndSave(String relativeFilePath, VirtualFile file, Set<String> writternPaths) throws IOException {
                CharSequence decompiled = decompiler.getText(file);
                addFileEntry(jarOutputStream, relativeFilePath, writternPaths, decompiled);
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
        BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(jarFile));
        return new JarOutputStream(outputStream);
    }

    private static void addDirectoryEntry(ZipOutputStream output, String relativePath, Set<String> writtenPaths)
            throws IOException {
        if (!writtenPaths.add(relativePath)) return;

        ZipEntry e = new ZipEntry(relativePath);
        e.setMethod(ZipEntry.STORED);
        e.setSize(0);
        e.setCrc(0);
        output.putNextEntry(e);
        output.closeEntry();
    }

    private static void addFileEntry(ZipOutputStream jarOS, String relativePath, Set<String> writtenPaths,
            CharSequence decompiled) throws IOException {
        if (!writtenPaths.add(relativePath)) return;

        ByteArrayInputStream fileIS = new ByteArrayInputStream(
                decompiled.toString().getBytes(Charsets.toCharset("UTF-8")));
        long size = decompiled.length();
        ZipEntry e = new ZipEntry(relativePath);
        if (size == 0) {
            e.setMethod(ZipEntry.STORED);
            e.setSize(0);
            e.setCrc(0);
        }
        jarOS.putNextEntry(e);
        try {
            FileUtil.copy(fileIS, jarOS);
        }
        finally {
            fileIS.close();
        }
        jarOS.closeEntry();
    }

}
