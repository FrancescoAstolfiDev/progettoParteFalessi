package project.statefull;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import project.models.ClassFile;
import project.models.MethodInstance;
import project.models.Release;
import project.models.Ticket;
import project.utils.EntryProject;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenjpaEntry implements EntryProject {
    private  List<MethodInstance> methods;
    private  List<ClassFile> classFiles;
    private  boolean methodsSetted = false;
    private  Map<String,MethodInstance> filledMethods;
    private  Map<String,List<Ticket>> ticketsOfInterest = new HashMap<>();
    private static  final String REFACTORED_METHODS_CLASS_PATH = "openjpa-kernel/src/main/java/org/apache/openjpa/kernel/jpql/JPQLExpressionBuilder.java";
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenjpaEntry.class);
    public void analyzeJavaFile() {
        try {
            Path path = Paths.get("C:\\isw2\\refactoring\\openjpa\\openjpa-0.9.7\\JPQLExpressionBuilder.java");
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> parseResult = parser.parse(path);

            parseResult.getResult().ifPresent(cu -> {
                if (parseResult.isSuccessful()) {
                    methods = new ArrayList<>();
                    classFiles = new ArrayList<>();

                    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                        // Costruisce il nome completo della classe includendo la gerarchia
                        String className = getFullClassName(classDecl);
                        ClassFile filledClass = new ClassFile();
                        filledClass.setClassName(className);
                        filledClass.setPath(REFACTORED_METHODS_CLASS_PATH);

                        // Per ogni metodo nella classe
                        classDecl.getMethods().forEach(methodDecl -> {
                            MethodInstance method = new MethodInstance();
                            method.setMethodName(methodDecl.getNameAsString());
                            method.setClassName(className);  // Usa il nome completo della classe
                            method.setClassPath(REFACTORED_METHODS_CLASS_PATH);
                            method.setSignature(methodDecl.getSignature().asString());
                            methods.add(method);
                            filledClass.addMethod(method);
                        });
                        classFiles.add(filledClass);
                    });
                } else {
                    LOGGER.error("Errore durante il parsing del file");
                }
            });

            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                LOGGER.error("Errore durante il parsing del file");
            }
        } catch (Exception e) {
            LOGGER.error("Errore durante l'analisi del file: {}", e.getMessage());
        }
    }


    private static String getFullClassName(ClassOrInterfaceDeclaration classDecl) {
        // Se la classe ha un genitore ed è una classe interna
        var parentNode = classDecl.getParentNode();
        if (parentNode.isPresent()) {
            var parent = parentNode.get();
            if (parent instanceof ClassOrInterfaceDeclaration parentClass) {
                return getFullClassName(parentClass) + "$" + classDecl.getNameAsString();
            }
        }
        return classDecl.getNameAsString();
    }


    @Override
    public String getProjectName() {
        return "openjpa";
    }

    @Override
    public double getSplit() {
        return ConstantSize.SPLIT_PERCENTAGE;
    }

    @Override
    public Path getRefactoredSourcePath() {
        return ConstantsWindowsFormat.REFACTOR_BASE_OPENJPA_PATH.resolve("openjpa-kernel");
    }

    @Override
    public Path getRefactoredClassPath() {
        return Path.of(REFACTORED_METHODS_CLASS_PATH);
    }

    @Override
    public String getRefactoredReleaseName() {
        return "0.9.7";
    }

    public int getNumStepDataset() {
        return 12;
    }

    @Override
    public List<MethodInstance> getInitializedRefactoredMethods(){
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
    public void setMethods(Map<String, MethodInstance> methods) {
        methodsSetted = true;
        this.filledMethods = methods;
    }

    @Override
    public MethodInstance getMethodToRefactor() {
        MethodInstance methodToRefactor = new MethodInstance();
        methodToRefactor.setMethodName("eval");
        methodToRefactor.setSignature("eval(JPQLNode node)");
        methodToRefactor.setClassPath(REFACTORED_METHODS_CLASS_PATH);
        methodToRefactor.setFullSignature(methodToRefactor.getMethodName() + "#" + methodToRefactor.getSignature());
        return methodToRefactor;
    }

    @Override
    public void setTickets(Release release, List<Ticket> tickets) {

        ticketsOfInterest.put(release.getName(), tickets);
    }

    @Override
    public Map<String, List<Ticket>> getTickets() {

        return ticketsOfInterest;
    }

    @Override
    public void setRefactoredClass(List<Release> releaseList) {
        int idRelease = Release.getId(getRefactoredReleaseName(), releaseList);
        Release release = releaseList.get(idRelease - 1);
        analyzeJavaFile();
        for(ClassFile classFile:classFiles){
            release.addClassFile(classFile);
        }

    }
}
