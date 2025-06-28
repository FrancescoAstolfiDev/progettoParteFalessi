package project.statefull;

import project.models.MethodInstance;
import project.utils.EntryProject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BookkeeperEntry implements EntryProject {
    private  boolean methodsSetted =false;
    private Map<String,MethodInstance> filledMethods;
    @Override
    public String getProjectName() {
        return "bookkeeper";
    }
    @Override
    public double getSplit() {
        return 50/100.0;
    }

    @Override
    public Path getRefactoredSourcePath() {
        return ConstantsWindowsFormat.REFACTOR_BASE_BOOKKEEPER_PATH.resolve("hedwig-server");
    }
    @Override
    public Path getRefactoredClassPath() {
        return ConstantsWindowsFormat.REFACTORED_CLASS_BOOKKEEPER;
    }
    @Override
    public String getRefactoredReleaseName() {
        return "4.1.0";
    }
    @Override
    public List<MethodInstance> getInitializedRefactoredMethods(){
        List<MethodInstance> methods = new ArrayList<>();
        String[] methodNames = {
                "run_refactored",
                "initializeConsole",
                "runInteractiveMode",
                "isJLineAvailable",
                "runJLineConsole",
                "runSimpleConsole",
                "executeShutdownSequence",
                "setupJLineConsole",
                "initializeConsoleReader",
                "setupCompletor",
                "setupHistory",
                "getHistoryFile",
                "loadHistoryEntries",
                "initializeCommandMethods",
                "runCommandLoop"
        };
        String[] methodSignature = {
                "run_refactored()",
                "initializeConsole()",
                "runInteractiveMode()",
                "isJLineAvailable()",
                "runJLineConsole()",
                "runSimpleConsole()",
                "executeShutdownSequence()",
                "setupJLineConsole()",
                "initializeConsoleReader()",
                "setupCompletor()",
                "setupHistory()",
                "getHistoryFile()",
                "loadHistoryEntries()",
                "initializeCommandMethods()",
                "runCommandLoop()"
        };

        for ( int i=0;i<methodNames.length;i++) {
            MethodInstance method=new MethodInstance();
            method.setMethodName(methodNames[i]);
            method.setSignature(methodSignature[i]);
            method.setFullSignature(methodNames[i]+"#"+methodSignature[i] );
            method.setClassPath("src/main/java/org/apache/hedwig/admin/console/HedwigConsole.java");
            methods.add(method);
        }
        return methods;
    }
    @Override
    public Map<String, MethodInstance> getFilledRefactoredMethods() {
       return this.filledMethods;
    }
    @Override
    public boolean isRefactoredMethodsFilled() {
        return methodsSetted;
    }
    @Override
    public void  setMethods (Map<String,MethodInstance> methods){
        methodsSetted =true;
        this.filledMethods=methods;
    }

}
