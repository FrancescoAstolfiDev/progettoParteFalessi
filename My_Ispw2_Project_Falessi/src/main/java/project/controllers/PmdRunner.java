package project.controllers;

import net.sourceforge.pmd.*;
import net.sourceforge.pmd.renderers.Renderer;
import net.sourceforge.pmd.renderers.TextRenderer;
import net.sourceforge.pmd.util.datasource.DataSource;
import net.sourceforge.pmd.util.datasource.FileDataSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import project.models.ClassFile;
import project.models.Release;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;



public class PmdRunner {
    public static final String rulesSetPath = "/Users/francescoastolfi/progetto-java/falessi_fra/method_ck_definitive/My_Ispw2_Project_Falessi/config/pmd/custom_rules.xml";
    /**
     * Esegue l'analisi PMD su un file o directory
     */
    public static Report runPmdAnalysis(String sourceFilePath) throws IOException {
        PMDConfiguration configuration = new PMDConfiguration();
        configuration.setInputPaths(sourceFilePath);
        configuration.setRuleSets(rulesSetPath);
        configuration.setIgnoreIncrementalAnalysis(true);

        RuleContext context = new RuleContext();

        try (StringWriter reportOutput = new StringWriter()) {
            Renderer renderer = new TextRenderer();
            renderer.setWriter(reportOutput);
            renderer.start();

            File sourceFile = new File(sourceFilePath);
            try (InputStream inputStream = new FileInputStream(sourceFile)) {
                DataSource dataSource = new FileDataSource(sourceFile);
                List<DataSource> files = Collections.singletonList(dataSource);

                RuleSetFactory ruleSetFactory = new RuleSetFactory();

                PMD.processFiles(configuration, ruleSetFactory, files, context, Collections.singletonList(renderer));
            }

            renderer.end();
            renderer.flush();
            renderer.getWriter().close();
            System.gc();  // Forza garbage collection per chiudere file e loader non referenziati


            return context.getReport();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Estrae solo il nome della classe da un nome di classe completo.
     * Rimuove il package, il modulo e qualsiasi riferimento a classi interne o anonime.
     *
     * @param fullClassName il nome completo della classe (es. "org.example.benchmark.MyClass$InnerClass")
     * @return solo il nome della classe (es. "MyClass")
     */
    public static String extractClassNameOnly(String fullClassName) {
        // Rimuove eventuali classi interne o anonime (es. $Anonymous4)
//        System.out.println("Full class name: " + fullClassName);
        int dollarIndex = fullClassName.indexOf('$');
        String cleanName = (dollarIndex != -1) ? fullClassName.substring(0, dollarIndex) : fullClassName;

        // Estrae solo il nome della classe senza package/modulo
        int lastDot = cleanName.lastIndexOf('.');
        String classNameOnly = (lastDot != -1) ? cleanName.substring(lastDot + 1) : cleanName;
//        System.out.println("Class name only: " + classNameOnly);
        return classNameOnly;
    }
    public static String normalizePathToModuleAndClass(String fullPath) {
        // Normalizza i separatori per compatibilità cross-platform
        String normalizedPath = fullPath.replace("\\", "/");

        // Cerca la parte dopo "src/main/java/"
        String marker = "/src/main/java/";
        int index = normalizedPath.indexOf(marker);
        if (index == -1) {
            return ""; // oppure lancia eccezione se preferisci
        }

        // Estrai la parte relativa al package
        String relativePath = normalizedPath.substring(index + marker.length());

        // Rimuove estensione .java o .class
        if (relativePath.endsWith(".java")) {
            relativePath = relativePath.substring(0, relativePath.length() - 5);
        } else if (relativePath.endsWith(".class")) {
            relativePath = relativePath.substring(0, relativePath.length() - 6);
        }

        // Dividi il percorso in parti
        String[] parts = relativePath.split("/");

        // Rimuove prefisso tipo org/apache/hedwig lasciando solo da modulo (es: zookeeper)
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].equals("org") && !parts[i].equals("apache") && !parts[i].equals("bookkeeper") && !parts[i].equals("hedwig")) {
                // Concatena con slash invece di punto
                return parts[i] + "/" + parts[parts.length - 1];
            }
        }

        // Caso fallback, restituisce solo nome file
        return parts[parts.length - 1];
    }









    /**
     * Raccoglie le metriche di code smell per ogni classe
     */
    public static Map<Object,Integer> collectCodeSmellMetricsProject(String projectPath) {
        Map<Object, Integer> classMetrics = new HashMap<>();
        int nSmells=0;
        try {
            // Trova tutti i file .java nel progetto
            List<String> javaFiles = findJavaFiles(projectPath);

            for (String javaFile : javaFiles) {
                Report report = runPmdAnalysis(javaFile);
                if (report == null) continue;

                String className = normalizePathToModuleAndClass(javaFile) ;
                // Estrai il nome della classe dal percorso del file
                //System.out.println("from File: " + javaFile +"to"+ className );



                // Inizializza le metriche per questa classe
                // Inizializza le metriche per questa classe
                Map<String, Integer> metrics = new HashMap<>();
                Set<String> rulesNames = getRuleNamesFromXml(rulesSetPath);
                for (String rule : rulesNames) {
                    metrics.put(rule, 0);
                }



                // Conta le occorrenze di ogni tipo di violazione
                Iterator<RuleViolation> violations = report.iterator();
                while (violations.hasNext()) {
                    RuleViolation violation = violations.next();
                    String ruleName = violation.getRule().getName();
                    if (metrics.containsKey(ruleName)) {
                        metrics.put(ruleName, metrics.get(ruleName) + 1);
                        nSmells++;
                    }
                }

                classMetrics.put(className, nSmells);
               // System.out.println("Class: " + className+ " - " + metrics );
                if (nSmells > 0) {
                    //System.out.println("Class: " + className+ " - " + nSmells );
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            nSmells=-1;
        }

        return classMetrics;
    }



    public static int collectCodeSmellMetricsClass(String classPath, String projectPath, int startLine, int endLine) {
        String className = extractClassNameOnly(classPath);
        int nSmells = 0;

        try {
            String javaFile = findClassFile(className, projectPath);
            if (javaFile == null) {
                System.err.println("File non trovato per la classe: " + className);
                return 0;
            }

            Report report = runPmdAnalysis(javaFile);

            Set<String> ruleNames = getRuleNamesFromXml(rulesSetPath);

            // Inizializza le metriche per questa classe
            Map<String, Integer> metrics = new HashMap<>();
            for (String rule : ruleNames) {
                metrics.put(rule, 0);
            }



            // Conta le occorrenze di ogni tipo di violazione
            Iterator<RuleViolation> violations = report.iterator();
            while (violations.hasNext()) {
                RuleViolation violation = violations.next();
                int line = violation.getBeginLine();
                String ruleName = violation.getRule().getName();

                // Filtra le violazioni tra startLine e endLine (inclusi)
                if (line >= startLine && line <= endLine) {
                    if (metrics.containsKey(ruleName)) {
                        metrics.put(ruleName, metrics.get(ruleName) + 1);
                        nSmells++;
                    }
                }
            }

            if (nSmells > 0) {
               // System.out.println("Class: " + className + " - " + nSmells);
            }

        } catch (Exception e) {
            System.err.println("Errore nell'analisi della classe " + className + ": " + e.getMessage());
            e.printStackTrace();
            nSmells=-1;
        }

        return nSmells;
    }
    /**
     * Estrae i nomi delle regole dal file XML specificato
     */
    private static Set<String> getRuleNamesFromXml(String rulesFilePath) {
        Set<String> ruleNames = new HashSet<>();
        try (InputStream inputStream = new FileInputStream(rulesFilePath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(inputStream);

            NodeList ruleElements = document.getElementsByTagName("rule");
            for (int i = 0; i < ruleElements.getLength(); i++) {
                Element ruleElement = (Element) ruleElements.item(i);
                String refAttribute = ruleElement.getAttribute("ref");
                if (refAttribute != null && !refAttribute.isEmpty()) {
                    String[] parts = refAttribute.split("/");
                    String ruleName = parts[parts.length - 1];
                    ruleNames.add(ruleName);
                }
            }
        } catch (Exception e) {
            System.err.println("Errore durante l'estrazione delle regole dal file XML: " + e.getMessage());
            e.printStackTrace();
        }
        return ruleNames;
    }


    /**
     * Trova tutti i file .java nel progetto
     */
    private static List<String> findJavaFiles(String projectPath) throws Exception {
        return Files.walk(Paths.get(projectPath))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .map(Path::toString)
                .collect(Collectors.toList());
    }
    /**
     * Trova il file Java corrispondente al nome della classe specificato
     * @param className il nome della classe da cercare
     * @param projectPath il percorso del progetto in cui cercare
     * @return il percorso completo del file della classe, o null se non trovato
     */
    /**
     * Trova il file Java corrispondente al nome della classe specificato
     * usando un approccio manuale che consuma meno risorse di sistema.
     *
     * @param className il nome della classe da cercare
     * @param projectPath il percorso del progetto in cui cercare
     * @return il percorso completo del file della classe, o null se non trovato
     */
    private static String findClassFile(String className, String projectPath) throws IOException {
        String targetFileName = className + ".java";

        // Coda per BFS
        Queue<File> directories = new LinkedList<>();
        directories.add(new File(projectPath));

        // Evita directory non utili (per performance)
        Set<String> ignoredDirs = Set.of("target", ".git", "build", "out", "node_modules");

        // Profondità massima (es. evita esplorazioni infinite o troppo profonde)
        final int MAX_DEPTH = 20;

        // Mappa directory -> profondità
        Map<File, Integer> depthMap = new HashMap<>();
        depthMap.put(new File(projectPath), 0);

        while (!directories.isEmpty()) {
            File currentDir = directories.poll();
            int currentDepth = depthMap.getOrDefault(currentDir, 0);

            if (currentDepth > MAX_DEPTH) {
                continue;
            }

            File[] files = currentDir.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (file.isDirectory()) {
                    if (!ignoredDirs.contains(file.getName())) {
                        directories.add(file);
                        depthMap.put(file, currentDepth + 1);
                    }
                } else if (file.isFile() && file.getName().equals(targetFileName)) {
                    return file.getAbsolutePath();
                }
            }
        }

        return null;
    }










}
