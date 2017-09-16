package verifier;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import jdk.nashorn.internal.runtime.regexp.RegExp;
import jdk.nashorn.internal.runtime.regexp.RegExpMatcher;
import jdk.nashorn.internal.runtime.regexp.joni.Regex;

import javax.xml.soap.Text;
import java.io.*;
import java.nio.Buffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Controller {

    //Verification related elements.
    @FXML
    private Button mCompileButton;

    @FXML
    private Button mRunButton;

    @FXML
    private Button mKillButton;

    //Source related elements.
    @FXML
    private Button mChooseDirectoryButton;

    @FXML
    private TextField mSourceDirectoryField;
    private List<String> mSourceFiles = new ArrayList<>();
    private List<String> mDepedencies = new ArrayList<>();

    //Executable targets
    @FXML
    private ComboBox mTargetComboBox;

    @FXML
    private TextArea mLogTextArea;

    @FXML
    private TextField mRuntimeParams;

    private Process mActiveProcess;

    //Populate the source for the first time. Ugly, but it works and I'm not going to develop this idea any further.
    boolean sourceNeedsPopulated = true;

    public void initialize() {
        initVerificationElements();
        initSourceElements();
    }

    private void initSourceElements(){
        mChooseDirectoryButton.setOnMouseReleased((MouseEvent e) -> {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            Stage stage = new Stage();
            File file = directoryChooser.showDialog(stage);
            if (file != null) {
                mSourceDirectoryField.setText(file.toPath().toString());
                populateSourceFiles();
                if (!mTargetComboBox.getItems().isEmpty()) {
                    mTargetComboBox.getSelectionModel().select(0);
                }
            }
        });
    }

    private void initVerificationElements() {
        mCompileButton.setOnMouseReleased((MouseEvent e) -> {
            try {
                mCompileButton.disableProperty().set(true);
                mRunButton.disableProperty().set(true);

                log("Compiling...");
                if (!populateSourceFiles()) {
                    mCompileButton.disableProperty().set(false);
                    mRunButton.disableProperty().set(false);
                    return;
                }

                String dependencies = String.join(":", mDepedencies);

                List<String> command = new ArrayList<>();
                command.add("javac");
                command.add("-cp");
                command.add(".:" + dependencies);
                command.addAll(mSourceFiles);

                Process process = Runtime.getRuntime().exec(command.toArray(new String[command.size()]),
                        null,
                        new File(mSourceDirectoryField.getText()));



                BufferedReader outputReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

                boolean isErrored = false;
                String s = null;
                while ((s = errorReader.readLine()) != null) {
                    log(s);
                    if (!s.matches(".*deprecat.*")) {
                        isErrored = true;
                    }
                }
                while ((s = outputReader.readLine()) != null) {
                    log(s);
                }

                if (isErrored) {
                    log("Compilation error.");
                }
                else {
                    log("Compiled successfully.");
                    mTargetComboBox.getSelectionModel().select(0);
                }

                mCompileButton.disableProperty().set(false);
                mRunButton.disableProperty().set(false);
            } catch (IOException e1) {
                log("Java source cannot be found.");
            }
        });

        mRunButton.setOnMouseReleased((MouseEvent e) -> {
            if (mTargetComboBox.getSelectionModel().getSelectedItem() == null) {
                log("Please specify a target to run.");
                return;
            }

            try {
                mCompileButton.disableProperty().set(true);
                mRunButton.disableProperty().set(true);

                String dependencies = String.join(":", mDepedencies);

                log("Running target " + mTargetComboBox.getSelectionModel().getSelectedItem().toString());
                mActiveProcess = Runtime.getRuntime().exec("java -cp .:" + dependencies  + " " + mTargetComboBox.getSelectionModel().getSelectedItem().toString() + " " + mRuntimeParams.getText(),
                                            null,
                                            new File(mSourceDirectoryField.getText()));


                BufferedReader outputReader = new BufferedReader(new InputStreamReader(mActiveProcess.getInputStream()));
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(mActiveProcess.getErrorStream()));

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String s = null;
                            while ((s = outputReader.readLine()) != null) {
                                log(s);
                            }
                            while ((s = errorReader.readLine()) != null) {
                                log(s);
                            }

                            mRunButton.disableProperty().set(false);
                            mCompileButton.disableProperty().set(false);
                        }
                        catch (IOException e) {
                            mCompileButton.disableProperty().set(false);
                            mRunButton.disableProperty().set(false);
                        }
                    }
                }).start();
            } catch (IOException e1) {
                e1.printStackTrace();
                mRunButton.disableProperty().set(false);
                mCompileButton.disableProperty().set(false);
            }
        });

        mKillButton.setOnMouseReleased((MouseEvent e) -> {
            if (mActiveProcess != null) {
                mActiveProcess.destroyForcibly();
                mActiveProcess = null;
                log("Killed process.");
            }
            else {
                log("No process running.");
            }
        });
    }

    private boolean populateSourceFiles() {
        if (mSourceDirectoryField.getText().isEmpty() && !sourceNeedsPopulated) {
            log("Source not populated.");
            return false;
        }

        File sourceDir = new File(mSourceDirectoryField.getText());

        if (sourceDir.isDirectory()) {
            mSourceFiles.clear();
            mTargetComboBox.getItems().clear();

            try {
                Iterator<Path> files = Files.find(Paths.get(mSourceDirectoryField.getText()), 999, (p, bfa) -> bfa.isRegularFile() && p.toString().matches(".+\\.(java|jar)")).iterator();
                while (files.hasNext()) {
                    Path path = files.next();

                    if (path.toString().contains(".jar")) {
                        mDepedencies.add(path.toString());
                    }
                    else {
                        mSourceFiles.add(path.toString());
                    }
                    grabExecutableTarget(path.toString());
                }
            } catch (IOException e) {
                e.printStackTrace();
                log(e.getStackTrace().toString());
                return false;
            }
        }
        else {
            log("Please select a directory.");
            return false;
        }
        return true;
    }

    private void log(String text) {
        mLogTextArea.appendText(text + "\n");
    }

    private void grabExecutableTarget(String path) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(path));

            String s = null;
            String packageName = "";
            String className = "";

            boolean foundClass = false;
            while ((s = reader.readLine()) != null) {
                if (s.matches("package\\s*[\\dA-Za-z]*;")) {
                    Matcher match = Pattern.compile("package\\s*([\\dA-Za-z]*);").matcher(s);
                    if (match.find()) {
                        packageName = match.group(1);
                    }
                }
                if (s.matches("\\s*(public)?\\s*class[\\w\\s]*\\{?\\s*") && !foundClass) {
                    Matcher match = Pattern.compile("\\s*(public)?\\s*class\\s*(\\w*)[\\w\\s]*\\{?\\s*").matcher(s);
                    if (match.find()) {
                        className = match.group(2);
                        foundClass = true;
                    }
                }
                if (s.matches("\\s*public\\s*static\\s*void\\s*main\\s*\\(String\\[\\]\\s*\\w*\\)\\s*\\{?\\s*")) {
                    if (!foundClass) {
                        log("Cannot determine class name for file: " + path);
                        log("Does it end with '{' or whitespace?");
                    }
                    mTargetComboBox.getItems().add(packageName + "." + className);
                    sourceNeedsPopulated = false;
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
