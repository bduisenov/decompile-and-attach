package io.gulp.intellij.plugin.decompile;

import com.intellij.ide.util.BrowseFilesListener;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

public class FolderSelectionForm extends DialogWrapper {

    private static final String title = "Folder For Storing Decompiled Lib Sources";

    private JPanel contentPane;

    private LabeledComponent<TextFieldWithBrowseButton> workingDirComponent;

    public FolderSelectionForm(Project project) {
        super(project);
        init();
        setTitle(title);
        setOKActionEnabled(false);
        workingDirComponent.setComponent(new TextFieldWithBrowseButton(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                if (!workingDirComponent.getComponent().getText().isEmpty()) {
                    setOKActionEnabled(true);
                }
            }
        }));
        workingDirComponent.getComponent().addBrowseFolderListener(
                "Test Title", "", project,
                new FileChooserDescriptor(false, true, false, false, false, false) {
                    @Override
                    public boolean isFileSelectable(VirtualFile file) {
                        if (!super.isFileSelectable(file)) return false;
                        return true;
                    }
                });
        workingDirComponent.getComponent().setEditable(false);
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        return contentPane;
    }

    public String getSelectedPath() {
        return workingDirComponent.getComponent().getText();
    }
}
