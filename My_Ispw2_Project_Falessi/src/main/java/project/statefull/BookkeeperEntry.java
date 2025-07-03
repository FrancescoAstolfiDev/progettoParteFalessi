package project.statefull;

import project.models.ClassFile;
import project.models.MethodInstance;
import project.models.Release;
import project.models.Ticket;
import project.utils.EntryProject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BookkeeperEntry implements EntryProject {
    private  boolean methodsSetted =false;
    private  Map<String,MethodInstance> filledMethods;
    private  Map<String,List<Ticket>> ticketsOfInterest = new HashMap<>();
    private  List<ClassFile> refactoredClass = new ArrayList<>();
    private  static final String REFACTORED_CLASS_PATH ="hedwig-server/src/main/java/org/apache/hedwig/admin/console/HedwigConsole.java";
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
        return Path.of(REFACTORED_CLASS_PATH);
    }
    @Override
    public String getRefactoredReleaseName() {
        return "4.1.0";
    }

    public int getNumStepDataset() {
        return 4;
    }

    @Override
    public List<MethodInstance> getInitializedRefactoredMethods(){
        List<MethodInstance> methods = new ArrayList<>();
        String[] methodNamesExternal = {
                "run_refactored",
                "initializeConsole",
                "runInteractiveMode",
                "isJLineAvailable",
                "runJLineConsole",
                "runSimpleConsole",
                "executeShutdownSequence",
                "executeLine"
        };

        String[] methodSignatureExternal = {
                "run_refactored()",
                "initializeConsole()",
                "runInteractiveMode()",
                "isJLineAvailable()",
                "runJLineConsole()",
                "runSimpleConsole()",
                "executeShutdownSequence()",
                "executeLine()"
        };

        String[] consoleHandlerMethodNames={
                "setupJLineConsole",
                "initializeConsoleReader",
                "setupCompletor",
                "setupHistory",
                "getHistoryFile",
                "loadHistoryEntries",
                "initializeCommandMethods",
                "runCommandLoop"

        };
        String [] consoleHandlerMethodSignatures={
                "setupJLineConsole()",
                "initializeConsoleReader()",
                "setupCompletor()",
                "setupHistory()",
                "getHistoryFile()",
                "loadHistoryEntries()",
                "initializeCommandMethods()",
                "runCommandLoop()"
        };

        for ( int i=0;i<methodNamesExternal.length;i++) {
            MethodInstance method=new MethodInstance();
            method.setMethodName(methodNamesExternal[i]);
            method.setSignature(methodSignatureExternal[i]);
            method.setFullSignature(methodNamesExternal[i]+"#"+methodNamesExternal[i] );
            method.setClassPath(refactoredClass.get(0).getPath());
            method.setClassName(refactoredClass.get(0).getClassName());
            refactoredClass.get(0).addMethod(method);
            methods.add(method);
        }
        for ( int i=0;i<consoleHandlerMethodNames.length;i++) {
            MethodInstance method=new MethodInstance();
            method.setMethodName(consoleHandlerMethodNames[i]);
            method.setSignature(consoleHandlerMethodSignatures[i]);
            method.setFullSignature(consoleHandlerMethodNames[i]+"#"+consoleHandlerMethodSignatures[i] );
            method.setClassPath(refactoredClass.get(1).getPath());
            method.setClassName(refactoredClass.get(1).getClassName());
            refactoredClass.get(1).addMethod(method);
            methods.add(method);
        }

        return methods;
    }
    @Override
    public void  setRefactoredClass(List<Release> releaseList){
        int idRelease=Release.getId(getRefactoredReleaseName(),releaseList);
        Release release=releaseList.get(idRelease-1);
        List<ClassFile> classFiles = new ArrayList<>();
        ClassFile classFile = new ClassFile();
        classFile.setClassName("HedwigConsole");
        classFile.setPath(REFACTORED_CLASS_PATH);
        classFiles.add(classFile);
        release.addClassFile(classFile);
        classFile = new ClassFile();
        classFile.setClassName("HedwigConsole$ConsoleHandler");
        classFile.setPath(REFACTORED_CLASS_PATH);
        classFiles.add(classFile);
        refactoredClass=classFiles;
        release.addClassFile(classFile);
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
    @Override
    public MethodInstance getMethodToRefactor() {
        MethodInstance methodToRefactor=new MethodInstance();
        methodToRefactor.setMethodName("run");
        methodToRefactor.setSignature("run()");
        methodToRefactor.setClassPath("src/main/java/org/apache/hedwig/admin/console/HedwigConsole.java");
        methodToRefactor.setFullSignature(methodToRefactor.getMethodName()+"#"+methodToRefactor.getSignature() );
        return methodToRefactor;
    }
    @Override
    public void setTickets(Release release, List<Ticket> tickets) {
        if(ticketsOfInterest.containsKey(release.getName())){
            ticketsOfInterest.put(release.getName()+"test",tickets);
        } else {
            ticketsOfInterest.put(release.getName(),tickets);
        }
    }
    @Override
    public Map<String,List<Ticket>> getTickets() {
        return ticketsOfInterest;
    }

}
