package io.gulp.intellij.plugin.decompile;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.intellij.notification.NotificationType.*;

import java.io.*;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.common.base.Strings;
import org.apache.commons.codec.Charsets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.java.decompiler.IdeaDecompiler;

import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.ui.configuration.PathUIUtils;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.compiled.ClassFileDecompilers;
import com.intellij.util.CommonProcessors;

/**
 * Created by bduisenov on 12/11/15.
 */
public class DecompileAndAttachAction extends AnAction {

    private static final Logger logger = Logger.getInstance(DecompileAndAttachAction.class);

    private final String baseDirProjectSettingsKey = "io.gulp.intellij.baseDir";

    /**
     * show 'decompile and attach' option only for *.jar files
     * @param e
     */
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

        final Optional<String> baseDirPath = getBaseDirPath(project);
        if (!baseDirPath.isPresent()) {
            return;
        }
        VirtualFile[] sourceVFs = event.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY);
        checkState(sourceVFs != null && sourceVFs.length > 0, "event#getData(VIRTUAL_FILE_ARRAY) returned empty array");
        new Task.Backgroundable(project, "Decompiling...", true) {

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setFraction(0.1);
                Arrays.asList(sourceVFs).stream() //
                        .filter((vf) -> "jar".equals(vf.getExtension())) //
                        .forEach((sourceVF) -> process(project, baseDirPath.get(), sourceVF, indicator, 1D / sourceVFs.length));
                indicator.setFraction(1.0);
            }

            @Override
            public boolean shouldStartInBackground() {
                return true;
            }
        }.queue();
    }

    private Optional<String> getBaseDirPath(Project project) {
        String result = null;
        final String baseDirPath = PropertiesComponent.getInstance(project).getValue(baseDirProjectSettingsKey);
        if (Strings.isNullOrEmpty(baseDirPath)) {
            final FolderSelectionForm form = new FolderSelectionForm(project);
            if (form.showAndGet()) {
                result = form.getSelectedPath();
                PropertiesComponent.getInstance(project).setValue(baseDirProjectSettingsKey, result);
            }
        } else {
            result = baseDirPath;
        }
        return Optional.ofNullable(result);
    }

    private void process(Project project, String baseDirPath, VirtualFile sourceVF, ProgressIndicator indicator, double fractionStep) {
        indicator.setText("Decompiling '" + sourceVF.getName() + "'");
        VirtualFile jarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(sourceVF);
        try {
            File tmpJarFile = FileUtil.createTempFile("decompiled", "tmp");
            Pair<String, Set<String>> result;
            try (JarOutputStream jarOutputStream = createJarOutputStream(tmpJarFile)) {
                result = processor(jarOutputStream).apply(jarRoot);
            }
            indicator.setFraction(indicator.getFraction() + (fractionStep * 70 / 100));
            indicator.setText("Attaching decompiled sources for '" + sourceVF.getName() + "'");
            result.second.forEach((failedFile) -> new Notification("DecompileAndAttach", "Decompilation problem",
                    "fernflower could not decompile class " + failedFile, WARNING).notify(project));
            File resultJar = copy(project, baseDirPath, sourceVF, tmpJarFile, result.first);
            attach(project, sourceVF, resultJar);
            indicator.setFraction(indicator.getFraction() + (fractionStep * 30 / 100));
            FileUtil.delete(tmpJarFile);
        } catch (Exception e) {
            if (!(e instanceof ProcessCanceledException)) {
                new Notification("DecompileAndAttach", "Jar lib couldn't be decompiled", e.getMessage(), ERROR).notify(project);
            }
            Throwables.propagate(e);
        }
    }

    private File copy(Project project, String baseDirPath, VirtualFile sourceVF, File tmpJarFile, String filename)
            throws IOException {
        String libraryName = filename.replace(".jar", "-sources.jar");
        File result = new File(baseDirPath + File.separator + libraryName);
        FileUtil.copy(tmpJarFile, result);
        return result;
    }

    private void attach(final Project project, final VirtualFile sourceVF, File resultJar) {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            VirtualFile resultJarVF = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resultJar);
            checkNotNull(resultJarVF, "could not find Virtual File of %s", resultJar.getAbsolutePath());
            VirtualFile resultJarRoot = JarFileSystem.getInstance().getJarRootForLocalFile(resultJarVF);
            VirtualFile[] roots = PathUIUtils.scanAndSelectDetectedJavaSourceRoots(null,
                    new VirtualFile[] {resultJarRoot});
            new WriteCommandAction<Void>(project) {

                @Override
                protected void run(@NotNull Result<Void> result) throws Throwable {
                    final Module currentModule = ProjectRootManager.getInstance(project).getFileIndex()
                            .getModuleForFile(sourceVF);
                    checkNotNull(currentModule, "could not find current module");
                    Optional<Library> moduleLib = findModuleDependency(currentModule, sourceVF);
                    checkState(moduleLib.isPresent(), "could not find library in module dependencies");
                    Library.ModifiableModel model = moduleLib.get().getModifiableModel();
                    for (VirtualFile root : roots) {
                        model.addRoot(root, OrderRootType.SOURCES);
                    }
                    model.commit();

                    new Notification("DecompileAndAttach", "Jar Sources Added", "decompiled sources " + resultJar.getName()
                            + " where added successfully to dependency of a module '" + currentModule.getName() + "'",
                            INFORMATION).notify(project);
                }
            }.execute();
        } , ModalityState.NON_MODAL);
    }

    private Optional<Library> findModuleDependency(Module module, VirtualFile sourceVF) {
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

    /**
     * recursively goes through jar archive and decompiles all found classes.
     * in case if decompilation fails on a class, the class name is put to {@code failed} Set
     * and then is returned with result.
     * @param jarOutputStream
     * @return {@code Pair<String, Set<String>>} containing the filename of a library and a set of
     * class names which failed to decompile
     */
    private Function<VirtualFile, Pair<String, Set<String>>> processor(JarOutputStream jarOutputStream) {
        return new Function<VirtualFile, Pair<String, Set<String>>>() {

            private IdeaDecompiler decompiler = new IdeaDecompiler();

            private Set<String> failed = new HashSet<>();

            @Override
            public Pair<String, Set<String>> apply(VirtualFile head) {
                try {
                    VirtualFile[] children = head.getChildren();
                    checkState(children.length > 0, "jar file is empty");
                    process("", head.getChildren()[0], Iterables.skip(Arrays.asList(children), 1), new HashSet<>());
                    final String libraryName = head.getName();
                    final Pair<String, Set<String>> result = Pair.create(libraryName, failed);
                    logger.debug("#apply({}): returned {}", head, result);
                    return result;
                } catch (IOException e) {
                    Throwables.propagate(e);
                }
                return null;
            }

            private void process(String relativePath, VirtualFile head, Iterable<VirtualFile> tail, Set<String> writtenPaths)
                    throws IOException {
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

            private void decompileAndSave(String relativeFilePath, VirtualFile file, Set<String> writternPaths)
                    throws IOException {
                try {
                    CharSequence decompiled = decompiler.getText(file);
                    addFileEntry(jarOutputStream, relativeFilePath, writternPaths, decompiled);
                } catch (ClassFileDecompilers.Light.CannotDecompileException e) {
                    failed.add(file.getName());
                }
            }
        };
    }

    private static JarOutputStream createJarOutputStream(File jarFile) throws IOException {
        BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(jarFile));
        return new JarOutputStream(outputStream);
    }

    private static void addDirectoryEntry(ZipOutputStream output, String relativePath, Set<String> writtenPaths)
            throws IOException {
        if (!writtenPaths.add(relativePath))
            return;

        ZipEntry e = new ZipEntry(relativePath);
        e.setMethod(ZipEntry.STORED);
        e.setSize(0);
        e.setCrc(0);
        output.putNextEntry(e);
        output.closeEntry();
    }

    private static void addFileEntry(ZipOutputStream jarOS, String relativePath, Set<String> writtenPaths,
            CharSequence decompiled) throws IOException {
        if (!writtenPaths.add(relativePath))
            return;

        ByteArrayInputStream fileIS = new ByteArrayInputStream(decompiled.toString().getBytes(Charsets.toCharset("UTF-8")));
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
        } finally {
            fileIS.close();
        }
        jarOS.closeEntry();
    }

}
